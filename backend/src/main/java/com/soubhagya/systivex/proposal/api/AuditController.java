package com.soubhagya.systivex.proposal.api;

import com.soubhagya.systivex.proposal.service.AuditService;
import com.soubhagya.systivex.proposal.service.AuditVerification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Governance audit reads (Phase 11 S2, D29).
 *
 * <p>The only audit endpoint in this slice is chain verification: bounded,
 * read-only, and never repairing. There is deliberately no HTTP endpoint
 * that appends audit events — events are written only inside server-side
 * transactions by later lifecycle slices, so callers can never fabricate
 * chain entries.
 */
@RestController
@RequestMapping("/api/v1/twin")
public class AuditController {

    private final AuditService audits;

    public AuditController(AuditService audits) {
        this.audits = audits;
    }

    @GetMapping("/audit/verify")
    public AuditVerification verify() {
        return audits.verify();
    }
}
