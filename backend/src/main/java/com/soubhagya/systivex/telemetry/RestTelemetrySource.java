package com.soubhagya.systivex.telemetry;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Production {@link TelemetrySource}: pulls Actuator health and Prometheus
 * exposition from the loopback interface only.
 *
 * <p>Bounded by construction: host is the constant {@code 127.0.0.1} (never
 * caller input — the request body carries no URLs), ports come only from
 * validated twin metadata, timeouts are short, and oversized or malformed
 * bodies are rejected by the parsers before any write. Any transport
 * failure yields an unavailable snapshot so the service records a
 * limitation instead of inferring evidence.
 */
@Component
public class RestTelemetrySource implements TelemetrySource {

    static final String LOOPBACK_HOST = "127.0.0.1";

    private final RestClient client;

    public RestTelemetrySource() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Snapshot fetch(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid telemetry port: " + port);
        }
        String health = get("http://" + LOOPBACK_HOST + ":" + port + "/actuator/health");
        String metrics = get("http://" + LOOPBACK_HOST + ":" + port + "/actuator/prometheus");
        return new Snapshot(health, metrics);
    }

    private String get(String url) {
        try {
            return client.get().uri(url).retrieve().body(String.class);
        } catch (Exception e) {
            return null;
        }
    }
}
