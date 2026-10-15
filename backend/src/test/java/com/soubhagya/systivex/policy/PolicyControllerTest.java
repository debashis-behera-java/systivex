package com.soubhagya.systivex.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.soubhagya.systivex.intelligence.ProposalConflictException;
import com.soubhagya.systivex.intelligence.SimulationDelta;
import com.soubhagya.systivex.intelligence.SimulationGraphSummary;
import com.soubhagya.systivex.intelligence.SimulationRequest;
import com.soubhagya.systivex.intelligence.SimulationSettings;
import com.soubhagya.systivex.twin.api.RestExceptionHandler;
import com.soubhagya.systivex.twin.service.EntityNotFoundException;
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
 * HTTP routing for advisory policy evaluation without a database or Spring
 * context: the POST route uses the exact simulation proposal wire format,
 * and shape/conflict/missing failures map to 400/409/404 through the
 * existing handler. Full decision behaviour lives in
 * {@code PolicyApiTest} (Docker) and the pure {@code PolicyEvaluatorTest}
 * unit suite.
 */
class PolicyControllerTest {

    private final AtomicReference<Function<SimulationRequest, PolicyEvaluationResponse>> behavior =
            new AtomicReference<>();

    private MockMvc mvc;

    private static PolicyEvaluationResponse review() {
        return new PolicyEvaluationResponse(
                PolicyDecision.REVIEW,
                PolicyTaxonomy.VERSION,
                PolicyService.ADVISORY,
                new SimulationSettings(6, Set.of(), 1),
                List.of(
                        new PolicyEvaluator.FiredRule(
                                PolicyTaxonomy.UNKNOWN_RISK_PRESENT,
                                PolicyDecision.REVIEW,
                                "1 hypothetical impacted components carry UNKNOWN risk"
                                        + " (missing evidence); missing evidence is not safety.")),
                new PolicyEvaluationResponse.SimulationSummary(
                        new SimulationGraphSummary(1, 1, 0, 0, 0, 0, 0),
                        new SimulationDelta(
                                0, 0, 0, 0, List.of(), List.of(), 0, 0, 0, 0, List.of(),
                                0, 0, false),
                        List.of(PolicyService.ADVISORY)),
                PolicyService.NOTE);
    }

    @BeforeEach
    void setup() {
        PolicyService stub =
                new PolicyService(null) {
                    @Override
                    public PolicyEvaluationResponse evaluate(SimulationRequest request) {
                        return behavior.get().apply(request);
                    }
                };
        mvc =
                MockMvcBuilders.standaloneSetup(new PolicyController(stub))
                        .setControllerAdvice(new RestExceptionHandler())
                        .build();
    }

    @Test
    void postPolicyEvaluationsReturnsAdvisoryDecision() throws Exception {
        behavior.set(request -> review());

        mvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/twin/policy-evaluations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"from":["target-service:order-service"],"operations":[]}\
                                        """))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.decision").value("REVIEW"))
                .andExpect(
                        MockMvcResultMatchers.jsonPath("$.policyVersion").value("policy-v1"))
                .andExpect(
                        MockMvcResultMatchers.jsonPath("$.advisory")
                                .value(PolicyService.ADVISORY))
                .andExpect(MockMvcResultMatchers.jsonPath("$.rulesFired").isArray())
                .andExpect(MockMvcResultMatchers.jsonPath("$.simulation").exists())
                .andExpect(MockMvcResultMatchers.jsonPath("$.note").exists());
    }

    @Test
    void shapeViolationMapsTo400() throws Exception {
        behavior.set(
                request -> {
                    throw new IllegalArgumentException(
                            "entity is required for REMOVE_ENTITY");
                });

        mvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/twin/policy-evaluations")
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
                        MockMvcRequestBuilders.post("/api/v1/twin/policy-evaluations")
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
                                MockMvcRequestBuilders.post("/api/v1/twin/policy-evaluations")
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
