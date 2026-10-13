package com.soubhagya.systivex.telemetry;

/**
 * Bounded source of raw local telemetry for one observation run.
 *
 * <p>Implementations fetch only from the loopback interface and only for
 * ports the twin already records. Transport failures are reported as
 * unavailable legs (null bodies), never thrown: a stopped service is
 * missing evidence, not malformed evidence. Validation failures are thrown
 * by the parsers downstream and abort the run.
 */
public interface TelemetrySource {

    /** Raw response bodies for one service; a null leg means unavailable. */
    record Snapshot(String healthBody, String metricsBody) {}

    Snapshot fetch(int port);
}
