package com.soubhagya.systivex.intelligence;

import com.soubhagya.systivex.twin.model.SystemEntity;
import com.soubhagya.systivex.twin.model.SystemEntityType;
import com.soubhagya.systivex.twin.repository.SystemEntityRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 7 deterministic change-risk assessment: a pure, explainable
 * function over one Phase 5 impact traversal plus Phase 6 runtime
 * metadata, evaluated in a single read-only transaction.
 *
 * <p>Reuse, not duplication: traversal, validation (400/404), sorting,
 * caps, and cycle handling all stay inside {@link ImpactAnalysisService};
 * this service only classifies the returned impacted set. It performs no
 * writes, no migration, no filesystem or network access. Only {@code
 * SERVICE} entities carry {@code runtime} metadata, so only services
 * receive health/counter/freshness reasoning — everything else is
 * assessed structurally (see {@link RiskTaxonomy}).
 */
@Service
public class RiskService {

    static final String NOTE =
            "Advisory risk classification (taxonomy risk-v1): potentially affected"
                    + " does not mean confirmed breakage. Levels combine structural reachability"
                    + " (depth, evidence-path fan-out) with last-known runtime evidence for services"
                    + " (health, cumulative failure counters, 30-minute freshness); missing or stale"
                    + " evidence raises caution, never lowers it. This result never authorizes an action;"
                    + " verify against the twin and live backend state before acting on it.";

    private final ImpactAnalysisService impactService;
    private final SystemEntityRepository entities;

    public RiskService(ImpactAnalysisService impactService, SystemEntityRepository entities) {
        this.impactService = impactService;
        this.entities = entities;
    }

    /**
     * Validates raw query input via the impact service (blank/oversized
     * input fails with {@link IllegalArgumentException}, 400; unknown
     * identifiers fail with 404) and classifies the impacted set.
     */
    @Transactional(readOnly = true)
    public RiskResponse assess(
            List<String> rawFrom, Integer rawMaxDepth, List<String> rawIncludeTypes) {
        ImpactResponse impact = impactService.analyze(rawFrom, rawMaxDepth, rawIncludeTypes);
        Instant now = Instant.now();
        Map<UUID, SystemEntity> index = new HashMap<>();
        for (SystemEntity entity : entities.findAll()) {
            index.put(entity.getId(), entity);
        }
        Map<UUID, Integer> pathCounts = new HashMap<>();
        for (ImpactResponse.ImpactPath path : impact.paths()) {
            pathCounts.merge(path.targetId(), 1, Integer::sum);
        }
        List<RiskResponse.RiskEntry> risks = new ArrayList<>();
        for (ImpactResponse.ImpactedEntity impacted : impact.impacted()) {
            SystemEntity entity = index.get(impacted.id());
            boolean service =
                    entity != null && entity.getType() == SystemEntityType.SERVICE;
            Object runtime =
                    entity == null || entity.getMetadata() == null
                            ? null
                            : entity.getMetadata().get("runtime");
            Assessed assessed = assessOne(impacted.depth(), pathCounts.getOrDefault(
                    impacted.id(), 0), service, runtime instanceof Map ? runtime : null, now);
            risks.add(
                    new RiskResponse.RiskEntry(
                            impacted.id(),
                            impacted.type().name(),
                            impacted.name(),
                            impacted.externalRef(),
                            impacted.depth(),
                            impacted.viaStart(),
                            assessed.level(),
                            List.copyOf(assessed.reasons()),
                            assessed.uncertain(),
                            assessed.stale()));
        }
        risks.sort(
                Comparator.comparingInt((RiskResponse.RiskEntry e) -> severityRank(e.level()))
                        .thenComparing(
                                RiskResponse.RiskEntry::externalRef,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(e -> e.id().toString()));
        return new RiskResponse(
                impact.startedFrom(),
                List.copyOf(risks),
                impact.maxDepth(),
                RiskTaxonomy.VERSION,
                Instant.now(),
                impact.entityCount(),
                impact.relationshipCount(),
                NOTE);
    }

    private static int severityRank(RiskLevel level) {
        return switch (level) {
            case HIGH -> 0;
            case UNKNOWN -> 1;
            case MEDIUM -> 2;
            case LOW -> 3;
        };
    }

    record Assessed(
            RiskLevel level, TreeSet<String> reasons, boolean uncertain, boolean stale) {}

    /**
     * Pure deterministic classification core: no repository access, so unit
     * tests can exercise every branch without a database.
     */
    static Assessed assessOne(
            int depth, int pathCount, boolean service, Object runtime, Instant now) {
        TreeSet<String> reasons = new TreeSet<>();
        RiskLevel level;
        if (depth <= 1) {
            level = RiskLevel.MEDIUM;
            reasons.add("directly connected to the change (depth " + depth + ")");
        } else {
            level = RiskLevel.LOW;
            reasons.add("transitively reachable (depth " + depth + ")");
        }
        if (pathCount >= RiskTaxonomy.BROAD_EXPOSURE_PATHS) {
            level = raise(level);
            reasons.add(
                    pathCount + " independent evidence paths reach this component");
        }
        if (!service) {
            return new Assessed(level, reasons, false, false);
        }
        if (!(runtime instanceof Map<?, ?> runtimeMap)) {
            reasons.add("no runtime evidence for this service; assessment structural only");
            return new Assessed(RiskLevel.UNKNOWN, reasons, true, true);
        }
        boolean stale = isStale(runtimeMap, now);
        if (isFutureTimestamp(runtimeMap, now)) {
            reasons.add("runtime evidence timestamp is in the future; treated as stale");
        } else if (stale) {
            reasons.add(
                    "runtime evidence stale (older than "
                            + RiskTaxonomy.STALE_AFTER.toMinutes()
                            + "m); severe signals retained");
        }
        String healthStatus = healthStatus(runtimeMap);
        boolean healthAvailable = isHealthAvailable(runtimeMap);
        if (healthAvailable
                && ("DOWN".equals(healthStatus) || "OUT_OF_SERVICE".equals(healthStatus))) {
            level = RiskLevel.HIGH;
            reasons.add("service reports " + healthStatus + " at last observation");
        } else if (!healthAvailable) {
            reasons.add("health unavailable at last observation");
        }
        for (Map.Entry<String, Long> counter : failureCounters(runtimeMap).entrySet()) {
            level = raise(level);
            reasons.add(
                    "cumulative "
                            + counter.getKey()
                            + "="
                            + counter.getValue()
                            + " recorded; lifetime evidence, not incident proof");
        }
        boolean uncertain = stale || !healthAvailable;
        return new Assessed(level, reasons, uncertain, stale);
    }

    private static RiskLevel raise(RiskLevel level) {
        return switch (level) {
            case LOW -> RiskLevel.MEDIUM;
            case MEDIUM -> RiskLevel.HIGH;
            case HIGH, UNKNOWN -> level;
        };
    }

    private static boolean isStale(Map<?, ?> runtime, Instant now) {
        Object observedAt = runtime.get("observedAt");
        if (!(observedAt instanceof String text)) {
            return true;
        }
        final Instant observed;
        try {
            observed = Instant.parse(text.trim());
        } catch (Exception e) {
            return true;
        }
        Duration age = Duration.between(observed, now);
        if (age.isNegative()) {
            // A future observation timestamp (clock skew or tampered
            // metadata) is evidence that cannot be trusted: stale and
            // uncertain, never fresh merely because its age is negative.
            return true;
        }
        return age.compareTo(RiskTaxonomy.STALE_AFTER) > 0;
    }

    private static boolean isFutureTimestamp(Map<?, ?> runtime, Instant now) {
        Object observedAt = runtime.get("observedAt");
        if (!(observedAt instanceof String text)) {
            return false;
        }
        try {
            return Instant.parse(text.trim()).isAfter(now);
        } catch (Exception e) {
            return false;
        }
    }

    private static String healthStatus(Map<?, ?> runtime) {
        Object health = runtime.get("health");
        if (health instanceof Map<?, ?> healthMap) {
            Object status = healthMap.get("status");
            return status == null ? null : String.valueOf(status);
        }
        return null;
    }

    private static boolean isHealthAvailable(Map<?, ?> runtime) {
        Object health = runtime.get("health");
        return health instanceof Map<?, ?> healthMap
                && Boolean.TRUE.equals(healthMap.get("available"));
    }

    private static Map<String, Long> failureCounters(Map<?, ?> runtime) {
        Map<String, Long> failures = new HashMap<>();
        Object counters = runtime.get("counters");
        if (counters instanceof Map<?, ?> countersMap) {
            for (Map.Entry<?, ?> entry : countersMap.entrySet()) {
                String name = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                boolean failure = false;
                for (String suffix : RiskTaxonomy.FAILURE_FAMILY_SUFFIXES) {
                    if (name.endsWith(suffix)) {
                        failure = true;
                        break;
                    }
                }
                if (failure && entry.getValue() instanceof Number number
                        && number.longValue() > 0) {
                    failures.put(String.valueOf(entry.getKey()), number.longValue());
                }
            }
        }
        return failures;
    }
}
