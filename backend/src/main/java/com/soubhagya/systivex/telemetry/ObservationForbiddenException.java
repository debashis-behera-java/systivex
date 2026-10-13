package com.soubhagya.systivex.telemetry;

/**
 * Twin observations are available on the loopback interface only (same
 * boundary as the Phase 4 sync trigger). Mapped to HTTP 403.
 */
public class ObservationForbiddenException extends RuntimeException {

    public ObservationForbiddenException(String message) {
        super(message);
    }
}
