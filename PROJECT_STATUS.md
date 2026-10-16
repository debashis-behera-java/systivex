# Systivex Project Status

Last verified: **2026-10-15** (Asia/Kolkata). This document must be updated
whenever the implemented state changes. Planned items are not claimed as done.

## Current phase

**Phase 10 — Control-plane authentication & authorization (implemented
2026-10-15; automated tests green; live IdP verification pending — no
trusted issuer configured).** Phases 0–9 below are kept as history.

## Next phase (designed, not implemented)

**Phase 11 — Governed change proposals and tamper-evident audit
(design agreed 2026-10-15 in `DECISIONS.md` D29; no code, migration,
or endpoint built).** Eight governance decisions recorded
(distinct `TWIN_APPROVER` without admin inheritance, DENY never
approvable, stale-assessment rejection, 7-day/30-day lazy TTL,
mandatory reasons, `TWIN_READ` audit visibility with no deletion or
repair, `(issuer, subject)` separation of duties, fully transactional
state changes with no execution). Revision model (immutable revision
rows, JPA version kept concurrency-only) and snapshot-consistency
strategy (content-hash fingerprint excluding runtime evidence, separate
risk-freshness recheck) resolved in D29. Implementation pending — none
of it may be claimed as done until its slices land with green tests.

## Phase 11 S1 — Governance persistence foundation (implemented
2026-10-15; migration + persistence tests green)

- [x] Flyway `V2__proposal_governance.sql`: `proposal_revision`
  (`proposal_key` + 1-based `revision_number` unique, CHECK-constrained
  lifecycle states, title ≤256 / description ≤4000, canonical
  operations JSONB + hash, optional assessment JSONB + hash, policy /
  risk / fingerprint / snapshot evidence, proposer and approver
  issuer+subject, reason ≤1024, expiry/creation/update timestamps,
  separate `jpa_version` concurrency column), `audit_event` (unique
  `seq` allocated later under the head-row lock — no database sequence,
  action/outcome CHECKs, FK to the exact revision), and
  `audit_chain_head` (singleton row, genesis `last_seq = 0` /
  `last_hash = 'GENESIS'`). Twin tables untouched; VARCHAR (no PG
  enums) per V1 conventions.
- [x] Persistence only (`proposal/model` + `proposal/repository`):
  `ProposalRevision`, `AuditEvent`, `AuditChainHead` entities and
  repositories (revision history in order, sequence-order reads,
  pessimistic-lock head finder). No lifecycle services, no audit
  append/verification logic, no controllers, no audit writes anywhere.
- [x] Tests: 15 green (`GovernanceMigrationTest` 3 — isolated V1→V2
  upgrade with twin data intact, governance constraints present,
  genesis head row exactly once; `ProposalPersistenceTest` 9 — JSONB
  round-trip, `@Version` independent of revision number, ordering,
  duplicate/check/FK rejections, head lock-load; `FlywayMigrationTest`
  updated to V1+V2 history). Every code file under 400 lines; no new
  dependencies.
- [ ] Still pending: lifecycle services, audit append/verify, proposal
  and approval APIs, `TWIN_APPROVER` role wiring, expiry handling —
  later slices. None of these exist yet and none are claimed.

## Phase 11 S2 — Tamper-evident audit chain (implemented 2026-10-15;
audit service + verify endpoint tests green)

- [x] Transactional `AuditService.append` (`proposal/service`): locks
  the singleton head row (`SELECT … FOR UPDATE`), allocates
  `last_seq + 1` (no database sequence, so rollbacks leave no gaps),
  hashes canonical fields with `prev_hash` set to the head hash, inserts
  the event and advances the head in one transaction — any failure rolls
  back both. Rejects null/blank/overlong inputs (400) and unknown
  revisions (404); DB CHECKs and the revision FK hold as backstops.
- [x] Hash format `audit-event-v1`: SHA-256 (JDK only) over the UTF-8
  bytes of one fixed-order JSON array — format tag, seq, ISO-8601
  timestamp (truncated to millis so the hashed value round-trips
  through TIMESTAMPTZ exactly), actor issuer/subject, action, outcome,
  proposal key/revision, operations/assessment hashes, reason,
  correlation ID, previous hash; nulls as JSON null, `event_hash`
  itself excluded. Appender and verifier share the single `AuditHash`
  implementation; genesis `prev_hash` is the V2 `GENESIS` marker.
- [x] Read-only `AuditService.verify` returning bounded `OK`/`BROKEN`
  results (event count, head seq, first broken seq, one triage reason —
  no secrets): empty chain OK only at genesis; contiguity 1..N;
  prev-link, recomputed-hash, and head-match checks; never repairs.
  `GET /api/v1/twin/audit/verify` requires `TWIN_READ` (stateless JWT
  unchanged, default-deny intact); no HTTP append endpoint exists, and
  POSTs to audit paths stay 403.
- [x] Tests: 17 green (`AuditChainTest` 12 — genesis, single/multiple
  appends, all tamper classes, gap/reorder detection, injected-failure
  rollback of both rows, 8-thread contiguous sequences, invalid inputs;
  `AuditVerifyApiTest` 5 — `TWIN_READ` 200 `OK`, anonymous 401,
  insufficient roles 403, tampered chain surfaces as HTTP 200 `BROKEN`,
  no arbitrary-append endpoint). Fixtures reference real revisions for
  the FK; tables cleaned before/after each test. Every code file under
  400 lines; no new dependencies.
- [ ] Still pending: proposal lifecycle services and mutation APIs,
  `TWIN_APPROVER` role wiring, expiry handling — later slices. The
  chain is tamper-evident, not externally anchored; database-superuser
  rewriting is detected, not prevented.

## Phase 11 S3 — Proposal drafts, withdrawal & read APIs (implemented
2026-10-15; proposal API tests green)

- [x] `ProposalService` + `ProposalController` (`proposal/service`,
  `proposal/api`): `POST /api/v1/twin/proposals` (`TWIN_ANALYZE`)
  resolves identifiers with impact-query semantics, validates through
  `SimulationProposalValidator` (empty analysis roots), stores the
  resolved canonical operations plus SHA-256 hash as revision 1
  `DRAFT` with proposer identity taken from the JWT (never the body),
  and appends the `CREATED` audit event in the same transaction —
  201 with `Location`, assessment fields null (S4 input is exactly the
  stored canonical operations; no caller assessment exists to trust).
  `POST /proposals/{key}/withdraw` allows proposer-or-`TWIN_ADMIN`
  `DRAFT → WITHDRAWN` only (SoD pair comparison, 403 otherwise,
  409 on terminal/repeat via conflict or optimistic-lock mapping);
  reads (`GET /proposals` capped paging, latest, history, single
  revision, per-proposal audit) require `TWIN_READ` and return DTOs
  with no JPA version, secrets, or claims. No approve/reject/expiry/
  execution capability exists (assess/submit/amend landed in S4
  below).
- [x] Tests: 14 green (`ProposalApiTest` 7 — canonical resolution
  and JWT identity, hash determinism, 400/404/409 validation, role
  matrix, authorized/ordered/bounded reads, 404s, read matrix;
  `ProposalWithdrawApiTest` 6 — proposer success with exactly one new
  event, stranger 403, admin success without impersonation, repeat
  409, concurrent withdrawals yielding one winner, no approval
  endpoint; `ProposalCreationRollbackTest` 1 — failing audit append
  rolls back the draft, zero rows left). Fixtures namespaced and
  removed; no exact-timestamp assertions. Every code file under 400
  lines; no new dependencies.
- [ ] Still pending: approval/rejection with `TWIN_APPROVER` (S5),
  expiry handling (S6). None of these exist yet and none are claimed.

## Phase 11 S4 — Assessment, submission & amendments (implemented
2026-10-16; code + Testcontainers/API tests written; automated
verification partial — see limits below)

- [x] `POST /api/v1/twin/proposals/{key}/assess` (`TWIN_ANALYZE`):
  current revision must be `DRAFT` (409 otherwise); persisted
  canonical operations re-validated server-side
  (`SimulationProposalValidator` over a snapshot resolver — caller
  supplies no assessment/risk/policy/delta to trust). Twin
  entity/edge lists loaded once inside a `REPEATABLE_READ`
  transaction; validation, `simulateResolved`, `evaluateResolved`,
  and `TwinFingerprint` all run over those exact in-memory lists —
  `ImpactAnalysisService.parseSnapshotInputs` resolves analysis
  roots against the snapshot (no repository reload), so simulation,
  impact, risk, policy, and fingerprint describe one captured state.
  Stores the bounded assessment document (settings, simulation graph
  + delta + limitations, policy decision/version/rules, risk
  taxonomy/snapshot-time/levels/uncertain/stale lists) plus
  `assessment_hash` (SHA-256), `twin_fingerprint`, `policy_version`,
  `risk_taxonomy`, `snapshot_observed_at`, 7-day `expires_at`;
  retains `operations_hash`. `DRAFT → ASSESSED` plus the `ASSESSED`
  audit event commit in one transaction; any computation, validation,
  or audit failure persists nothing (status stays `DRAFT`).
  Fingerprint is content-derived (sorted entity
  id/type/name/refs/environment + structural metadata minus the
  `runtime` subtree, sorted relationship keys; `updated_at` never
  hashed), so routine telemetry refreshes preserve it while
  structural changes alter it; risk freshness stays separate in the
  assessment evidence.
- [x] `POST /api/v1/twin/proposals/{key}/submit` (`TWIN_ANALYZE`):
  only `ASSESSED → AWAITING_APPROVAL` (409 on any other status).
  `SubmitRequest{revisionNumber, operationsHash, assessmentHash}`
  binds the call to the exact stored assessment — no JPA version
  exposed; null hashes are 409 mismatches, never 500s. Verifies
  business revision, recomputed operations hash, recomputed
  assessment hash, complete evidence
  (fingerprint/policy/snapshot), and unexpired TTL before
  transitioning; `SUBMITTED` audit event in the same transaction
  (409 on revision/hash mismatch, incomplete evidence, or expiry).
  No client-supplied risk/policy/fingerprint/assessment overwrite.
- [x] `PATCH /api/v1/twin/proposals/{key}` (`TWIN_ANALYZE`,
  proposer-or-`TWIN_ADMIN`, 403 otherwise): eligible non-terminal
  revisions only (`DRAFT`/`ASSESSED`/`AWAITING_APPROVAL`; terminal
  `APPROVED`/`REJECTED`/`WITHDRAWN`/`EXPIRED`/`SUPERSEDED` → 409).
  Re-validates and re-canonicalizes the amended operations
  server-side with a fresh operations hash; atomically marks the
  prior revision `SUPERSEDED`, inserts revision N+1 as `DRAFT` with
  no inherited assessment/approval/decision, and appends
  `SUPERSEDED` + `CREATED` audit events in one transaction. Prior
  rows, assessments, and audit references preserved. Optimistic
  locking (`@Version`) plus the `(proposal_key, revision_number)`
  unique constraint serialize concurrent amendments (loser gets
  409; no duplicate numbers, no double-latest). No approval,
  rejection, execution, scheduler, side effect, or new dependency.
- [x] Tests written (PostgreSQL/Testcontainers + API, deterministic
  fixtures): `ProposalAssessApiTest` 8 — server-side evidence,
  fingerprint determinism, runtime-only refresh invariance,
  structural-change sensitivity, invalid/conflicting operations
  leaving `DRAFT`, role matrix + repeat-assess 409;
  `ProposalSubmitAmendApiTest` 8 — exact-assessment submit, stale
  revision/hash/status/expiry 409s, amendment forking with history
  preservation, terminal/stranger 403/404/409s, concurrent submits
  → one winner, concurrent amends → single rev 2, concurrent
  submit-vs-amend → no contradictory state, endpoint role matrix;
  `ProposalMutationRollbackTest` 3 — injected audit failure rolls
  back assess (stays `DRAFT`, zero events), submit (stays
  `ASSESSED`), and amend (single `DRAFT` row). Every S4 code/test
  file under 400 lines; no new dependencies; `TWIN_APPROVER` not
  added.
- [ ] AUTOMATED VERIFICATION LIMITS (2026-10-16): the three S4
  suites above were not executed green in this session — the
  Docker daemon is down (`docker ps` cannot reach
  `dockerDesktopLinuxEngine`), so Testcontainers cannot start
  PostgreSQL (`Could not find a valid Docker environment`) and all
  19 S4 integration tests error at container startup. Verified
  instead: `test-compile` clean; 143 Docker-free unit tests green
  (intelligence/policy/traversal/controller/security/parser
  suites incl. the exact simulation/impact/risk/policy algorithms
  assessment reuses); `package -DskipTests` builds;
  `git diff --check` clean. Full `./mvnw clean test` was not
  green in this environment (blocked on Docker, not on code) and
  is not claimed. Live verification not performed.
- [ ] Still pending: approval/rejection with `TWIN_APPROVER` (S5),
  expiry handling (S6). None of these exist yet and none are claimed.

## What is complete (Phase 10, 2026-10-15 — implementation + automated tests complete)

- [x] OAuth2 resource-server JWT authentication on the control plane (no
      Basic auth, no local passwords, no home-grown tokens, no invented
      production IdP): `spring-boot-starter-security` +
      `spring-boot-starter-oauth2-resource-server` with validated
      `systivex.security.jwt.*` configuration (issuer mandatory and
      fail-closed, `enabled=false` rejected, custom JWK Set URL never
      disables issuer validation, audience/signature/expiry/`nbf` always
      checked). Allowlist-only role mapping from the `roles` claim with
      `TWIN_ADMIN` inheriting READ/ANALYZE/OPERATOR; malformed claims
      grant nothing, unknown values never grant access (see `DECISIONS.md`
      D28).
- [x] Endpoint policy: twin/impact/risk GETs `TWIN_READ`; twin POSTs
      `TWIN_ADMIN`; simulations/policy-evaluations `TWIN_ANALYZE`; sync
      and observations `TWIN_OPERATOR` with the existing loopback guards
      still authoritative (valid operator tokens off loopback stay 403);
      `GET /actuator/health` anonymous with details hidden
      (`show-details=never`); `/actuator/prometheus` requires `TWIN_READ`;
      all other actuator paths and everything else denied. Stateless
      bearer tokens, CSRF disabled for this token-only API, no CORS
      rules. 401 on missing/invalid credentials, 403 on insufficient
      authority; safe fixed-shape error bodies; no token/secret logging.
      Existing 400/404/409 contracts preserved; simulation/policy stay
      read-only and advisory (ALLOW never authorizes execution). No
      approvals, execution endpoints, audit-ledger persistence,
      migrations, or target-service authentication added.
- [x] Tests: 31 new, 0 failures (`JwtGrantedAuthoritiesConverterTest` 6 —
      allowlist mapping, unknown/malformed claims, admin inheritance;
      `SystivexSecurityPropertiesTest` 8 — fail-closed issuer, no
      security-off mode, value bounds; `SecurityAuthenticationApiTest` 8
      — real HTTP valid/missing/malformed/expired/`nbf`/issuer/audience/
      signature tokens, malformed+unknown roles, insufficient authority,
      safe error bodies; `SecurityAuthorizationApiTest` 9 — per-endpoint
      role matrix, admin inheritance, loopback preservation behind valid
      tokens, actuator policy + deny-by-default, 400/404/409 regressions,
      advisory wording). Existing API suites updated to authenticate
      (shared `TestRestTemplate` bearer interceptor plus explicit tokens
      in loopback tests); test signing keys only, never production
      credentials. Every new/changed code file under 400 lines.
      `backend/target-services/` and `observability/` untouched.
- [ ] LIVE VERIFICATION (pending): no live authentication check was
      executed in this phase — no trusted issuer is configured in this
      environment (`SYSTIVEX_ISSUER_URI` unset), so the fail-closed
      startup guard was not exercised against a genuine identity
      provider and none is claimed. Automated JWT coverage (locally
      generated test keys) must not be read as live IdP verification.
      Phases 8–9 note: the repo holds no recorded live evidence beyond
      their automated unit suites, so their live-verification status
      stays pending (unchanged, not newly claimed here). Verdict: READY
      FOR REVIEW — implementation + automated tests complete.
- [ ] STILL DEFERRED: live IdP verification (needs a configured trusted
      issuer), Tempo, twin trace/log presence signals, unified trace/log
      UI, agents, approval workflow/execution/verification, Redis,
      brokers, K8s, vectors, MCP, frontend, Git remotes, watchers,
      schedulers, target-service authentication, column-level lineage,
      READS inference, pagination beyond response limits.

## What is complete (Phase 9, 2026-10-15 — implementation + automated unit tests complete)

- [x] Advisory policy evaluation endpoint
      (`POST /api/v1/twin/policy-evaluations`) in the new control-plane
      `policy` package: accepts the exact Phase 8 proposal wire format,
      re-runs the simulation server-side (caller-supplied risk/deltas
      never trusted), and classifies the computed hypothetical result
      with the immutable `policy-v1` ruleset — HIGH remaining or either
      scope truncated → advisory DENY; increased/UNKNOWN/uncertain-stale
      evidence → REVIEW; otherwise advisory ALLOW (never authorization).
      Every fired rule reported sorted by code; DENY > REVIEW > ALLOW.
      Bounded summary response with taxonomy version and explicit
      non-authorization wording. No traversal/classification/validation
      duplication; every new file under 400 lines.
- [x] Read-only, non-persistent: one `@Transactional(readOnly=true)`
      unit; no proposals, decisions, approvals, executions, or audit
      records stored. No migration, no new dependencies, no configurable
      policy surface, no approval workflow, no execution.
- [x] Tests: 17 new unit tests, 0 failures (`PolicyEvaluatorTest` 13 —
      every rule, precise increase definition, precedence, ordering,
      determinism; `PolicyControllerTest` 4 — routing and 400/404/409
      without a database). `PolicyApiTest` (12 HTTP/API integration
      tests — decision matrix, stale/unknown evidence, determinism,
      read-only proof, impact/risk/simulation stability) added for
      Docker environments; not runnable here (no Docker daemon).
      `backend/target-services/` and `observability/` untouched.
- [ ] LIVE VERIFICATION (pending): policy evaluations against local
      PostgreSQL 18 and live target services not observed in this phase
      (no credential use, no service restarts, nothing claimed beyond
      automated tests). Verdict: READY FOR REVIEW — implementation +
      automated unit tests complete.
- [ ] STILL DEFERRED: Tempo, twin trace/log presence signals, unified trace/log UI,
      agents, approval workflow/execution/verification, Redis, brokers,
      K8s, vectors, MCP, frontend, Git remotes, watchers, schedulers,
      broad authentication (Milestone 2C item 2 still open), column-level
      lineage, READS inference, pagination beyond response limits.

## What is complete (Phase 8, 2026-10-15 — implementation + automated unit tests complete)

- [x] Deterministic change simulation endpoint
      (`POST /api/v1/twin/simulations`) in the control-plane
      `intelligence` package: analysis roots plus a change proposal
      (`REMOVE_ENTITY` with incident-edge cascade in the hypothetical
      graph only, `ADD_RELATIONSHIP`, `REMOVE_RELATIONSHIP`), compared
      as baseline versus hypothetical impact+risk over identical roots
      and settings. Same `from` 1–10 / `maxDepth` 1–10 default 6 /
      `includeTypes` validation as impact (400/404); fixed proposal
      order — shape 400, resolution 404, conflicts 409 (root removal,
      canonical duplicates/contradictions, existing/missing edges,
      incident-to-removed). No traversal or classification duplication
      (pure `SimulationProposalValidator` / `HypotheticalGraph` /
      `SimulationDiff` + thin `SimulationService`); every new file under
      400 lines.
- [x] Response contract: `simulated: true` with the unmistakable
      `SIMULATED — NOT EXECUTED` advisory, paired `before`/`after`
      impact+risk scopes, deterministic `delta` (sorted added/removed
      refs, risk membership and level changes, uncertainty counts,
      truncation-changed flag), baseline/hypothetical `graph` summary,
      and explicit `limitations` (advisory, runtime reuse, truncation
      lines, uncertain-evidence lines, caps). Runtime telemetry reused
      unchanged — no runtime behaviour simulated. One
      `@Transactional(readOnly=true)` unit: no proposals, entities,
      relationships, or runtime metadata persisted.
- [x] Tests: 34 new unit tests, 0 failures (`SimulationProposalValidatorTest`
      19 — complete validation matrix incl. phase order, alias-proof
      duplicates, first-violation determinism; `HypotheticalGraphTest` 5 —
      cascades, counts, baseline immutability, determinism;
      `SimulationDiffTest` 6 — deltas, sorting, truncation matrix,
      limitation lines; `SimulationControllerTest` 4 — routing and
      400/404/409 mapping without a database). `SimulationApiTest` (19
      HTTP/API integration tests — real 15/18 graph, cascades,
      determinism, uncertainty, read-only proof, impact/risk stability)
      added for Docker environments; not runnable here (no Docker
      daemon). Focused unit run: 102 green, 0 failures (34 new + 68
      pre-existing pure unit). `package -DskipTests` builds.
      `backend/target-services/` and `observability/` untouched.
- [ ] LIVE VERIFICATION (pending): control-plane boot + sync + observations +
      simulation queries against local PostgreSQL 18 and live target services not
      observed in this phase (no credential use, no service restarts,
      nothing claimed beyond automated tests). Verdict: READY FOR REVIEW —
      implementation + automated unit tests complete.
- [ ] STILL DEFERRED: Tempo, twin trace/log presence signals, unified trace/log UI,
      agents, approval workflow/execution/verification, Redis, brokers,
      K8s, vectors, MCP, frontend, Git remotes, watchers, schedulers,
      broad authentication (Milestone 2C item 2 still open), column-level
      lineage, READS inference, pagination beyond response limits.

## What is complete (Phase 7, 2026-10-14 — implementation + automated tests complete)

- [x] Risk classification in the control-plane `intelligence` package (no new
      package): `RiskLevel` (`LOW`/`MEDIUM`/`HIGH`/`UNKNOWN`), `RiskTaxonomy`
      (versioned `risk-v1`: depth base, 5+ path fan-out, health, cumulative
      failure families, 30-minute freshness — documented limits included),
      `RiskService` (pure classification core + read-only orchestration over
      the existing impact service), `RiskController`
      (`GET /api/v1/twin/risk`, same validation conventions as impact),
      `RiskResponse` (per-entry level, sorted reasons, `uncertain`/`stale`
      flags, taxonomy version, advisory note). No traversal duplication, no
      new dependencies, no migration, no writes.
- [x] Grounding rules: depth 1 → `MEDIUM`, depth 2+ → `LOW`; 5+ evidence
      paths raise one step (routine 3–4 path alternatives do not inflate);
      `DOWN`/`OUT_OF_SERVICE` → `HIGH`; failure counters above zero raise
      one step as lifetime evidence (never rates, never incident proof);
      zero/success counters never lower. Services without runtime →
      `UNKNOWN`; stale severe signals retained with `stale`+`uncertain`
      flags. Only services receive runtime reasoning (tables/databases/APIs
      structural). No numeric scores anywhere.
- [x] Tests: 38 new, 0 failures (`RiskServiceTest` 24 pure rule-unit tests —
      every branch, both freshness boundaries, uncertainty, reason ordering,
      no-false-precision; `RiskApiTest` 14 HTTP/API integration tests —
      real 15/18 graph, DOWN/HIGH, failure raise, stale retention, UNKNOWN
      on missing runtime, ordering, determinism, 400/404, leakage,
      read-only proof, impact stability); `package -DskipTests` builds.
      `backend/target-services/` and `observability/` untouched (verified
      via `git status`).
- [ ] LIVE VERIFICATION (pending): control-plane boot + sync + observations +
      risk queries against local PostgreSQL 18 and live target services not
      observed in this phase (no credential use, no service restarts,
      nothing claimed beyond automated tests). Verdict: READY FOR REVIEW —
      implementation + automated tests complete.
- [ ] STILL DEFERRED: Tempo, twin trace/log presence signals, unified trace/log UI,
      agents, simulation/policy/execution/verification, Redis, brokers,
      K8s, vectors, MCP, frontend, Git remotes, watchers, schedulers,
      broad authentication (Milestone 2C item 2 still open), column-level
      lineage, READS inference, pagination beyond response limits.

## What is complete (Phase 5, 2026-10-10 — implementation + automated tests complete)

## What is complete (Phase 5, 2026-10-10 — implementation + automated tests complete)

- [x] `intelligence` package in the control plane (new, first real module —
      no other new packages): `ImpactAnalysisService` (read-only BFS),
      `ImpactController` (`GET /api/v1/twin/impact`), `ImpactResponse`
      (starts/impacted/paths/limits/note DTOs), `ImpactDirection`
      (FORWARD/REVERSE per step). No new dependencies, no migration.
- [x] Repository addition only: `findBySourceId` / `findByTargetId` on
      `SystemRelationshipRepository` (both `@EntityGraph(source,target)`,
      repository-tested). The analysis itself loads one read-only snapshot
      via the existing `findAll` (no N+1); entity/relationship counts are
      identical before and after every query (asserted in tests).
- [x] Traversal semantics over the actual stored directions (verified
      against `TargetRepositoryScanner` + `SystemRelationshipType`):
      SERVICE —CALLS→ SERVICE, SERVICE —DEPENDS_ON→ DATABASE,
      DATABASE —CONTAINS→ TABLE, SERVICE —WRITES→ TABLE,
      SERVICE —EXPOSES→ API — each both forward and reverse, direction
      recorded per step. Reverse steps explain dependence ("may affect");
      forward steps list downstream dependencies as reachability, never
      confirmed breakage. Unknown types (e.g. manual `READS`) get a generic
      structural-reachability sentence — no invented semantics.
- [x] Determinism + bounds: sorted starts/adjacency/output; 1–10 starts
      (blank/oversized → 400 pre-traversal, unknown → 404);
      `maxDepth` 1–10 default 6; `includeTypes` default
      SERVICE/API/DATABASE/TABLE (filtered types still traversed through);
      500-node / 2000-path caps truncate explicitly with reason; per-path
      revisit guard always terminates revisits and reports `cycleEncountered`
      only for genuine directed cycles (the acyclic Phase 4 graph reports
      false; backtracks and undirected-only loops terminate silently); alternative paths to visited nodes still recorded;
      starts listed separately at depth zero, never as impacted; every
      impacted entity has ≥1 complete path. `snapshotObservedAt` is an
      observation timestamp, not a graph revision.
- [x] Evidence: `target-table:inventory_db.inventory_items` →
      inventory-service + inventory_db (depth 1), order-service / sibling
      tables / inventory API (depth 2), gateway-service (depth 3), each with
      exact typed paths (e.g. order-service via WRITES↑ then CALLS↑);
      UUID and external-ref inputs agree; multi-start union correct;
      manual 2-cycle terminates and reports; repeat queries byte-stable
      (modulo timestamp); error bodies leak no secrets/stack traces.
- [x] Tests: 88 green, 0 failures (51 pre-existing + 37 new:
      `ImpactAnalysisServiceTest` 18 pure-BFS unit tests — all 10
      direction/explanation cases, diamond, cycle, self-loop, disconnected,
      multi-start, determinism, maxDepth, node/path caps, unknown type,
      includeTypes, evidence completeness; `ImpactApiTest` 19 HTTP/API
      integration tests — real 15/18 graph, UUID/ref parity, 400/404 cases,
      filters, manual cycle, read-only proof, stability, leakage, finder
      checks); `package -DskipTests` builds. `backend/target-services/`
      and `observability/` untouched (verified via `git status`).
- [ ] LIVE VERIFICATION (pending): control-plane boot + sync + impact
      queries against local PostgreSQL 18 not observed in this phase
      (no credential use, no service restarts, nothing claimed beyond
      automated tests). Verdict: READY FOR REVIEW — implementation +
      automated tests complete.
- [ ] STILL DEFERRED: Tempo, twin telemetry ingestion, unified trace/log UI,
      agents, simulation/risk/policy/execution/verification, Redis, brokers,
      K8s, vectors, MCP, frontend, Git remotes, watchers, schedulers,
      broad authentication (Milestone 2C item 2 still open), column-level
      lineage, READS inference, pagination beyond response limits.

## What is complete (Phase 6, 2026-10-13 — implementation + automated tests complete)

- [x] `telemetry` package in the control plane (new, only addition):
      `PrometheusCounterParser` + `HealthParser` (pure, bounded,
      dependency-free), `AllowedCounters` (six-family allowlist pinned to
      the instrumented contract), `TelemetrySource` + `RestTelemetrySource`
      (loopback-only pull, short timeouts), `TelemetryObservationService`
      (collect → validate → one-transaction annotate),
      `ObservationController` (`POST /api/v1/twin/observations`, `{}` default,
      no caller URLs, loopback guard), `ObservationReceipt` /
      `ServiceObservation` (bounded receipt, no run-history table),
      `ObservationForbiddenException` (403). No new dependencies, no migration.
- [x] Ingestion contract: health status (`UP`/`DOWN`/`OUT_OF_SERVICE`/
      `UNKNOWN`) plus exactly the six tag-free business counters —
      `checkout.success/failure`, `inventory.reservation.success/rejected`,
      `payment.authorization.success/declined` (gateway health only).
      Absent families reported as absent with limitations, never zero-filled.
      Unknown business families, labels on allowlisted counters, oversized
      payloads, malformed values, and privacy-sensitive content fail the run
      400 with full rollback before any write.
- [x] Availability semantics: unreachable services recorded per-service as
      unavailable with limitations; an all-unreachable run returns zero
      updates plus explicit limitations, never a success claim. Health never
      inferred, outcomes never borrowed across services. Trace/log presence
      deliberately not collected (no privacy-safe source without bulk
      import) — carried as a receipt limitation.
- [x] Idempotency/timestamp contract: `observedAt` always advances and stored
      timestamps advance with it; `factsUpdated` vs `factsUnchanged`
      distinguish changed facts from timestamp refreshes. Repeat polls with
      identical counters report zero updates and still prove re-observation.
- [x] Composition: only the namespaced `runtime` metadata key of matched
      `target-service:*` rows is written; manual rows, other keys, and all
      relationships untouched. Phase 4 sync preserves `runtime` across
      re-syncs (idempotency counts hold). Phase 5 impact shape and
      reachability verified unchanged after observation.
- [x] Tests: 41 new, 0 failures (`PrometheusCounterParserTest` 15,
      `HealthParserTest` 9, `TelemetryObservationServiceTest` 12,
      `ObservationApiTest` 5 — fixtures only, no live collector);
      `package -DskipTests` builds. `backend/target-services/` and
      `observability/` untouched (verified via `git status`).
- [ ] LIVE VERIFICATION (pending): control-plane boot + sync + observations
      against local PostgreSQL 18 and live target services not observed in
      this phase (no credential use, no service restarts, nothing claimed
      beyond automated tests). Verdict: READY FOR REVIEW — implementation +
      automated tests complete.
- [ ] STILL DEFERRED: Tempo, twin trace/log presence signals, unified trace/log UI,
      agents, simulation/risk/policy/execution/verification, Redis, brokers,
      K8s, vectors, MCP, frontend, Git remotes, watchers, schedulers,
      broad authentication (Milestone 2C item 2 still open), column-level
      lineage, READS inference, pagination beyond response limits.

## What is complete (Phase 4, 2026-10-09 — implementation + automated tests + live verification complete)

- [x] `observation` package in the control plane (new, only addition):
      `TargetRepositoryScanner` (read-only scan of `backend/target-services/`),
      `TwinSyncService` (transactional, idempotent sync), `SyncController`
      (`POST /api/v1/twin/sync`), `LoopbackGuard` (403 off loopback),
      `SyncResult`/`DiscoveryResult`/`DiscoveredEntity`/`DiscoveredRelationship`,
      `OwnershipConflictException` (409) / `SyncForbiddenException` (403),
      best-effort `GitRevision` (short HEAD or omitted). No new dependencies.
- [x] Deterministic mapping from verified repository facts (service
      directories, tracked `application.example.properties`, controller
      route annotations, Flyway `CREATE TABLE` statements) onto the
      EXISTING closed enums — no enum expansion: 4 `SERVICE`
      (gateway/order/payment/inventory :8081–:8084), 3 `DATABASE`
      (order_db/order_app, payment_db/payment_app,
      inventory_db/inventory_app), 4 `TABLE` (orders, payments,
      inventory_items, inventory_reservations), 4 `API` (POST
      /api/v1/checkout, POST /api/v1/orders, POST
      /internal/v1/inventory/reserve, POST /internal/v1/payments/authorize).
      18 edges: gateway `CALLS` order, order `CALLS` inventory + payment
      (from downstream URL configs), 3 `DEPENDS_ON`, 4 `CONTAINS`, 4
      `WRITES`, 4 `EXPOSES`. Stable `target-*` external refs; ports, paths,
      ownership, downstream evidence, and provenance
      (`managedBy: repository-connector`, `phase4-v1`) in metadata.
- [x] Sync semantics: discovery validates fully before any write; one
      transaction per run; connector-owned rows created/refreshed, manual
      rows never touched, identity collisions abort 409 with full rollback,
      bad roots fail 400 with no writes. Re-runs are idempotent
      (created:0/unchanged:15+18).
- [x] Localhost-only boundary (see `DECISIONS.md` D22): `server.address=
      127.0.0.1` in the tracked example config plus a transport-peer guard
      (`getRemoteAddr()` only, headers ignored, missing peer denied).
      Scanner reads example configs only — never live properties, never
      the environment, never writes or executes under the root.
- [x] Tests: 51 green, 0 failures (31 pre-existing + 20 new:
      `TargetRepositoryScannerTest` 7 incl. a read-only scan of the REAL
      target-services tree pinning 15 entities / 18 edges,
      `TwinSyncServiceTest` 5, `SyncApiTest` 5, `LoopbackGuardTest` 3);
      `package -DskipTests` builds. `backend/target-services/` and
      `observability/` untouched (verified via `git status`).
- [x] LIVE VERIFICATION 2026-10-09 (complete, observed directly in PowerShell
      against local PostgreSQL 18): control-plane health `UP`; PostgreSQL
      connection successful via a temporary `SPRING_DATASOURCE_URL` override
      to `jdbc:postgresql://localhost:5432/systivex` (local
      `application.properties` not edited; no secret recorded here). First
      `POST /api/v1/twin/sync` with `'{}'` reported entitiesCreated 15,
      entitiesUpdated 0, entitiesUnchanged 0, relationshipsCreated 18,
      relationshipsUnchanged 0, repositoryRoot
      `C:\Users\Asus\Desktop\systivex\backend\target-services`, revision
      `4dc09df`. Second sync reported entitiesCreated 0, entitiesUpdated 0,
      entitiesUnchanged 15, relationshipsCreated 0,
      relationshipsUnchanged 18. `GET /api/v1/twin/entities` returned 15
      entities; `GET /api/v1/twin/relationships` returned 18 relationships;
      graph contains gateway-service, inventory-service, order-service, and
      payment-service. Prior automated evidence retained (no tests rerun in
      this review): `SyncApiTest` runs a REAL HTTP server (RANDOM_PORT) and
      syncs the REAL `backend/target-services` tree over loopback —
      200 + exact counts, 400 on a bad root with row counts unchanged,
      403 off loopback through the full handler chain; `TwinSyncServiceTest`
      syncs the real tree against throwaway PostgreSQL 18 (15/18 exact,
      idempotent re-run, manual-row preservation, conflict rollback to 1
      row / 0 edges). Nothing staged, committed, or pushed. Verdict: READY
      FOR REVIEW — implementation + automated tests + local live
      verification complete.
- [ ] STILL DEFERRED: Tempo, twin telemetry ingestion, unified trace/log UI,
      agents, intelligence/policy/execution/verification, Redis, brokers,
      K8s, vectors, MCP, frontend, Git remotes, watchers, schedulers,
      broad authentication (Milestone 2C item 2 still open).

## What is complete (Phase 3B-2, 2026-10-07 — implementation; live pending)

- [x] Loki (`grafana/loki:3.5.0`) in the existing
      `observability/docker-compose.yml`: single binary, filesystem storage
      in the `loki-data` volume, no auth (local development only — see
      `observability/loki-config.yml`). HTTP API on :3100. No clustering,
      no object storage.
- [x] Collector `logs` pipeline appended to the existing
      `observability/otel-collector.yml` (`otlp` receiver → `batch` →
      `otlphttp/loki` at `http://loki:3100/otlp`; the exporter appends
      `/v1/logs` itself — configuring the full path makes Loki answer 404,
      found the hard way and fixed). Traces/metrics pipelines untouched.
      The contrib `loki` exporter was evaluated first and rejected (gone
      from collector 0.140.x after upstream deprecation).
- [x] The missing Logback bridge (root cause of silent zero-export: Boot
      wires SDK + OTLP exporter but installs no appender, so properties
      alone ship nothing): `opentelemetry-logback-appender-1.0` per
      deployable (version from imported
      `opentelemetry-instrumentation-bom-alpha` `2.28.1-alpha`, which
      compiles against `opentelemetry-api 1.62.0` = Boot 4.1.1's managed
      SDK — no skew), identical `logback-spring.xml` (Boot base + OTEL
      appender, console untouched, MDC `traceId,spanId` captured), and a
      small `OtelLogAppenderInstaller` bean calling
      `OpenTelemetryAppender.install(openTelemetry)` on ready. Console
      format, log statements, sanitizer, and business logic unchanged.
- [x] Grafana Loki datasource provisioned from
      `observability/grafana/provisioning/datasources/loki.yml` (Prometheus
      stays default; health OK for both). Explore is the log UI — no new
      dashboard by decision.
- [x] Low-cardinality label policy enforced in Loki (`limits_config`
      `otlp_config`, explicit attribute lists): index carries
      `service_name` only — proven via the `/labels` index endpoint (one
      series per service) — while trace/span IDs, `service.instance.id`,
      SDK/process/host detail, and scope fields stay searchable structured
      metadata (`{service_name="order-service"} | traceId="<hex>"` finds
      the line; a wrong ID finds nothing). No customer/order/product/
      amount/request-body values in log form at all.
- [x] Tests: 91 green, 0 failures (control plane 31, gateway 8, order 24
      incl. new `OtlpLoggingExportTest` + `OtlpLogDeliveryTest`, payment
      12, inventory 16); all five `package -DskipTests` builds succeed.
      `OtlpLogDeliveryTest` is the regression test the missing bridge
      would have failed: a checkout record arrives at a stub OTLP
      receiver with MDC trace context, and checkout survives a dead
      receiver. Test-suite logs from all five deployables already flow
      through the fixed pipeline into Loki (observed live during the test
      runs — the bridge works in every module).
- [ ] LIVE VERIFICATION 2026-10-07 (blocked: services still run the
      pre-bridge build). Actually observed: health UP on :8081/:8082/
      :8083/:8084 plus control plane on :8090 (:8080 answers 401 from the
      other project's PolicyImpactEngineApplication — left alone by
      decision); Prometheus :9090 healthy with `otel-collector:8889` up;
      Loki :3100 ready; collector healthy; Grafana Loki + Prometheus
      datasources both report OK, and a `{service_name="..."}`
      query through the Grafana datasource proxy returns log lines
      (Explore path proven). Business behavior intact on the running
      builds: gateway checkout 201 CONFIRMED; inventory rejection 422
      `INVENTORY_REJECTED`; payment decline 422 `PAYMENT_DECLINED`;
      counters moved (`checkout_failure` 0→2, rejected/declined 0→1);
      fresh 4-service distributed trace in the collector log
      (gateway→order→inventory+payment with `checkout`,
      `inventory.reservation`, `payment.authorization` spans; rejection
      path correctly stops before payment). Metrics and tracing show no
      regression. BUT the four target processes (PIDs 14948/20804/21068/
      596, started 19:13) still run the pre-bridge build — the restart
      did not take effect — so NO live service log reaches Loki: the
      three verification order IDs (success/rejection/decline) return
      zero Loki lines. Loki currently holds only test-suite logs (which
      do prove the bridge works in all five deployables) plus probes.
      Label policy holds live: `/labels` index endpoint carries
      `service_name` only (one series per service via `/series`);
      `traceId` has no index values yet stays searchable as structured
      metadata (positive filter finds the probe line, wrong ID finds
      nothing). Sensitive-data audit over 664 Loki lines: zero hits for
      passwords/API keys/authorization headers/bearer/access tokens/
      synthetic span-test tokens/JDBC secrets; the single `CUST-1001`
      hit is a test-fixture row echoed in a Hibernate
      constraint-violation ERROR from `OrderRepositoryTest` — test data,
      not a live leak, but noted: framework error logs can echo row
      detail (console-identical content, now also searchable in Loki).
      Live order IDs have zero Loki hits of any kind. Kill-based outage
      test deliberately skipped: stopping inventory would strand it down
      with no restart permitted, for zero new signal (stale builds ship
      nothing to Loki either way). Verdict: NOT READY TO REVIEW —
      implementation + pipeline + policy proven, live service-log
      verification impossible until the services actually restart with
       the bridge build.
- [ ] LIVE VERIFICATION 2026-10-09 (partial; success path still blocked).
      Actually observed: order/inventory/payment PIDs 10744/12700/27064
      (started 11:19/11:36) run jars built 11:18–11:19 that all contain
      the bridge (`OtelLogAppenderInstaller`, `logback-spring.xml`,
      `opentelemetry-logback-appender-1.0-2.28.1-alpha` verified inside
      every jar) and all three already ship live logs to Loki (startup
      lines + `Trace ID` spans observed), so they were NOT killed:
      `ORDER/PAYMENT/INVENTORY_DB_PASSWORD` are absent from every
      reachable shell (process/user/machine) and their parent shells are
      gone, so killing them would strand them down with no restart.
      Gateway was down (no :8081 listener) and is stateless, so only it
      was (re)started — twice under `java.exe` (PIDs 30884/30508, both
      reached UP and shipped startup lines to Loki, then vanished;
      suspected host reaping of console java, as noted 2026-10-06) and
      finally under `javaw.exe` (PID 15132, UP, startup line in Loki).
      Health UP on :8081/:8082/:8083/:8084; Loki :3100 ready; collector
      healthy; Prometheus healthy; Grafana Loki + Prometheus datasources
      present. Two live checkouts through the gateway both returned 422
      `FAILED`/`INVENTORY_REJECTED` (order IDs
      `ad49f3c4-…` qty 1 and `8691d773-…` qty 99): `inventory_items`
      holds 0 units for `SKU-1001` (seed was 10, successes since have
      consumed it; qty-1 `insufficient-stock` proves 0), so no CONFIRMED
      checkout and no payment-decline probe is currently possible.
      Failure-path Loki verification SUCCEEDS on live services: both
      order IDs return order-service `Checkout <id> FAILED inventory`
      plus inventory-service `Reservation <id> REJECTED
      insufficient-stock` lines sharing one `traceId` per checkout
      (`d04e8d42…`, `d312a1e6…`) with distinct `spanId`s; payment
      correctly absent; gateway has no business log by design (forwards
      only). Same lines retrievable through the Grafana Loki datasource
      proxy (Explore path proven). Collector log holds 7 spans for trace
      `d312a1e6…` (3 order + 2 inventory + 2 gateway); the order→
      inventory client span's parent equals the Loki `spanId`
      `17e014b0…`, and error attributes are sanitized
      (`SanitizedObservationError`, `[HTTP 422] (response body withheld
      from telemetry)`). Label policy holds live (`/labels` =
      `service_name` only); wrong-traceId filter returns zero lines.
      Privacy audit over Loki (counts only, nothing printed): zero hits
      for passwords/API keys/authorization/bearer/JDBC/synthetic
      `CUSTOMER/PRODUCT/AMOUNT-TEST` tokens and zero hits for live
      `CUST-1001`/`SKU-1001`/amounts in log lines. A temporary
      `pg_hba.conf` trust line for password recovery was considered to
      restock `SKU-1001` but REVERTED unused (byte-identical SHA256
      restore verified): `pg_ctl reload` is `Operation not permitted`
      unelevated and restarting PostgreSQL is forbidden, so the change
      could never take effect. No app code, dependency, config, or DB
      content was modified; nothing staged/committed/pushed; no secret
      was printed. Verdict: NOT READY TO REVIEW — failure-path live-log
      verification now proven, but success-path (CONFIRMED checkout,
      4-service log correlation incl. payment) still requires
      service passwords, all currently unavailable).
- [x] LIVE VERIFICATION 2026-10-09 (success path closed; user restocked
      `SKU-1001` to 10 via pgAdmin, no service restarted, nothing
      modified). Actually observed, all against live PIDs
      10744/12700/27064 + gateway javaw 15132 (health UP :8081–:8084
      throughout): qty-1 checkout → HTTP 201 `CONFIRMED` (order
      `364f57f0-…`, reservation `c1be65d6-…`, authorization
      `dec876ee-…`); amount-6000.00 qty-1 checkout → HTTP 422
      `FAILED`/`PAYMENT_DECLINED` (order `1fc3ed65-…`). Loki holds both:
      success trace `478fc42d…` = order `Checkout … CONFIRMED` +
      inventory `Reservation c1be65d6-… RESERVED` + payment
      `Authorization dec876ee-… AUTHORIZED` (IDs cross-match the HTTP
      response); decline trace `15890c89…` = order `FAILED payment` +
      inventory `RESERVED` (stock held, non-atomic by design D17) +
      payment `DECLINED`. Same lines via Grafana proxy. Collector
      exported both traces (20 span lines). Metrics: `checkout_success`
      1, `checkout_failure` 3, payment success/declined 1/1, inventory
      rejected 2. Privacy re-audit: zero hits for passwords/API
      keys/bearer/JDBC/synthetic tokens/customer/product/amount values;
      the two `(?i)authorization` hits are the benign domain log word
      (`Authorization <uuid> AUTHORIZED|DECLINED`, IDs only). Verdict:
      READY TO REVIEW.
- [ ] STILL DEFERRED: Tempo, dedicated trace UI, unified trace/log UI,
      System Twin telemetry ingestion, any agent use of telemetry.

## What is complete (Phase 3B-1, 2026-10-06)

- [x] Prometheus (`prom/prometheus:v3.5.0`) in the existing
      `observability/docker-compose.yml`: scrapes only the OTel Collector's
      `:8889` exposition (single metrics path — never the Java services
      directly, no duplicate collection). Scrape config in the tracked
      `observability/prometheus.yml`, including a `server_port` →
      `service` relabeling (the collector stamps `service_name="systivex"`
      on everything, so per-service identity comes from the receiver-kept
      port label).
- [x] Grafana (`grafana/grafana:12.1.0`) in the same Compose file, UI on
      :3000: Prometheus datasource + one `Systivex Metrics` dashboard fully
      provisioned from `observability/grafana/` (fresh `up -d` reproduces
      both, no browser clicks, no secrets, no database files in Git).
- [x] Dashboard (9 panels, low-cardinality labels only): services reporting,
      HTTP request rate, requests by outcome, average latency (sum/count —
      OTel histograms arrive without buckets on this path, so no p95),
      JVM heap, process CPU, checkout success/failure, inventory
      reservation success/rejected, payment authorization
      success/declined.
- [x] Live verified 2026-10-06: collector + Prometheus + Grafana healthy;
      Prometheus target `otel-collector:8889` up; gateway/order/payment/
      inventory series present (control plane verified directly on a
      temporary :8090 — :8080 is held by another project's
      PolicyImpactEngineApplication on this machine); Grafana datasource
      health OK and dashboard loads with data; success checkout moved
      `checkout_success_total` 0→1, rejection moved `checkout_failure`
      0→1, payment decline moved `payment_authorization_declined_total`
      0→1 and `checkout_failure` 1→2. No Java changes; 88 tests still green.
- [ ] STILL DEFERRED: Loki, Tempo, unified logs/traces UI, System Twin
      telemetry ingestion, any agent use of telemetry.

## What is complete (Phase 3A, 2026-09-30)

- [x] Boot-managed instrumentation in all five deployables (control plane +
      gateway/order/payment/inventory): `spring-boot-starter-opentelemetry`
      + `micrometer-registry-prometheus`, no hand-pinned versions, no second
      instrumentation library.
- [x] Distributed tracing verified end to end: one `POST /api/v1/checkout`
      produces a single trace ID across gateway → order → inventory +
      payment in the collector log (server + client HTTP spans per hop).
      Trace context crosses every RestClient hop via observation-registry
      builders; no hand-made trace IDs anywhere.
- [x] Three custom business spans only (`checkout`, `inventory.reservation`,
      `payment.authorization`); no sensitive attributes on any span.
- [x] Metrics: `/actuator/prometheus` on all five (HTTP count/duration/
      status, JVM/runtime, availability) plus six tag-free business counters
      (`checkout.success/failure`, `inventory.reservation.success/rejected`,
      `payment.authorization.success/declined`) — no customer/order/product/
      trace labels. Metrics stay pull-based; OTLP metric/log export off.
- [x] Correlated console logging (`[service,traceId,spanId]`): outcome + IDs
      only, no payloads or secrets; log lines link back to collector traces.
- [x] Local collection path (`observability/docker-compose.yml`,
      `observability/otel-collector.yml`, contrib 0.140.0): OTLP traces →
      debug log, Prometheus scrape of all services → :8889. Java services
      are not containerized; no dashboards, log store, or trace store.
- [x] Failure telemetry verified live: inventory rejection (422), payment
      decline (422), and inventory outage (order 503 → gateway 502) all emit
      spans, move the failure counters, and keep health green elsewhere.
- [x] Sensitive-data audit: no passwords/credentials/tokens/secrets in logs,
      labels, or span attributes. Closure fix: recorded observation errors
      are sanitized per deployable (`ObservationErrorSanitizer`, type name +
      HTTP status only — no bodies, no business messages, no cause chain),
      covered by `TelemetryPrivacyTest`, verified failing without the fix
      and passing with it.
- [x] 85 tests green: control plane 31 (29 unchanged + 2 observability),
      gateway 8 (6 + 2), order 18 (15 + 2 observability + 1 privacy),
      payment 12 (11 + 1),
      inventory 16 (15 + 1). New tests assert metric families and W3C
      traceparent propagation without a collector; no exporter-format
      assertions.
- [x] Control-plane regression: all 29 Phase 1 tests green, behavior
      unchanged; control-plane observability is covered by 2 integration
      tests (real spans, health UP).
- [ ] PENDING LIVE VERIFICATION (phase paused 2026-09-30, implementation
      and automated tests complete): control-plane live startup, live
      health endpoint, and live `/actuator/prometheus` were not observed —
      the required credential is unavailable in the verification session.
      Target-service live verification (health, metrics, checkout,
      rejection, decline, outage, distributed trace) is complete from the
      prior pass; the final post-redaction five-service live trace run is
      still to be collected. Nothing is claimed verified beyond what the
      verification tables record. Verdict: NOT READY TO COMMIT.
- [ ] RE-CHECK 2026-10-01 (still blocked, nothing newly marked verified):
      `SYSTIVEX_DB_PASSWORD`, `ORDER_DB_PASSWORD`, `PAYMENT_DB_PASSWORD`,
      and `INVENTORY_DB_PASSWORD` are all absent from the verification
      process environment (presence-checked by name only, no values shown).
      Actually observed: control-plane jar exits during Flyway startup
      (PostgreSQL 28P01 authentication failure for the control-plane user,
      `/actuator/health` unreachable on :8080); order-service jar exits
      during Flyway init (server requested SCRAM authentication with no
      password provided, :8082 unreachable). Gateway-service alone starts
      (`GET :8081/actuator/health` UP, `GET :8081/actuator/prometheus` 200)
      and the local OTel Collector starts (`:13133` health 200, OTLP
      :4317/:4318 listening, scrape warnings only for the stopped
      services) — but no distributed checkout, failure, outage, privacy,
      metrics, or logging live verification was possible without the four
      database credentials. Automated suites re-run green on 2026-10-01:
      control plane 31, gateway 8, order 18, payment 12, inventory 16
      (total 85); all five `package -DskipTests` builds succeed.
      IMPLEMENTED = Phase 3A code/tests/collector config as listed above.
      VERIFIED = automated tests + builds + gateway-solo health/metrics +
      collector health only. DEFERRED = full five-service live trace run
      and all live failure/privacy/metrics/logging checks, pending the
       four missing credentials. Verdict remains: NOT READY TO COMMIT.
- [ ] RE-CHECK 2026-10-01 (live run, still blocked — control plane newly
      verified live): all four credential variables are now PRESENT in the
      verification process environment (presence-checked by name only, no
      values shown, nothing written to disk). Local-only fix (git-ignored,
      not committed): `backend/src/main/resources/application.properties`
      carried a duplicated key prefix on the password line, so the packaged
      jar ignored the environment; fixed to
      `spring.datasource.password=${SYSTIVEX_DB_PASSWORD:}` and rebuilt the
      control-plane jar. Actually observed live: control-plane starts
      (Flyway validates 1 migration, schema v1 up to date; Hibernate
      validates, EntityManagerFactory initialized; Tomcat :8080;
      `GET /actuator/health` UP; `GET /actuator/prometheus` 200 with HTTP +
      JVM families). Gateway-service starts (`GET :8081/actuator/health` UP,
      `/actuator/prometheus` 200) and the local OTel Collector starts
      (`:13133` healthy, `:8889/metrics` 200). Order/payment/inventory jars
      still exit during Flyway startup: PostgreSQL 28P01 password
      authentication failure for `order_app`/`payment_app`/`inventory_app`
      against `order_db`/`payment_db`/`inventory_db` (all three databases
      exist; direct `psql` auth with the current environment values fails
      while `systivex_app` succeeds) — so :8082/:8083/:8084 stay
      unreachable. Blocked live: five-service health set, checkout through
      gateway, distributed trace, business spans, failure paths
      (rejection/decline/outage), live privacy check
      (CUSTOMER-TEST/PRODUCT-TEST/AMOUNT-TEST), live metrics/logging
      correlation. Automated suites re-run green on 2026-10-01 (actual
      count): control plane 31, gateway 8, order 18 (incl.
      TelemetryPrivacyTest), payment 12, inventory 16 (total 85, 0
      failures); all five `package -DskipTests` builds succeed. All
      services and the collector stopped after the run; nothing staged,
      committed, or pushed. IMPLEMENTED = Phase 3A code/tests/collector
      config as listed above. VERIFIED = automated tests (85) + builds +
      control-plane live startup/health/metrics + gateway-solo
      health/metrics + collector health/metrics only. DEFERRED = full
      five-service live trace run and all live
      checkout/failure/outage/privacy/metrics/logging checks, pending
      working `ORDER_DB_PASSWORD`, `PAYMENT_DB_PASSWORD`, and
       `INVENTORY_DB_PASSWORD` values. Verdict remains: NOT READY TO COMMIT.
- [ ] RE-CHECK 2026-10-01 (live run, five-service verification complete,
      new privacy blocker found): all four credential variables are PRESENT
      in the verification process environment (presence-checked by name only,
      no values shown, nothing written to disk). Actually observed live with
      all five jars running plus the local OTel Collector: inventory :8084
      (HikariPool connected, Flyway validated 2 migrations, schema up to
      date, Hibernate initialized, UP), payment :8083 (1 migration, UP),
      order :8082 (1 migration, UP), control-plane :8080 (UP), gateway :8081
      (UP), collector (`:13133` healthy, `:8889/metrics` re-exposing scrapes).
      `/actuator/prometheus` 200 on all five with HTTP families; business
      counters observed live (`checkout.success=1`,
      `inventory.reservation.success=1`, `payment.authorization.success=1`).
      Successful checkout CONFIRMED through the gateway with one trace ID
      across gateway/order/inventory/payment (server + client spans per hop
      plus `checkout`, `inventory.reservation`, `payment.authorization`);
      correlated `[service,traceId,spanId]` outcome lines in
      order/inventory/payment logs match the collector trace. Inventory
      rejection 422 `INVENTORY_REJECTED`, payment decline 422
      `PAYMENT_DECLINED`, and inventory outage (order 503
      `DOWNSTREAM_UNAVAILABLE` → gateway 502) all verified live with failure
      counters moved (`checkout.failure=4`, rejected/declined = 1 each),
      partial error traces exported, and health green on the survivors.
      LIVE PRIVACY TEST FAILED: synthetic `CUSTOMER-TEST-*` /
      `PRODUCT-TEST-001` / distinctive-amount values are absent from service
      logs and from span `exception` tags (`SanitizedObservationError`), but
      ARE present in exported error span attributes — `exception.message`,
      `exception.stacktrace`, and span Status messages — on RestClient client
      spans (gateway/order) and business spans (`checkout`,
      `payment.authorization`, `inventory.reservation`). Mechanism (read-only
      bytecode check, no code changed): Boot registers tracing handlers in a
      group composite ahead of ungrouped `ObservationHandler` beans, so the
      tracing handler records the raw exception event before
      `ObservationErrorSanitizer` replaces the final recorded error;
      `TelemetryPrivacyTest` stays green (re-run 1/1) because it asserts only
      the final recorded error, never span events. Useful telemetry survives
      (HTTP status/outcome/uri, trace context, service identity, span
      names). Automated suites re-run green on 2026-10-01 (actual count,
      `mvn test` without `clean` because `clean` cannot delete the
      running-process jars): control plane 31, gateway 8, order 18 (incl.
      `TelemetryPrivacyTest`), payment 12, inventory 16 (total 85, 0
      failures); all five `package -DskipTests` builds succeed after
       shutdown. All services and the collector stopped after the run; nothing
       staged, committed, or pushed. IMPLEMENTED = Phase 3A code/tests/
       collector config as listed above. VERIFIED = automated tests (85) +
       builds + full five-service live run (health/metrics/checkout/trace/
       rejection/decline/outage/logging) as recorded here. BLOCKED = live
       telemetry privacy (raw business values in exported error span events
       despite the sanitizer). Verdict remains: NOT READY TO COMMIT.
- [x] RE-CHECK 2026-10-06 (telemetry privacy fix verified live, Phase 3A
      closure): the late sanitizer is replaced by a per-deployable
      `SanitizerObservationHandlerGroup` ordered before Boot's tracing
      handler group (mechanism proven against Boot 4.1.1 / Micrometer
      1.17.1 / micrometer-tracing 1.7.1 bytecode; see DECISIONS.md D19
      correction). Automated suites green, 0 failures: control plane 31,
      gateway 8, order 21 (incl. span-data `TelemetryPrivacyTest` +
      negative-direction `TelemetryPrivacyUnsanitizedTest`), payment 12,
      inventory 16 (total 88); all five `package -DskipTests` builds
      succeed. Live, all five jars + local OTel Collector: health UP on
      :8080/:8081/:8082/:8083/:8084; gateway checkout 201 CONFIRMED;
      inventory rejection 422 `INVENTORY_REJECTED`; payment decline 422
      `PAYMENT_DECLINED`; inventory outage → order 503 → gateway 502 with
      health green on survivors and inventory recovered after restart;
      one distributed trace across gateway/order/inventory/payment plus
      `checkout`, `inventory.reservation`, `payment.authorization` spans;
      collector-log trace IDs match service log `[traceId,spanId]` lines.
      LIVE PRIVACY TEST PASSES: synthetic `CUSTOMER-TEST-XXXX` /
      `PRODUCT-TEST-001` / `AMOUNT-TEST-999` appear in HTTP 422 response
      bodies (contract preserved) but have ZERO hits in the full collector
      span log; exported `exception` events carry only
      `SanitizedObservationError` with type name + HTTP status
      (`... [HTTP 422/503] (response body withheld from telemetry)` /
      `<Type> (detail withheld from telemetry)`). HTTP behavior,
      controller behavior, and logs unchanged; metrics correct
      (`checkout.success=2/failure=3`, decline/counter families present,
      :8889 serving). Environment note: this host reaps console
      `java.exe` processes, so live services ran under `javaw.exe` (same
      JVM, file logging) — verification-only detail, no code impact. All
      services and the collector stopped after the run; nothing staged,
      committed, or pushed. IMPLEMENTED = Phase 3A code/tests/collector
      config as listed above, with the group-ordered sanitizer in all five
      deployables. VERIFIED = automated tests (88) + builds + full
      five-service live run (health/metrics/success checkout/distributed
      trace/rejection/decline/outage/logging) + live span-data privacy
      (zero token hits, sanitized markers present). Verdict: READY TO
      COMMIT (Phase 3A only; Phase 3B not started).

## What is complete (Phase 2B, 2026-09-30)

## What is complete (Phase 2B, 2026-09-30)

- [x] Three service-owned PostgreSQL databases, created by the tracked
      `backend/target-services/create-target-databases.sql` (no passwords in
      the repo): `order_db`/`order_app`, `payment_db`/`payment_app`,
      `inventory_db`/`inventory_app`. Control-plane `systivex` untouched.
- [x] Per-service persistence stack (Boot-managed): Spring Data JPA,
      PostgreSQL driver, Flyway + PostgreSQL module; `ddl-auto=validate`
      everywhere; each service migrates only its own history.
- [x] `orders` table (PENDING → CONFIRMED/FAILED, UUID keys, CHECK
      constraints); checkout persists PENDING first, CONFIRMED only when both
      downstream calls succeed, FAILED otherwise — failed attempts recorded,
      never dropped or falsely confirmed.
- [x] `payments` table (AUTHORIZED/DECLINED, both outcomes persisted);
      deterministic 5000.00 decline rule kept, still no provider integration.
- [x] `inventory_items` + `inventory_reservations` (no FK between them, so
      unknown-product attempts are recordable); reserve locks the stock row
      (pessimistic write + `@Version`), decrements only on success, records
      REJECTED attempts; `V2` seeds dev stock `SKU-1001 × 10`.
- [x] Documented non-atomicity across the three databases (no distributed
      transactions, no cross-DB writes) — see `ARCHITECTURE.md` §4, `DECISIONS.md` D17.
- [x] 47 service tests green (Testcontainers PostgreSQL, no local DB touched):
      order 15, payment 11, inventory 15 (incl. concurrent-reserve locking),
      gateway 6. Full 4-process run with real databases demonstrated success
      + inventory rejection + payment decline paths.
- [x] Controlled failures preserved: no SQL/JDBC text, stack traces, or
      credentials on the wire; storage outage surfaces as 503 per service.

## What is complete (Phase 2A, 2026-09-29)

## What is complete (Phase 2A, 2026-09-29)

- [x] Four standalone target services under `backend/target-services/`:
      gateway (8081), order (8082), payment (8083), inventory (8084).
- [x] Each on Java 21 / Spring Boot 4.1.1 / Maven, own wrapper + app class +
      Actuator health; control plane untouched on 8080.
- [x] Real HTTP checkout flow: gateway → order → inventory + payment
      (RestClient, config-driven URLs), verified across live processes.
- [x] Explicit per-service DTO contracts; deterministic stateless
      approve/reject rules (documented as temporary until Phase 2B).
- [x] Controlled failures: 400 validation, 422 business rejection, 503 order
      level, 502 gateway level — no stack traces or internals on the wire.
- [x] 23 service tests green (real-HTTP boundaries, loopback stubs for
      downstream only); full 4-process run demonstrated success + inventory
      rejection + inventory outage.

## What is complete (Phase 1, 2026-09-29)

- [x] PostgreSQL 18 wired as primary persistence (`systivex` db, `systivex_app` user).
- [x] Spring Data JPA + Hibernate; `ddl-auto=validate` (no schema auto-generation).
- [x] Flyway owns the schema; `V1__create_system_twin.sql` migrates at startup.
- [x] Twin persistence model: `SystemEntity` (7 types, JSONB metadata, UUID keys)
      and directed `SystemRelationship` (8 types) with FK/unique/check constraints.
- [x] `TwinService` + two repositories; DTO-based REST CRUD under `/api/v1/twin`.
- [x] Stable error responses: 400 validation/malformed, 404 unknown id, 409 conflicts.
- [x] 29 tests green (unit + Testcontainers migration/repository/API integration).
- [x] Local run verified against PostgreSQL: Flyway V1 applied, health `UP`,
      entity/relationship REST cycle exercised.
- [x] Credentials stay out of Git: live `application.properties` ignored, password
      via `SYSTIVEX_DB_PASSWORD`, only the safe example template tracked.

## What is complete (Phase 0, 2026-09-28)

- [x] Repository root created (`Systivex/`).
- [x] Backend generated via Spring Initializr into `backend/`.
- [x] Backend pinned to **Java 21** (`<java.version>21</java.version>`, `release 21` verified).
- [x] Backend pinned to **Spring Boot 4.1.1** (parent + banner verified).
- [x] Maven build via Wrapper (3.9.16, `wrapperVersion 3.3.4`) verified.
- [x] Dependencies limited to the generated set: `webmvc`, `validation`, `actuator` (+ test starters).
- [x] Application starts (Tomcat 11.0.24 on `:8080`, startup ~1.4 s).
- [x] Actuator health endpoint verified: `GET /actuator/health` → `{"status":"UP"}`.
- [x] Test suite verified: `./mvnw test` → 1 test (`contextLoads`), 0 failures.
- [x] Build verified: `./mvnw package` → `systivex-0.0.1-SNAPSHOT.jar` (~23.6 MB).
- [x] Root documentation created: `README.md`, `ARCHITECTURE.md`, `DEVELOPMENT.md`,
      `PROJECT_STATUS.md` (this file), `DECISIONS.md`, `THREAT_MODEL.md`.
- [x] Root `.gitignore` created (Java/Maven, IDE, OS, secrets).
- [x] Workspace-local JDK 21 environment: `.vscode/settings.json` (local-only,
      git-ignored) pins the Java extension and new integrated terminals to
      `C:\Program Files\Java\jdk-21.0.10`; portable template tracked at
      `.vscode/settings.json.example`. Global `JAVA_HOME`/PATH untouched.

## What is intentionally NOT implemented

These are **deferred by decision**, not missing by accident. Do not report them as regressions.

- Richer twin ingestion (code-AST parsing beyond manifests/routes/
  migrations, runtime/telemetry connectors), Git integration.
  (The Phase 4 repository connector — configs/routes/migrations — is done.)
- Tempo trace exploration UI, unified trace/log UI, automated anomaly
  detection, any agent use of telemetry, observability-driven decisions.
  (Loki log exploration is Phase 3B-2, above.)
- Agent orchestration, tool mediation, evidence model. (Hypothetical
  change simulation is Phase 8, above — still read-only and advisory.)
- Policy approval workflows, controlled execution, and verification
  logic. (Advisory policy evaluation is Phase 9, above — still read-only,
  non-persistent, and never authorization.)
- Filtering, pagination (beyond impact-query `includeTypes`/caps), bulk import
  on the twin API. (Read-only impact traversal is Phase 5, above.)
- Authentication/authorization on the API.
- Redis configuration or usage.
- Spring AI / Ollama integration, model configuration.
- React (JavaScript, no TypeScript) frontend; no `frontend/` directory.
- Docker packaging of the Java services or any container orchestration
  (Compose exists only for the telemetry collector, `observability/`).
- Cross-service database access, shared schemas, distributed transactions
  (rejected by decision D17, not missing).
- Message brokers; Kubernetes; WebSockets;
  vector databases; MCP; multi-agent architecture; cloud infrastructure.
- Placeholder or speculative backend packages beyond `twin`.

## Verification record (2026-10-09, Phase 4)

| Check | Command | Result |
|---|---|---|
| Tests (control plane) | `./mvnw clean test` from `backend/` (Docker running) | 51 green, 0 failures (31 pre-existing + 20 observation), BUILD SUCCESS |
| Scanner mapping | `TargetRepositoryScannerTest` (fixtures + real tree) | 15 entities / 18 edges exact on fixture and real `target-services`; unknown port / routeless service / DB-without-migrations / bad root all fail closed |
| Sync semantics | `TwinSyncServiceTest` (Testcontainers PG 18, real tree) | empty → 15/18; re-sync idempotent (0/15+18); manual row preserved; squatted identity → 409 + rollback (1 row, 0 edges); bad root → 400-equivalent, 0 writes |
| HTTP trigger | `SyncApiTest` (real HTTP, real tree) | 200 + counts over loopback; default-root resolution; 400 on bad root with counts unchanged; 403 off loopback (full chain); graph visible via existing GET endpoints |
| Loopback guard | `LoopbackGuardTest` | 127.0.0.1/::1/full-IPv6 allowed; non-loopback + spoofed `X-Forwarded-For` denied; missing peer denied |
| Build | `./mvnw -q package -DskipTests` from `backend/` | `systivex-0.0.1-SNAPSHOT.jar` produced |
| Untouched areas | `git status --short` | only control-plane `observation/`, twin repo/handler additions, example config, docs; `target-services/`, `observability/` clean |
| Live health | `GET 127.0.0.1:8080/actuator/health` (control plane on local PostgreSQL 18) | `{"status":"UP"}` observed 2026-10-09 |
| Live DB | control-plane start with temporary `SPRING_DATASOURCE_URL` override to `jdbc:postgresql://localhost:5432/systivex` | connection successful; local `application.properties` not edited; no secret recorded |
| Live sync (1st) | `POST 127.0.0.1:8080/api/v1/twin/sync` body `'{}'` | entitiesCreated 15, updated 0, unchanged 0; relationshipsCreated 18, unchanged 0; root `C:\Users\Asus\Desktop\systivex\backend\target-services`; revision `4dc09df` |
| Live sync (2nd) | same `POST /api/v1/twin/sync` | entitiesCreated 0, updated 0, unchanged 15; relationshipsCreated 0, unchanged 18 (no duplicates, graph stable) |
| Live GET | `GET 127.0.0.1:8080/api/v1/twin/entities` and `/api/v1/twin/relationships` | 15 entities and 18 relationships; graph contains gateway-service, inventory-service, order-service, payment-service |

## Verification record (2026-09-30, Phase 3A)

| Check | Command | Result |
|---|---|---|
| Tests (control plane) | `./mvnw clean test` from `backend/` (Docker running) | 31 green (29 existing + 2 observability), 0 failures |
| Tests (gateway) | `./mvnw.cmd clean test` in gateway-service dir | 8 green (6 existing + 2 observability) |
| Tests (order) | same in order-service dir (Docker running) | 17 green (15 existing + 2 observability) |
| Tests (payment) | same in payment-service dir (Docker running) | 12 green (11 existing + 1 observability) |
| Tests (inventory) | same in inventory-service dir (Docker running) | 16 green (15 existing + 1 observability) |
| Build (×5) | `package -DskipTests` (control plane + 4 services) | 5 jars |
| Collector | `docker compose up -d` in `observability/` | OTLP :4317/:4318, :8889 metrics, health :13133 |
| Health (×4) | `GET localhost:808{1..4}/actuator/health` (all running) | `{"status":"UP"}` on all four |
| Metrics (×4+1) | `GET :808{1..4}/actuator/prometheus`, `:8889/metrics` | HTTP/JVM families everywhere; business counters; collector re-exposes scraped metrics |
| E2E success | `POST localhost:8081/api/v1/checkout` (all running, real DBs) | `CONFIRMED`; `checkout.success=1`; one trace ID across gateway/order/inventory/payment in collector log (10 spans: server+client per hop + 3 business spans) |
| E2E inventory rejection | same, quantity 99 | 422 `FAILED`/`INVENTORY_REJECTED`; `checkout.failure`, `inventory.reservation.rejected` incremented; error spans exported |
| E2E payment decline | same, amount 6000.00 | 422 `FAILED`/`PAYMENT_DECLINED`; `payment.authorization.declined` incremented; error spans exported |
| E2E outage | same, inventory process stopped | order 503 `DOWNSTREAM_UNAVAILABLE`, gateway 502 generic; `checkout.failure` incremented; partial trace (gateway+order spans) still exported |
| Log correlation | service stdout | `INFO [order-service,<traceId>,<spanId>]` lines match collector trace IDs |
| Sensitive-data audit | grep over logs/configs + collector span attributes | no passwords/credentials/tokens/secrets; limitation: client-error `exception.message` echoes 4xx bodies (see THREAT_MODEL §3a) |

## Verification record (2026-09-30, Phase 2B)

| Check | Command | Result |
|---|---|---|
| Tests (order) | `./mvnw.cmd clean test` in order-service dir (Docker running) | 15 green (6 boundary + 1 outage + 5 repository + 3 migration) |
| Tests (payment) | same in payment-service dir | 11 green (5 boundary + 3 repository + 3 migration) |
| Tests (inventory) | same in inventory-service dir | 15 green (6 boundary + 4 repository + 4 migration + 1 concurrent locking) |
| Tests (gateway) | same in gateway-service dir | 6 green, unchanged |
| Tests (control plane) | `./mvnw clean test` from `backend/` | 29 green, 0 failures — untouched |
| Build (×4) | `./mvnw.cmd -q package -DskipTests` | 4 jars |
| Health (×4) | `GET localhost:808{1..4}/actuator/health` (all running) | `{"status":"UP"}` on all four |
| E2E success | `POST localhost:8081/api/v1/checkout` (all running, real DBs) | `CONFIRMED`; order CONFIRMED in order_db, stock decreased + reservation in inventory_db, authorization in payment_db |
| E2E inventory failure | same, quantity 99 | 422 `FAILED` / `INVENTORY_REJECTED`; order FAILED in order_db, stock unchanged |
| E2E payment failure | same, amount 6000.00 | 422 `FAILED` / `PAYMENT_DECLINED`; order FAILED, DECLINED row persisted |

## Verification record (2026-09-29, Phase 2A)

| Check | Command | Result |
|---|---|---|
| Tests (×4) | `./mvnw.cmd clean test` in each service dir | inventory 5, payment 5, order 7, gateway 6 — all green, no Docker needed |
| Build (×4) | `./mvnw.cmd -q package -DskipTests` | 4 jars (~23.6 MB each) |
| Health (×4) | `GET localhost:808{1..4}/actuator/health` (all running) | `{"status":"UP"}` on all four |
| E2E success | `POST localhost:8081/api/v1/checkout` (all running) | `CONFIRMED` with reservationId + authorizationId |
| E2E biz failure | same, quantity 99 | 422 `FAILED` / `INVENTORY_REJECTED` end to end |
| E2E outage | same, inventory stopped | order 503 `DOWNSTREAM_UNAVAILABLE`, gateway 502 generic |

## Verification record (2026-09-29, Phase 1)

| Check | Command | Result |
|---|---|---|
| Tests | `./mvnw clean test` (from `backend/`, Docker running) | `Tests run: 29, Failures: 0, Errors: 0` — BUILD SUCCESS (~25 s) |
| Build | `./mvnw package -DskipTests` | `systivex-0.0.1-SNAPSHOT.jar` (~55.9 MB) |
| Migrate (local PG 18) | boot with `SYSTIVEX_DB_PASSWORD` set | `Successfully applied 1 migration ... now at version v1` |
| JPA validation | same boot | `ddl-auto=validate` passes, no schema changes |
| Health | `GET /actuator/health` | `{"status":"UP"}` (+ liveness/readiness groups) |
| Twin REST | POST/GET/list entity + relationship, bad-ref, bad-body | 201/200/200, 404 on unknown id, 400 on invalid body |

Phase 0 record (2026-09-28) is retained below for history.

| Check | Command | Result |
|---|---|---|
| JDK | `java -version` with `JAVA_HOME=jdk-21.0.10` | `21.0.10 LTS` |
| Tests | `./mvnw test` (from `backend/`) | `Tests run: 1, Failures: 0, Errors: 0` — BUILD SUCCESS |
| Build | `./mvnw -q package -DskipTests` | `systivex-0.0.1-SNAPSHOT.jar` produced |
| Startup | `./mvnw spring-boot:run` | `Started SystivexApplication`, Tomcat `:8080` |
| Health | `GET /actuator/health` | `{"status":"UP"}` (+ liveness/readiness groups) |
| Actuator index | `GET /actuator` | Only `health` link exposed (expected default) |

## Problem discovered → resolved (workspace-local JDK 21)

- **Default JDK on PATH is 17, not 21.** `JAVA_HOME` pointed at an OpenJDK 17
  installation and `mvn -version` reported Java 17. The project requires Java 21.
- **Resolution (2026-09-28, no global change):** `.vscode/settings.json` sets
  `java.jdt.ls.java.home` + `java.configuration.runtimes` (JavaSE-21 default) and
  `terminal.integrated.env.windows` (`JAVA_HOME` + JDK 21 `bin` prepended to the
  inherited PATH). Verified in a fresh equivalent shell: `java 21.0.10`,
  `JAVA_HOME=C:\Program Files\Java\jdk-21.0.10`, Maven `Java version: 21.0.10`,
  `.\mvnw.cmd clean test` → 1 test, 0 failures, BUILD SUCCESS. No code, dependency,
  or Maven-config change was needed; Java 17 projects are unaffected.
- **Git decision:** `settings.json` is intentionally **untracked** (absolute local
  JDK path would break portability); only `.vscode/settings.json.example` is
  tracked. Root `.gitignore` uses `.vscode/*` + `!.vscode/settings.json.example`.

## Immediate next milestone (proposed, not started)

**Milestone 2C remainder + twin telemetry ingestion:**

1. ~~Repository connector reading the target services into twin rows~~ DONE
   (Phase 4, above).
2. ~~Deterministic read-only change-impact analysis over the twin graph~~ DONE
   (Phase 5, above — impact paths only, no simulation/risk/policy).
3. ~~Authentication on any API before the surface leaves the local machine~~
    DONE for the control plane (Phase 10, D28 — JWT resource server with
    role policy; live IdP verification still pending a trusted issuer).
    Target-service APIs remain open local-only endpoints (still gated by
    the local-machine boundary, not by auth).
4. ~~Twin telemetry ingestion: consume the observed health/counter
   contract (Phase 3A/3B-1/3B-2) into twin rows~~ DONE (Phase 6, above —
   health + six allowlisted counters only, still without agents or
   execution; trace/log presence remains deferred).
5. ~~Deterministic change-risk assessment over impact paths + runtime
   facts~~ DONE (Phase 7, above — ordinal levels with reasons and
   uncertainty flags, advisory only; no policy/execution).

Out of scope: agents, simulation, approvals UI, brokers, K8s,
vector search, frontend beyond API consumption readiness. Cross-database
atomicity for checkout stays out unless a future decision with a concrete
reconciliation design asks for it.
