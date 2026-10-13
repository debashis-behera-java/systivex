package com.soubhagya.systivex.intelligence;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only change-risk assessment over the persisted twin graph plus
 * Phase 6 runtime metadata.
 *
 * <p>No mutation, no filesystem access, no external calls: traversal and
 * validation stay inside {@link ImpactAnalysisService} (blank/oversized
 * input fails 400, unknown identifiers 404, via the existing handler);
 * this endpoint only classifies the returned impacted set inside one
 * read-only transaction. Parameters are optional at the Spring binding
 * level so missing input fails closed with 400 instead of 500. The result
 * is advisory and never authorizes an action.
 */
@RestController
@RequestMapping("/api/v1/twin")
public class RiskController {

    private final RiskService riskService;

    public RiskController(RiskService riskService) {
        this.riskService = riskService;
    }

    @GetMapping("/risk")
    public RiskResponse risk(
            @RequestParam(name = "from", required = false) List<String> from,
            @RequestParam(name = "maxDepth", required = false) Integer maxDepth,
            @RequestParam(name = "includeTypes", required = false) List<String> includeTypes) {
        return riskService.assess(from, maxDepth, includeTypes);
    }
}
