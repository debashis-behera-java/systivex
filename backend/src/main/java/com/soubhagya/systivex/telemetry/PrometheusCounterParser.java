package com.soubhagya.systivex.telemetry;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 6 deterministic parser for Prometheus text exposition.
 *
 * <p>Pure, bounded, independently unit-testable: no I/O, no Spring, no
 * state. Returns logical counter names to non-negative integral values.
 *
 * <p>Fail-closed rules (any violation throws {@link IllegalArgumentException}
 * and the whole observation run aborts with no writes):
 *
 * <ul>
 *   <li>payload over {@code MAX_CHARS} or {@code MAX_LINES} is rejected
 *       (bounded cardinality / bounded work);
 *   <li>raw payload matching the privacy deny-list (credentials, tokens,
 *       customer/product identifiers) is rejected — defense in depth on top
 *       of allowlist-only extraction (raw text is never stored);
 *   <li>an allowlisted family carrying labels ({@code {...}}) is rejected
 *       (the instrumented counters are tag-free; labels would be unbounded
 *       cardinality and a leak vector);
 *   <li>a non-allowlisted family containing a business keyword is rejected
 *       as an unsupported metric family (must be reviewed before ingestion);
 *   <li>non-integral, negative, NaN/infinite, or conflicting duplicate
 *       values are rejected (business counters are non-negative integers).
 * </ul>
 *
 * <p>All other families (http_, jvm_, process_, system_, tomcat_,
 * hikaricp_, …) and comment lines ({@code # HELP} / {@code # TYPE}) are
 * ignored. Absent families are reported as absent by the caller — never
 * inferred as zero.
 */
public final class PrometheusCounterParser {

    static final int MAX_CHARS = 262144;
    static final int MAX_LINES = 10000;

    private static final Pattern PRIVACY_DENY =
            Pattern.compile(
                    "password|passwd|secret|bearer|api[_-]?key|access[_-]?token|-----BEGIN"
                            + "|CUST-|SKU-|customerid|productid",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern SAMPLE_LINE =
            Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{[^}]*\\})?\\s+([^\\s]+)(\\s+\\d+)?$");

    private PrometheusCounterParser() {}

    /**
     * Parses exposition text into logical counter values.
     *
     * @throws IllegalArgumentException on any fail-closed rule above
     */
    public static Map<String, Long> parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Prometheus exposition is empty");
        }
        if (body.length() > MAX_CHARS) {
            throw new IllegalArgumentException(
                    "Prometheus exposition exceeds " + MAX_CHARS + " characters");
        }
        if (PRIVACY_DENY.matcher(body).find()) {
            throw new IllegalArgumentException(
                    "Prometheus exposition contains privacy-sensitive content; rejecting run");
        }
        Map<String, Long> counters = new LinkedHashMap<>();
        int lines = 0;
        for (String raw : body.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            lines++;
            if (lines > MAX_LINES) {
                throw new IllegalArgumentException(
                        "Prometheus exposition exceeds " + MAX_LINES + " samples");
            }
            Matcher sample = SAMPLE_LINE.matcher(line);
            if (!sample.matches()) {
                // Non-sample lines (exemplars, unknown extensions) are ignored:
                // only allowlisted counter samples can ever become facts.
                continue;
            }
            String name = sample.group(1);
            boolean labelled = sample.group(2) != null;
            String logical = AllowedCounters.PROMETHEUS_TO_LOGICAL.get(name);
            if (logical == null) {
                if (isBusinessFlavoured(name)) {
                    throw new IllegalArgumentException(
                            "Unsupported metric family: " + name);
                }
                continue;
            }
            if (labelled) {
                throw new IllegalArgumentException(
                        "Unexpected labels on allowlisted counter: " + name);
            }
            long value = parseCounterValue(name, sample.group(3));
            Long previous = counters.get(logical);
            if (previous != null && previous != value) {
                throw new IllegalArgumentException(
                        "Conflicting duplicate samples for counter: " + name);
            }
            counters.put(logical, value);
        }
        return Map.copyOf(counters);
    }

    private static boolean isBusinessFlavoured(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String keyword : AllowedCounters.BUSINESS_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static long parseCounterValue(String name, String raw) {
        final double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Malformed counter value for " + name + ": " + raw);
        }
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
            throw new IllegalArgumentException(
                    "Invalid counter value for " + name + ": " + raw);
        }
        if (value != Math.floor(value) || value > 9_000_000_000_000_000L) {
            throw new IllegalArgumentException(
                    "Non-integral counter value for " + name + ": " + raw);
        }
        return (long) value;
    }
}
