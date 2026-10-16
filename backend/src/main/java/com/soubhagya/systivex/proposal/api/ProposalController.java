package com.soubhagya.systivex.proposal.api;

import com.soubhagya.systivex.proposal.service.ProposalAmendmentService;
import com.soubhagya.systivex.proposal.service.ProposalAssessmentService;
import com.soubhagya.systivex.proposal.service.ProposalService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Governed proposals (Phase 11 S3–S4, D29).
 *
 * <p>Actor identity always comes from the validated JWT — request bodies
 * never carry proposer or approver identity. This slice adds assessment,
 * submission, and amendment to the S3 drafts, withdrawal, and reads;
 * approval and rejection arrive later.
 */
@RestController
@RequestMapping("/api/v1/twin/proposals")
public class ProposalController {

    private final ProposalService proposals;

    private final ProposalAssessmentService assessments;

    private final ProposalAmendmentService amendments;

    public ProposalController(
            ProposalService proposals,
            ProposalAssessmentService assessments,
            ProposalAmendmentService amendments) {
        this.proposals = proposals;
        this.assessments = assessments;
        this.amendments = amendments;
    }

    @PostMapping
    public ResponseEntity<ProposalResponse> create(
            @Valid @RequestBody CreateProposalRequest request, JwtAuthenticationToken authentication) {
        ProposalResponse created =
                proposals.create(issuerOf(authentication), subjectOf(authentication), request);
        return ResponseEntity.created(URI.create("/api/v1/twin/proposals/" + created.proposalKey()))
                .body(created);
    }

    @PostMapping("/{key}/withdraw")
    public ProposalResponse withdraw(
            @PathVariable UUID key,
            @Valid @RequestBody(required = false) WithdrawRequest request,
            JwtAuthenticationToken authentication) {
        return proposals.withdraw(
                key,
                issuerOf(authentication),
                subjectOf(authentication),
                isAdmin(authentication),
                request == null ? null : request.reason());
    }

    @PostMapping("/{key}/assess")
    public ProposalResponse assess(
            @PathVariable UUID key, JwtAuthenticationToken authentication) {
        return assessments.assess(key, issuerOf(authentication), subjectOf(authentication));
    }

    @PostMapping("/{key}/submit")
    public ProposalResponse submit(
            @PathVariable UUID key,
            @RequestBody SubmitRequest request,
            JwtAuthenticationToken authentication) {
        return assessments.submit(key, request, issuerOf(authentication), subjectOf(authentication));
    }

    @PatchMapping("/{key}")
    public ProposalResponse amend(
            @PathVariable UUID key,
            @Valid @RequestBody CreateProposalRequest request,
            JwtAuthenticationToken authentication) {
        return amendments.amend(
                key,
                issuerOf(authentication),
                subjectOf(authentication),
                isAdmin(authentication),
                request);
    }

    @GetMapping
    public ProposalListResponse list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return proposals.list(page, size);
    }

    @GetMapping("/{key}")
    public ProposalResponse latest(@PathVariable UUID key) {
        return proposals.latest(key);
    }

    @GetMapping("/{key}/revisions")
    public List<ProposalResponse> history(@PathVariable UUID key) {
        return proposals.history(key);
    }

    @GetMapping("/{key}/revisions/{revisionNumber}")
    public ProposalResponse revision(@PathVariable UUID key, @PathVariable int revisionNumber) {
        return proposals.revision(key, revisionNumber);
    }

    @GetMapping("/{key}/audit")
    public List<AuditEventResponse> audit(@PathVariable UUID key) {
        return proposals.auditTrail(key);
    }

    private static String issuerOf(JwtAuthenticationToken authentication) {
        return authentication.getToken().getIssuer().toString();
    }

    private static String subjectOf(JwtAuthenticationToken authentication) {
        return authentication.getToken().getSubject();
    }

    private static boolean isAdmin(JwtAuthenticationToken authentication) {
        return authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_TWIN_ADMIN"));
    }
}
