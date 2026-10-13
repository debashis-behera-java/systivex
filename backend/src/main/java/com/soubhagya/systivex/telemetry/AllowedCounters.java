package com.soubhagya.systivex.telemetry;

import java.util.Map;
import java.util.Set;

/**
 * Strict allowlist for Phase 6 telemetry ingestion.
 *
 * <p>Pinned to the metric families the repository actually instruments
 * (verified against the target-service sources):
 *
 * <ul>
 *   <li>order-service {@code CheckoutService}: {@code checkout.success},
 *       {@code checkout.failure}
 *   <li>inventory-service {@code InventoryReservationService}:
 *       {@code inventory.reservation.success},
 *       {@code inventory.reservation.rejected}
 *   <li>payment-service {@code PaymentAuthorizationService}:
 *       {@code payment.authorization.success},
 *       {@code payment.authorization.declined}
 *   <li>gateway-service: no business counters (health only)
 * </ul>
 *
 * <p>Micrometer exposes these tag-free counters on
 * {@code /actuator/prometheus} with dots converted to underscores and a
 * {@code _total} suffix (proven by each service's
 * {@code ObservabilityTest}). Any other business-flavoured family, any
 * label set on an allowlisted family, and any payload-bearing content is
 * rejected fail-closed by the parser — never stored.
 */
public final class AllowedCounters {

    /** Prometheus exposition name to logical counter name. */
    static final Map<String, String> PROMETHEUS_TO_LOGICAL =
            Map.of(
                    "checkout_success_total", "checkout.success",
                    "checkout_failure_total", "checkout.failure",
                    "inventory_reservation_success_total", "inventory.reservation.success",
                    "inventory_reservation_rejected_total", "inventory.reservation.rejected",
                    "payment_authorization_success_total", "payment.authorization.success",
                    "payment_authorization_declined_total", "payment.authorization.declined");

    /**
     * Logical counter families each known service may report. Families
     * outside a service's set are ignored for that service (cross-talk is
     * never attributed). A service short name missing from this map is an
     * unknown service mapping and fails the run.
     */
    static final Map<String, Set<String>> SERVICE_FAMILIES =
            Map.of(
                    "order-service",
                            Set.of("checkout.success", "checkout.failure"),
                    "inventory-service",
                            Set.of("inventory.reservation.success", "inventory.reservation.rejected"),
                    "payment-service",
                            Set.of(
                                    "payment.authorization.success",
                                    "payment.authorization.declined"),
                    "gateway-service", Set.of());

    /**
     * Substrings marking a metric family as business-flavoured. A scraped
     * family containing one of these that is not in the allowlist above is
     * an unsupported metric family and fails the run — it must be reviewed
     * and allowlisted explicitly before ingestion, never absorbed silently.
     */
    static final Set<String> BUSINESS_KEYWORDS =
            Set.of("checkout", "reservation", "authorization", "payment");

    private AllowedCounters() {}
}
