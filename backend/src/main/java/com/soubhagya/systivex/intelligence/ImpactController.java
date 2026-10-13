package com.soubhagya.systivex.intelligence;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only change-impact query over the persisted twin graph.
 *
 * <p>No mutation, no filesystem access, no external calls: this endpoint only
 * reads twin rows inside one read-only transaction (see
 * {@link ImpactAnalysisService}). Validation failures fail closed with 400
 * (reuse of the existing handler via {@link IllegalArgumentException});
 * unknown identifiers fail with 404. Parameters are optional at the Spring
 * binding level so that missing or blank input also fails closed with 400
 * instead of 500.
 */
@RestController
@RequestMapping("/api/v1/twin")
public class ImpactController {

    private final ImpactAnalysisService impactService;

    public ImpactController(ImpactAnalysisService impactService) {
        this.impactService = impactService;
    }

    @GetMapping("/impact")
    public ImpactResponse impact(
            @RequestParam(name = "from", required = false) List<String> from,
            @RequestParam(name = "maxDepth", required = false) Integer maxDepth,
            @RequestParam(name = "includeTypes", required = false) List<String> includeTypes) {
        return impactService.analyze(from, maxDepth, includeTypes);
    }
}
