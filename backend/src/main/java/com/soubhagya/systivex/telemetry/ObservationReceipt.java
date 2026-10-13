package com.soubhagya.systivex.telemetry;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of one twin telemetry observation run. Returned directly from
 * the request; no run history is persisted (no approved migration for it).
 *
 * <p>Idempotency/timestamp contract: {@code observedAt} always advances to
 * the run time, and stored metadata timestamps advance with it, so
 * staleness can never be hidden. {@code factsUpdated} counts services
 * whose health status or counter values differed from the stored facts;
 * {@code factsUnchanged} counts services whose facts matched even though
 * their observation timestamp refreshed. A repeated poll with identical
 * counters therefore reports {@code factsUpdated: 0} while still proving
 * the services were re-observed now.
 */
public record ObservationReceipt(
        Instant observedAt,
        int servicesProcessed,
        int factsUpdated,
        int factsUnchanged,
        List<ServiceObservation> services,
        List<String> limitations,
        String note) {}
