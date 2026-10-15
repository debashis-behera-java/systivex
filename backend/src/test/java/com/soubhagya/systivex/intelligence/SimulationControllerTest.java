package com.soubhagya.systivex.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.twin.api.RestExceptionHandler;
import com.soubhagya.systivex.twin.service.EntityNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP routing for the simulation endpoint without a database or Spring
 * context: the POST route delegates to {@link SimulationService}, and
 * shape/conflict/missing failures map to 400/409/404 through the existing
 * handler. Full before/after behaviour lives in {@code SimulationApiTest}
 * (Docker) and the pure {@code Simulation*Test} unit suites.
 */
class SimulationControllerTest {

    private final AtomicReference<Function<SimulationRequest, SimulationResponse>> behavior =
            new AtomicReference<>();

    private MockMvc mvc;

    private static SimulationResponse ok() {
        ImpactResponse impact =
                new ImpactResponse(
                        List.of(), List.of(), List.of(), 6, 0, 0, false, null, false,
                        Instant.EPOCH, 1, 0,
                        new ImpactResponse.Limits(10, 10, 500, 2000), "note");
        RiskResponse risk =
                new RiskResponse(
                        List.of(), List.of(), 6, "risk-v1", Instant.EPOCH, 1, 0, "note");
        return new SimulationResponse(
                true,
                SimulationService.ADVISORY,
                new SimulationSettings(6, Set.of(), 1),
                new SimulationResponse.Scope(impact, risk),
                new SimulationResponse.Scope(impact, risk),
                new SimulationGraphSummary(1, 1, 0, 0, 0, 0, 0),
                new SimulationDelta(
                        0, 0, 0, 0, List.of(), List.of(), 0, 0, 0, 0, List.of(), 0, 0,
                        false),
                List.of(SimulationService.ADVISORY));
    }

    @BeforeEach
    void setup() {
        SimulationService stub =
                new SimulationService(null, null, null, null) {
                    @Override
                    public SimulationResponse simulate(SimulationRequest request) {
                        return behavior.get().apply(request);
                    }
                };
        mvc =
                MockMvcBuilders.standaloneSetup(new SimulationController(stub))
                        .setControllerAdvice(new RestExceptionHandler())
                        .build();
    }

    @Test
    void postSimulationsDelegatesAndReturnsSimulatedResponse() throws Exception {
        behavior.set(request -> ok());

        mvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/twin/simulations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"from":["target-service:order-service"],"operations":[]}\
                                        """))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.simulated").value(true))
                .andExpect(
                        MockMvcResultMatchers.jsonPath("$.advisory")
                                .value(SimulationService.ADVISORY))
                .andExpect(MockMvcResultMatchers.jsonPath("$.before").exists())
                .andExpect(MockMvcResultMatchers.jsonPath("$.after").exists())
                .andExpect(MockMvcResultMatchers.jsonPath("$.delta").exists())
                .andExpect(MockMvcResultMatchers.jsonPath("$.limitations").isArray());
    }

    @Test
    void shapeViolationMapsTo400() throws Exception {
        behavior.set(
                request -> {
                    throw new IllegalArgumentException(
                            "entity is required for REMOVE_ENTITY");
                });

        mvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/twin/simulations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"from":["svc:a"],"operations":[{"type":"REMOVE_ENTITY"}]}\
                                        """))
                .andExpect(MockMvcResultMatchers.status().isBadRequest());
    }

    @Test
    void unknownIdentifierMapsTo404() throws Exception {
        behavior.set(
                request -> {
                    throw new EntityNotFoundException("SystemEntity not found: svc:missing");
                });

        mvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/twin/simulations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"from":["svc:missing"],"operations":[]}\
                                        """))
                .andExpect(MockMvcResultMatchers.status().isNotFound());
    }

    @Test
    void proposalConflictMapsTo409WithoutInternals() throws Exception {
        behavior.set(
                request -> {
                    throw new ProposalConflictException("Edge already exists: a|b|CALLS");
                });

        String body =
                mvc.perform(
                                MockMvcRequestBuilders.post("/api/v1/twin/simulations")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"from":["svc:a"],"operations":[]}\
                                                """))
                        .andExpect(MockMvcResultMatchers.status().isConflict())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).contains("Edge already exists");
        assertThat(body).doesNotContain("stackTrace", "at com.soubhagya");
    }
}
