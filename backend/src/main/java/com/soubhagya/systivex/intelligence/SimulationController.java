package com.soubhagya.systivex.intelligence;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only change-simulation endpoint over the persisted twin graph.
 *
 * <p>No mutation, no persistence, no execution: the proposal in the request
 * body is validated and applied to in-memory working copies only, and both
 * scopes reuse the existing impact traversal and risk classification
 * inside one read-only transaction (see {@link SimulationService}).
 * Validation failures fail closed with 400 (shape) / 404 (unknown
 * identifiers) via the existing handler; proposal conflicts fail with 409.
 * Every response carries {@code simulated: true} and the unmistakable
 * {@code SIMULATED — NOT EXECUTED} advisory.
 */
@RestController
@RequestMapping("/api/v1/twin")
public class SimulationController {

    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/simulations")
    public SimulationResponse simulate(@RequestBody SimulationRequest request) {
        return simulationService.simulate(request);
    }
}
