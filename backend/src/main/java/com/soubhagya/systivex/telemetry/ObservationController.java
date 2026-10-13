package com.soubhagya.systivex.telemetry;

import com.soubhagya.systivex.observation.LoopbackGuard;
import com.soubhagya.systivex.observation.SyncForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explicit observation trigger for the Phase 6 telemetry connector. No
 * schedulers, background pollers, or watchers: an operator invokes this
 * endpoint and the connector pulls the configured local sources once.
 *
 * <p>Localhost-only (same pattern as the Phase 4 sync trigger): this phase
 * adds no authentication system, so the mutating trigger refuses
 * non-loopback peers instead of serving the network. The request carries
 * no URLs — collection targets come only from validated twin metadata on
 * the loopback interface.
 */
@RestController
@RequestMapping("/api/v1/twin")
public class ObservationController {

    private final TelemetryObservationService observationService;
    private final LoopbackGuard guard;

    public ObservationController(TelemetryObservationService observationService, LoopbackGuard guard) {
        this.observationService = observationService;
        this.guard = guard;
    }

    /** Empty request: {@code {}} observes all managed services. */
    public record ObserveRequest() {}

    @PostMapping("/observations")
    public ObservationReceipt observe(
            @RequestBody(required = false) ObserveRequest request, HttpServletRequest http) {
        try {
            guard.check(http);
        } catch (SyncForbiddenException e) {
            throw new ObservationForbiddenException(
                    "Twin observations are available on the loopback interface only");
        }
        return observationService.observe();
    }
}
