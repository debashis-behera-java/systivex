package com.soubhagya.systivex.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure unit tests for the Actuator health parser. */
class HealthParserTest {

    @ParameterizedTest
    @ValueSource(strings = {"UP", "DOWN", "OUT_OF_SERVICE", "UNKNOWN"})
    void knownStatusesParse(String status) {
        assertThat(HealthParser.parse("{\"status\":\"" + status + "\"}")).isEqualTo(status);
    }

    @Test
    void fullActuatorBodyParses() {
        String body = "{\"status\":\"UP\",\"components\":{\"db\":{\"status\":\"UP\"}}}";

        assertThat(HealthParser.parse(body)).isEqualTo("UP");
    }

    @Test
    void missingStatusFails() {
        assertThatThrownBy(() -> HealthParser.parse("{\"components\":{}}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unexpectedStatusFails() {
        assertThatThrownBy(() -> HealthParser.parse("{\"status\":\"COMPROMISED\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedJsonFails() {
        assertThatThrownBy(() -> HealthParser.parse("not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyBodyFails() {
        assertThatThrownBy(() -> HealthParser.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
