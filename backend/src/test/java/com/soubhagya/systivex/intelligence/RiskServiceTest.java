package com.soubhagya.systivex.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the risk classification core
 * ({@code RiskService.assessOne}): every taxonomy branch, the freshness
 * boundary, uncertainty handling, reason ordering, and the no-false-
 * precision rules — without Spring or a database.
 */
class RiskServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

    private static Map<String, Object> runtime(
            String observedAt, boolean healthAvailable, String status, Map<String, Long> counters) {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("available", healthAvailable);
        health.put("status", status);
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("managedBy", "telemetry-connector");
        runtime.put("observedAt", observedAt);
        runtime.put("health", health);
        runtime.put("counters", new LinkedHashMap<>(counters));
        runtime.put("limitations", List.of());
        return runtime;
    }

    private static Map<String, Object> freshUp(Map<String, Long> counters) {
        return runtime("2026-10-14T09:50:00Z", true, "UP", counters);
    }

    @Test
    void depthOneNonServiceStartsMedium() {
        var assessed = RiskService.assessOne(1, 1, false, null, NOW);

        assertThat(assessed.level()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(assessed.uncertain()).isFalse();
        assertThat(assessed.stale()).isFalse();
        assertThat(assessed.reasons()).isNotEmpty();
    }

    @Test
    void deepNonServiceStartsLow() {
        var assessed = RiskService.assessOne(3, 1, false, null, NOW);

        assertThat(assessed.level()).isEqualTo(RiskLevel.LOW);
        assertThat(assessed.uncertain()).isFalse();
    }

    @Test
    void broadExposureRaisesOneLevel() {
        assertThat(RiskService.assessOne(3, 5, false, null, NOW).level())
                .isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskService.assessOne(1, 6, false, null, NOW).level())
                .isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void raiseNeverExceedsHigh() {
        var assessed = RiskService.assessOne(1, 10, false, null, NOW);

        assertThat(assessed.level()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void twoPathsDoNotRaise() {
        assertThat(RiskService.assessOne(3, 2, false, null, NOW).level())
                .isEqualTo(RiskLevel.LOW);
    }

    @Test
    void routineTriangleAlternativesDoNotRaise() {
        assertThat(RiskService.assessOne(3, 4, false, null, NOW).level())
                .isEqualTo(RiskLevel.LOW);
        assertThat(RiskService.assessOne(1, 4, false, null, NOW).level())
                .isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void serviceWithoutRuntimeIsUnknown() {
        var assessed = RiskService.assessOne(1, 1, true, null, NOW);

        assertThat(assessed.level()).isEqualTo(RiskLevel.UNKNOWN);
        assertThat(assessed.uncertain()).isTrue();
        assertThat(assessed.stale()).isTrue();
        assertThat(String.join(" ", assessed.reasons())).contains("no runtime evidence");
    }

    @Test
    void serviceWithNonMapRuntimeIsUnknown() {
        var assessed = RiskService.assessOne(1, 1, true, "garbage", NOW);

        assertThat(assessed.level()).isEqualTo(RiskLevel.UNKNOWN);
        assertThat(assessed.uncertain()).isTrue();
    }

    @Test
    void freshHealthyServiceWithNoFailuresKeepsStructuralLevel() {
        var assessed = RiskService.assessOne(1, 1, true, freshUp(Map.of()), NOW);

        assertThat(assessed.level()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(assessed.uncertain()).isFalse();
        assertThat(assessed.stale()).isFalse();
    }

    @Test
    void downStatusSetsHigh() {
        var assessed =
                RiskService.assessOne(
                        3, 1, true, runtime("2026-10-14T09:55:00Z", true, "DOWN", Map.of()), NOW);

        assertThat(assessed.level()).isEqualTo(RiskLevel.HIGH);
        assertThat(String.join(" ", assessed.reasons())).contains("DOWN");
        assertThat(assessed.uncertain()).isFalse();
    }

    @Test
    void outOfServiceSetsHigh() {
        var assessed =
                RiskService.assessOne(
                        2,
                        1,
                        true,
                        runtime("2026-10-14T09:55:00Z", true, "OUT_OF_SERVICE", Map.of()),
                        NOW);

        assertThat(assessed.level()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void failureCountersRaiseOneLevelWithLifetimeWording() {
        var assessed =
                RiskService.assessOne(
                        2,
                        1,
                        true,
                        freshUp(Map.of("checkout.failure", 4L, "checkout.success", 100L)),
                        NOW);

        assertThat(assessed.level()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(String.join(" | ", assessed.reasons()))
                .contains("checkout.failure=4")
                .contains("lifetime evidence, not incident proof");
    }

    @Test
    void rejectedAndDeclinedSuffixesCountAsFailures() {
        var assessed =
                RiskService.assessOne(
                        1,
                        1,
                        true,
                        freshUp(Map.of("inventory.reservation.rejected", 1L)),
                        NOW);

        assertThat(assessed.level()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void zeroFailureCountersAddNoReasonAndNeverLower() {
        var assessed =
                RiskService.assessOne(
                        1,
                        1,
                        true,
                        freshUp(Map.of("checkout.failure", 0L, "checkout.success", 50L)),
                        NOW);

        assertThat(assessed.level()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(String.join(" ", assessed.reasons())).doesNotContain("checkout.failure");
    }

    @Test
    void successCountersNeverLower() {
        var assessed =
                RiskService.assessOne(1, 1, true, freshUp(Map.of("checkout.success", 999L)), NOW);

        assertThat(assessed.level()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void exactlyAtThresholdIsFresh() {
        var assessed =
                RiskService.assessOne(
                        2, 1, true, runtime("2026-10-14T09:30:00Z", true, "UP", Map.of()), NOW);

        assertThat(assessed.stale()).isFalse();
        assertThat(assessed.uncertain()).isFalse();
    }

    @Test
    void olderThanThresholdIsStaleButKeepsLevel() {
        var assessed =
                RiskService.assessOne(
                        2, 1, true, runtime("2026-10-14T09:29:59Z", true, "UP", Map.of()), NOW);

        assertThat(assessed.stale()).isTrue();
        assertThat(assessed.uncertain()).isTrue();
        assertThat(assessed.level()).isEqualTo(RiskLevel.LOW);
        assertThat(String.join(" ", assessed.reasons())).contains("stale");
    }

    @Test
    void staleSevereSignalRetainsHigh() {
        var assessed =
                RiskService.assessOne(
                        3,
                        1,
                        true,
                        runtime("2026-10-14T08:00:00Z", true, "DOWN", Map.of()),
                        NOW);

        assertThat(assessed.level()).isEqualTo(RiskLevel.HIGH);
        assertThat(assessed.stale()).isTrue();
        assertThat(assessed.uncertain()).isTrue();
        assertThat(String.join(" ", assessed.reasons())).contains("DOWN");
    }

    @Test
    void unreadableTimestampIsTreatedAsStale() {
        var assessed =
                RiskService.assessOne(
                        2, 1, true, runtime("not-a-timestamp", true, "UP", Map.of()), NOW);

        assertThat(assessed.stale()).isTrue();
        assertThat(assessed.uncertain()).isTrue();
    }

    @Test
    void futureTimestampIsStaleAndUncertain() {
        var assessed =
                RiskService.assessOne(
                        2, 1, true, runtime("2026-10-14T10:00:01Z", true, "UP", Map.of()), NOW);

        assertThat(assessed.stale()).isTrue();
        assertThat(assessed.uncertain()).isTrue();
        assertThat(assessed.level()).isEqualTo(RiskLevel.LOW);
        assertThat(String.join(" ", assessed.reasons()))
                .contains("in the future; treated as stale");
    }

    @Test
    void futureTimestampWithDownRetainsHigh() {
        var assessed =
                RiskService.assessOne(
                        3, 1, true, runtime("2026-10-14T12:00:00Z", true, "DOWN", Map.of()), NOW);

        assertThat(assessed.level()).isEqualTo(RiskLevel.HIGH);
        assertThat(assessed.stale()).isTrue();
        assertThat(assessed.uncertain()).isTrue();
        assertThat(String.join(" ", assessed.reasons())).contains("DOWN");
    }

    @Test
    void unavailableHealthFlagsUncertaintyWithoutLevelChange() {
        var assessed =
                RiskService.assessOne(
                        2, 1, true, runtime("2026-10-14T09:55:00Z", false, null, Map.of()), NOW);

        assertThat(assessed.level()).isEqualTo(RiskLevel.LOW);
        assertThat(assessed.uncertain()).isTrue();
        assertThat(String.join(" ", assessed.reasons())).contains("health unavailable");
    }

    @Test
    void reasonsAreSorted() {
        var assessed =
                RiskService.assessOne(
                        1,
                        5,
                        true,
                        freshUp(Map.of("checkout.failure", 2L)),
                        NOW);

        assertThat(new ArrayList<>(assessed.reasons()))
                .isSorted();
        assertThat(assessed.reasons()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void noNumericScoresAnywhere() {
        var assessed =
                RiskService.assessOne(
                        1,
                        5,
                        true,
                        freshUp(Map.of("checkout.failure", 2L)),
                        NOW);

        for (String reason : assessed.reasons()) {
            assertThat(reason).doesNotContain("probability").doesNotContain("%");
        }
        assertThat(List.of("LOW", "MEDIUM", "HIGH", "UNKNOWN"))
                .contains(assessed.level().name());
    }
}
