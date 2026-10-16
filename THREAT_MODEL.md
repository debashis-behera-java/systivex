# Systivex Threat Model

Scope: **foundation + persistence + target-runtime + telemetry principles** (2026-09-30). Enforcement
mechanisms (policy engine, approvals, audit store) are **planned, not implemented** —
these principles bind all future design so the mechanisms can be built correctly.

## 1. System under analysis (today)

A single Spring Boot control plane on `:8080` exposing Actuator `health` plus a
local twin CRUD API (`/api/v1/twin`), backed by its own PostgreSQL database
(`systivex`) via Flyway migrations and validated JPA mappings. Alongside it,
four Phase 2B target services on `:8081–:8084` (gateway → order → inventory/payment)
with open local HTTP APIs, now backed by three service-owned databases
(`order_db`, `payment_db`, `inventory_db`, each with its own application user
that owns nothing else). No user data of value yet, no agents, no execution
capability, and the control plane does not call the target services.
Credential handling is load-bearing in both halves: every datasource password
(control-plane `SYSTIVEX_DB_PASSWORD` plus `ORDER/PAYMENT/INVENTORY_DB_PASSWORD`)
reaches its app only through the environment; all live `application.properties`
files are git-ignored and only secret-free example templates are tracked. The
gateway holds no database credentials at all.

## 2. Trust principles (binding)

### T1. The LLM is untrusted

- LLM output is **data**, never instruction. It is never executed, never used as
  authorization, and never treated as fact about the target system.
- Tool and connector outputs are likewise data by default, not authority:
  they inform proposals but never substitute for control-plane authorization.
- Every LLM-derived claim used in a decision must be corroborated against the
  System Twin or live backend state before it influences action.

### T2. User input is untrusted

- All input arriving at the control plane (REST bodies, SSE parameters, future
  UI/API calls) is validated, size-bounded, and encoded on output.
- Validation (`spring-boot-starter-validation` is present) must be applied at
  every trust boundary; validation failures fail closed with no side effects.

### T3. Backend systems are authoritative

- Ground truth is the System Twin (planned) backed by versioned backend state
  (code, config, runtime signals) — not model output, not client assertions.
- Reads that inform consequential decisions must come through authenticated,
  integrity-checked connectors; stale or unverifiable state blocks action, not permits it.
- Between target services this already applies in miniature: order-service
  never reads inventory or payment state from their databases (it cannot — no
  grants, no shared schema), only from their HTTP responses, which it treats
  as untrusted input validated at the boundary. Database ownership is a
  security boundary, not just an organizational one.

### T4. The agent cannot authorize itself

- Proposal and authorization are separate roles. The agent (planned) may propose
  changes and request actions; only the control plane's policy/execution path
  (planned) may authorize them.
- No tool, prompt, credential, or code path may allow an agent to mint, escalate,
  or replay its own authorization.

### T5. Consequential actions require control-plane authorization

- Any action with side effects outside the control plane (target-environment
  change, credential use, data mutation, outbound call) must cross the
  policy → execution boundary with an explicit grant.
- Default-deny: absence of a policy decision is denial. Failures fail closed.

### T6. High-risk actions require approval

- Actions classified high-risk (production writes, destructive operations,
  security-sensitive changes — classification to be defined with the policy engine)
  additionally require explicit human or delegated-policy approval before execution.
- Approval is bound to the exact assessed proposal (parameters, target, twin
  revision); any material change re-enters assessment.

### T7. Consequential actions must be auditable

- Every authorization, approval, execution, and verification outcome produces an
  append-only record: who/what was proposed, which policy version decided,
  who approved (if required), what executed, and what verification observed.
- Audit records must be sufficient to reconstruct and explain any change after the fact.

## 3a. Telemetry as a data surface (Phase 3A, verified 2026-09-30)

Telemetry is evidence about behavior, not a trusted channel — and it is
itself a place data can leak. The following holds for the current pipeline
(services → local collector debug log / local scrape endpoints):

- **Span attributes no longer carry bodies or business messages.**
  Automatic RestClient-error spans used to record the downstream 4xx response
  body in `exception.message`, and custom business observations recorded
  service-layer messages — both reachable by customer/product/amount data.
  A per-deployable `ObservationErrorSanitizer` now participates in Boot's
  observation-handler grouping through its own group ordered BEFORE the
  tracing handler group (verified against Boot 4.1.1 registration order),
  so the error the tracing handler records is already the payload-free
  replacement keeping only the original exception type name and the HTTP
  status; no credentials, passwords, tokens, or secrets were observed in
  span attributes, and a 2026-10-06 live collector-log check found zero
  synthetic-token hits across success/rejection/decline/outage traces.
  Guarantee (narrow, as implemented): observation-error paths cannot carry
  response bodies, business messages, or cause chains into spans. NOT
  claimed: that no future span attribute, log line, or metric label could
  ever carry sensitive data — telemetry remains a leakage surface and any
  new attribute/label/message must be reviewed here first. Covered by
  `TelemetryPrivacyTest` (recorded span data) plus the negative-direction
  `TelemetryPrivacyUnsanitizedTest`. Until a trace backend beyond the local
  collector exists, trace output stays on the local machine regardless.
- **Logs carry only outcome + internal IDs.** The three business log lines
  emit reservation/authorization/order IDs with a status word — no customer,
  product, amount, header, or secret values. Correlation uses the trace/span
  IDs in the log pattern, not payload data.
- **Phase 3B-2 log pipeline (2026-10-07): records now leave the JVM.**
  The OTel Logback bridge ships the same console records over OTLP to the
  collector and into Loki; nothing new is logged. What travels per record:
  the message text (outcome + IDs, unchanged), MDC `traceId`/`spanId`
  strings, and the native OTel trace/span IDs — all as structured metadata
  or message content, never as Loki index labels (only `service.name` is a
  label; see `DECISIONS.md` D21). The Phase 3A sanitizer is untouched and
  still governs span errors; log content was already payload-free, so no
  new redaction was needed. Verified live: no passwords, credentials,
  authorization headers, tokens, request bodies, or business payloads in
  Loki; synthetic privacy tokens from the span tests have no log path to
  leak through. Logs remain evidence about runtime behavior, not
  authoritative truth — a log line proves a code path ran, not that the
  world matches it. Cardinality is a consumption risk, not just a cost
  one: unbounded labels would also be a cheap denial-of-service against
  the local Loki, which is why the label policy is binding, not advisory.
- **Metrics are tag-free by rule.** The six business counters have no labels
  at all; in particular customer, order, product, and trace IDs must never
  become metric labels (cardinality abuse would also be a cheap
  denial-of-service against the local collector). Standard HTTP/JVM metrics
  keep only their low-cardinality framework labels.
- **Access boundary is the loopback interface.** Scrape endpoints
  (`/actuator/prometheus`), OTLP receivers (:4317/:4318), the
  collector's :8889 exposition, Loki's :3100 API, and Grafana's :3000 UI
  have no authentication — acceptable only because everything binds
  locally and nothing is reachable beyond the machine. Exposing any of
  these beyond localhost requires authentication and a recorded review
  (see §4: actuator surface).
- **Phase 4 sync trigger (2026-10-09): same boundary, one new mutating
  surface.** `POST /api/v1/twin/sync` writes to the twin, so it gets two
  loopback layers instead of one: the server binds `127.0.0.1` by default
  and the endpoint refuses non-loopback transport peers with 403
  (`getRemoteAddr()` only — `X-Forwarded-For` is untrusted input and is
  ignored; a missing peer is denied). The scanner only reads tracked
  example configs, route annotations, and migration SQL — never live
  `application.properties`, never environment values, never writes to the
  repository, never executes scanned files — so secrets cannot enter twin
  metadata through this path. Connector-owned rows are namespaced by
  `managedBy` marker + `target-*` refs; a squatted identity aborts the
  run (409, rolled back) rather than hijacking the row. Full API
  authentication remains deferred and still gates any non-local exposure.
- **Sampling 1.0 is local-only.** Full trace sampling is proportional to
  development traffic. Any shared or higher-traffic environment needs a
  sampling decision first.
- **Phase 5 impact query (2026-10-10): read-only, same trust level as the
  existing twin GETs.** `GET /api/v1/twin/impact` performs no mutation,
  execution, filesystem scanning, or external calls — one read-only
  transaction over already-persisted twin rows (counts unchanged after
  queries, asserted in tests). Input is validated and size-bounded before
  any graph work (1–10 starts, blank rejected, `maxDepth` 1–10,
  `includeTypes` against the closed enum; oversized input fails 400 without
  traversal; unknown identifiers fail 404) per T2; error bodies reuse the
  existing handler (no secrets, no stack traces — asserted in tests). No
  authentication infrastructure is introduced: like the existing twin GETs,
  this endpoint is local-development-only and must not be exposed beyond
  the machine without the deferred auth story (see §3: still open).
  **Binding caveat (evidence, not assumption):** the tracked example binds
  `127.0.0.1`, but a pre-existing live `application.properties` does not
  inherit it — verified 2026-10-10 that the live file carries no
   `server.address` line — so the effective bind is the framework default
   unless `SERVER_ADDRESS=127.0.0.1` is set or the live file is given the
   bind (see `DECISIONS.md` D23, `DEVELOPMENT.md` §10–§11). Impact results
   are advisory reachability ("potentially affected", per-path evidence
   included), never authorization or fact about the live system (T1/T3):
   over-approximation (`WRITES` is per-database, no `READS` edges) and
   staleness (`snapshotObservedAt` is an observation timestamp, not a
   revision) must block consequential use, not permit it.
- **Phase 6 runtime observation (2026-10-13): annotation, same trust
  level as the sync trigger.** `POST /api/v1/twin/observations` writes
  only the namespaced `runtime` metadata key of matched
  `target-service:*` rows — one transaction, full rollback on any
  validation/identity failure — and refuses non-loopback peers with 403
  like the sync endpoint (no new auth infrastructure; exposure beyond
  the machine still requires the deferred auth story). Collection pulls
  from `127.0.0.1` ports already in twin metadata; the request carries
  no URLs, so there is no caller-controlled fetch target. Ingestion is
  allowlist-only (health status + six tag-free counters); raw bodies,
  labels, and all other families are never stored, and unknown
  business-flavoured families or privacy-sensitive content abort the run
  instead of entering the twin. Absent counters and unreachable services
  are recorded as limitations with no zero-fill and no
  health-by-absence inference (T3). Runtime facts are advisory
  observations with an advancing `observedAt`, never authorization (T4–
  T6) and never a substitute for audit records (T7, still planned).
- **Phase 7 risk assessment (2026-10-14): classification, same trust
  level as the impact GET.** `GET /api/v1/twin/risk` performs no
  mutation, execution, filesystem scanning, or external calls — one
  read-only transaction reusing the impact traversal (counts unchanged
  after queries, asserted in tests). Ordinal levels with sorted reasons
  replace any numeric-score temptation: there is no probability to
  misread, and `UNKNOWN`/stale entries force caution instead of
  permitting action (T1/T3). The taxonomy version travels in the
  response so future policy work can pin exactly what it consumed. Like
  the impact endpoint, local-development-only until the deferred auth
  story lands (see §3: still open).
- **Phase 8 change simulation (2026-10-15): hypothetical, same trust
  level as the impact/risk endpoints.** `POST /api/v1/twin/simulations`
  looks like a write but performs none: the proposal is validated and
  applied to in-memory working copies inside one read-only transaction
  (counts unchanged after simulations, asserted in tests), and every
  response carries `simulated: true` plus the unmistakable
  `SIMULATED — NOT EXECUTED` advisory so a hypothetical result can
  never be mistaken for executed or persisted state (T1/T3). Input is
  validated in a fixed order before any graph work — impact conventions
  for roots/depth/types, then operation shape (400), identifier
  resolution (404), then proposal conflicts (409: root removal,
  canonical duplicates/contradictions, existing/missing edges,
  incident-to-removed) per T2; error bodies reuse the existing handler
  (no secrets, no stack traces — asserted in tests). Canonical
  UUID-level comparison closes the alias hole (same endpoint spelled as
  UUID vs external reference). No authentication infrastructure is
  introduced: like the impact/risk endpoints, local-development-only
  until the deferred auth story lands (see §3: still open). Simulation
  deltas are advisory reachability/risk differences, never authorization
  or fact about the live system (T4/T5); runtime metadata is reused
  unchanged and labelled as such, never simulated.
- **Phase 9 advisory policy evaluation (2026-10-15): judgment, same
  trust level as the intelligence endpoints.**
  `POST /api/v1/twin/policy-evaluations` accepts the exact simulation
  proposal format and re-runs the simulation server-side inside one
  read-only transaction — caller-supplied risk or deltas are never
  trusted as evidence (untrusted input cannot drive the outcome).
  Decisions are advisory ALLOW/REVIEW/DENY from immutable code rules
  (`policy-v1`, version pinned in-response); HIGH or truncated results
  fail closed to advisory DENY, missing/stale evidence escalates to REVIEW at minimum and never reads as safe (T1/T3). The response states
  unmistakably that nothing was authorized, approved, executed, or
  persisted (T4/T5); no approval workflow, execution path, or audit
  record is created, and none of the deferred T6/T7 machinery is
  introduced. Error bodies reuse the existing handler (no secrets, no
  stack traces — asserted in tests). Like the other intelligence
  endpoints, local-development-only until the deferred auth story lands
  (see §3: still open).
- **Phase 10 control-plane authentication (2026-10-15): the deferred
  auth story lands for the control plane.** All `/api/v1/twin/*`
  endpoints and `/actuator/prometheus` now require JWT bearer tokens
  from a configured trusted issuer (see `DECISIONS.md` D28): missing or
  invalid credentials are 401, authenticated identities without the
  required role are 403, and unknown/malformed role claims grant
  nothing (`TWIN_ADMIN` inherits the three lesser roles). The sync and
  observation loopback guards stay authoritative behind the
  `TWIN_OPERATOR` role — a valid operator token from a non-loopback peer
  is still refused, and `X-Forwarded-For` remains untrusted input.
  `GET /actuator/health` stays anonymous but exposes status only
  (`show-details=never`). Security error bodies are fixed safe messages
  (no bearer tokens, headers, secrets, claims, or stack traces —
  asserted in tests); nothing in the security path logs credentials.
  Simulation and policy evaluation stay read-only and deterministic: an
  advisory ALLOW is never authorization to execute a change (T4/T5), and
  no approval, execution, audit-ledger, migration, or target-service
  authentication surface is introduced here. Tests use locally generated
  signing keys only — automated JWT coverage is NOT live identity
  verification, and none is claimed without a configured trusted
  issuer. The four target-service APIs remain open local-only endpoints
  (unchanged by this phase) and must still not be exposed beyond the
  machine.

## 3. Out-of-scope today, in-scope for design

- Control-plane authentication is implemented (Phase 10, D28); still
  open: live identity-provider verification (no trusted issuer
  configured here), secret management, key rotation, and any
  authentication on the four target-service APIs (still open local-only
  endpoints — acceptable on a local machine, not beyond it). Each
  service's database credential remains a separate local secret;
  compromising one application user yields that service's database only.
- Twin-connector authentication and data-integrity guarantees.
- Approval UX and delegation model (governance design agreed in
  `DECISIONS.md` D29 — distinct `TWIN_APPROVER`, hard separation of
  duties, DENY-never-approvable; implementation pending, none of it may
  be claimed as built); risk-classification taxonomy.
- Execution sandboxing, blast-radius limits, rollback/verification loops.
- Persistence hardening and audit-log immutability (twin tables exist
  now; the audit chain is designed in `DECISIONS.md` D29 as
  tamper-evident — hash-chained with a verify endpoint — explicitly not
  tamper-proof against a database superuser; implementation pending).

Each must reference the principle(s) above when introduced; any exception
requires a dated entry in `DECISIONS.md`.

## 4. What must NOT happen next (anti-goals)

- Direct agent-to-target execution paths bypassing the control plane.
- LLM output concatenated into queries, shell, or config without mediation.
- Silent policy bypasses, self-approval, or unaudited "break-glass" writes.
- Broadening the Actuator or API surface without a recorded security review.
- Hibernate schema auto-generation (`update`/`create`/`create-drop`) against any
  database that matters; committed credentials in any form.
- Direct cross-service database access (even read-only) bypassing the HTTP
  boundary; shared schemas or cross-database foreign keys between services.
