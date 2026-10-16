# Systivex Architectural Decisions

Record of binding decisions taken at project foundation (2026-09-28) and
extended in Phase 1 and Phase 2A (2026-09-29), Phase 2B and Phase 3A (2026-09-30),
Phase 3B-1/3B-2 (2026-10-06/07), Phase 4 (2026-10-09), Phase 5 (2026-10-10),
and Phase 6 (2026-10-13), and Phase 7 (2026-10-14).
Status labels: **decided** (binding now) / **planned** (intent, not yet implemented).

## D1. Java 21 — decided

- **Decision:** Java 21 as the language baseline (`<java.version>21</java.version>`, `release 21`).
- **Rationale:** LTS, virtual threads, modern language features; matches Spring Boot 4.x requirements.
- **State:** Implemented and verified (`./mvnw test` compiles with `release 21` on JDK 21.0.10).

## D2. Spring Boot (4.1.1) — decided

- **Decision:** Spring Boot 4.1.1 via `spring-boot-starter-parent` for the control plane.
- **Rationale:** Convention-based wiring, production-ready Actuator surface, mature test support.
- **State:** Implemented and verified (banner `v4.1.1`, Tomcat 11.0.24).

## D3. Maven (Wrapper) — decided

- **Decision:** Maven with the checked-in Wrapper (3.9.16, `wrapperVersion 3.3.4`); no Gradle.
- **Rationale:** Reproducible builds without a preinstalled Maven; standard Spring layout.
- **State:** Implemented and verified (`./mvnw test | package | spring-boot:run`).

## D4. Modular monolith for the Systivex control plane — decided

- **Decision:** Systivex itself is a **modular monolith**: one Spring Boot deployment
  with strict internal module boundaries (`twin`, `intelligence`, `policy`,
  `execution`, `verification`, `observation`, `api` as target modules).
- **Rationale:** Single deployable is operable at this scale; explicit module APIs
  preserve the option to extract services later without paying distribution costs now.
- **State:** Planned structure; **no module packages exist yet** (no placeholders by decision).

## D5. Microservices reserved for the target environment — decided

- **Decision:** Microservices are the shape of the **future target software environment**
  (systems under observation/management), never of the Systivex control plane at this stage.
- **Rationale:** Keeps a hard control-plane vs. target-environment separation;
  avoids distributed-systems overhead before there is anything to manage.
- **State:** Deferred; no target services exist.

## D6. PostgreSQL as primary persistence — decided (activated 2026-09-29)

- **Decision:** PostgreSQL is the primary system of record (twin state first;
  proposals, policy decisions, execution/verification records later).
- **Rationale:** Relational integrity and auditability suit safety-critical records.
- **State:** Implemented in Phase 1: local PostgreSQL 18, `systivex` database,
  `systivex_app` owner. Previously deferred; activated when the twin model
  needed persistence, per the original intent.

## D7. Redis only when justified — decided

- **Decision:** No Redis until a concrete, measured need (e.g. rate limiting, ephemeral
  coordination, cache with invalidation story) is recorded.
- **Rationale:** Avoids a second state system and its consistency/ops burden prematurely.
- **State:** Deferred; no Redis dependency or configuration exists.

## D8. Spring AI + Ollama later — planned

- **Decision:** Agent reasoning will use Spring AI against local Ollama models first
  (cloud models only if a later decision permits).
- **Rationale:** Keeps inference local and cost-predictable during development;
  Spring AI gives provider portability behind the control-plane boundary.
- **State:** Deferred; no Spring AI dependency, no model configuration exists.

## D9. REST + SSE initially — decided

- **Decision:** Synchronous surface is REST; streaming progress (analysis/simulation)
  is Server-Sent Events. No WebSockets at this stage.
- **Rationale:** REST covers CRUD/authorization flows; SSE covers one-way progress
  without the lifecycle/state costs of bidirectional sockets.
- **State:** Partially implemented; twin entity/relationship CRUD exists under
  `/api/v1/twin`. Proposals/approvals/records and SSE are still planned.

## D10. No premature infrastructure — decided

- **Decision:** Do not introduce Kafka, RabbitMQ, Kubernetes, WebSockets, vector
  databases, MCP, multi-agent architecture, or cloud infrastructure at this stage.
  Likewise no Docker Compose, security architecture, or frontend scaffolding yet.
- **Rationale:** Each adds ops surface, failure modes, and security scope without a
  consumer. Infrastructure follows demonstrated need and a recorded decision.
- **State:** Enforced in this phase; no Kafka/RabbitMQ/K8s/WebSockets/vector DB/
  MCP/multi-agent/cloud. `pom.xml` holds only the Phase 1 persistence and web
  set (see `DEVELOPMENT.md` §2 for the list).

## D11. Flyway owns the schema; Hibernate only validates — decided (2026-09-29)

- **Decision:** All schema change goes through versioned Flyway migrations under
  `db/migration/`; `spring.jpa.hibernate.ddl-auto=validate` everywhere, including
  local runs. `update`/`create`/`create-drop` are banned outside throwaway
  scratch work that never touches a shared database.
- **Rationale:** The migration history is the reviewable, replayable record of
  what the schema is; validation keeps the JPA mapping honest without giving
  the ORM write access to the schema.
- **State:** Implemented: `V1__create_system_twin.sql`, validated at every
  startup and in tests.

## D12. Twin persistence model v1 — decided (2026-09-29)

- **Decision:** Two tables (`system_entity`, `system_relationship`), UUID keys,
  closed enum type sets (7 entity, 8 relationship), JSONB metadata through
  Hibernate's native JSON mapping (no extra JSON library), FK constraints with
  `ON DELETE CASCADE`, unique `external_ref` and `(source, target, type)`
  triples, no-self-reference check.
- **Rationale:** Covers what Phase 1 needs to prove (persist, link, constrain)
  with the fewest moving parts; enums stay closed until a connector demands more.
- **State:** Implemented with repository, service, DTO REST layer, and
  constraint tests.

## D13. Testcontainers for database tests, one container per run — decided (2026-09-29)

- **Decision:** Integration tests run against throwaway PostgreSQL 18 containers
  (single shared container per JVM via `@DynamicPropertySource`); the
  developer's local database is never used by tests. Testcontainers stays a
  test-scope-only dependency.
- **Rationale:** CI-reproducible without local setup; one container avoids the
  churn of per-class containers. Local runs still target the developer's own
  PostgreSQL with `SYSTIVEX_DB_PASSWORD` from the environment.
- **State:** Implemented: 21 of 29 tests run against the container.

## D14. Boot 4 dependency notes — decided (2026-09-29)

- **Decision:** Use `spring-boot-starter-flyway` (raw `flyway-core` alone gets no
  auto-configuration in Boot 4); Testcontainers modules by their 2.x names
  (`testcontainers-postgresql`, version via an imported `testcontainers-bom`
  pinned to the release Boot 4.1.1 manages); `spring-boot-starter-restclient-test`
  for `TestRestTemplate` in API tests.
- **Rationale:** Boot 4 split several starters/modules; following the new
  coordinates keeps versions managed instead of hand-pinned.
- **State:** Implemented in `pom.xml` (see the BOM comment there).

## D15. Target microservices live apart from the control plane — decided (2026-09-29)

- **Decision:** The observed system (`backend/target-services/`: gateway, order,
  payment, inventory) is built as separate Spring Boot deployables, while
  Systivex itself stays a modular monolith. The two sides share no code, no
  database, and no packages — only HTTP contracts documented in
  `ARCHITECTURE.md` §4.
- **Rationale:** The control-plane/target-environment split is the whole point
  of the architecture: the decider must not be the thing being changed. Separate
  deployables make the future observation boundary real instead of notional,
  without paying for brokers, service mesh, or orchestration before anything
  needs them.
- **State:** Implemented: 4 services, fixed dev ports 8081–8084, verified
  end to end. Control-plane code untouched.

## D16. Plain RestClient edge, deterministic stateless targets — decided (2026-09-29)

- **Decision:** Service-to-service calls use plain `RestClient` with
  configuration-driven URLs; the "gateway" is an explicit forwarding controller,
  not a gateway framework. Inventory/payment answer from fixed demo rules
  (reject > 5 units, decline > 5000.00) with no database; failures surface as
  422/503/502 with sanitized bodies and basic timeouts, no retry or
  circuit-breaker libraries.
- **Rationale:** A framework gateway and resilience machinery would hide the
  very HTTP behavior Phase 2A exists to demonstrate. Deterministic rules make
  tests and demos repeatable; persistence arrives per-service in Phase 2B.
- **State:** Superseded in Phase 2B for the persistence part (D17): the demo
  quantity rule is replaced by real stock, but the payment amount rule stays
  and the RestClient/no-retry/no-gateway-framework shape is unchanged.

## D17. Service-owned databases, no cross-service data access — decided (2026-09-30)

- **Decision:** Each target service owns exactly one PostgreSQL database
  (`order_db`/`order_app`, `payment_db`/`payment_app`,
  `inventory_db`/`inventory_app`), migrated by that service's own Flyway
  history and validated by its own JPA mappings (`ddl-auto=validate`). The
  control-plane database (`systivex`/`systivex_app`) is untouched and shares
  nothing with them. No service reads or writes another service's tables, no
  shared schemas, no cross-database foreign keys — not even for "read-only"
  convenience. If order-service needs payment or inventory facts, it calls
  those services over HTTP and treats the response as untrusted input
  (validated DTOs, same as before).
- **Rationale:** A shared database would quietly recouple services that HTTP
  boundaries keep apart: one team's migration could break another service,
  and "just this one join" becomes distributed logic with no contract. The
  database boundary makes the service boundary real — each schema can evolve
  with its owner, and every cross-service read stays visible as an HTTP call
  with explicit failure modes. Separate application users (each owning only
  its database) enforce this at the PostgreSQL level, not just by convention.
- **State:** Implemented: `orders` in order_db; `payments` in payment_db;
  `inventory_items` + `inventory_reservations` in inventory_db (no FK between
  the two, so rejected attempts for unknown products are still recordable).
  Known limitation accepted, not solved: checkout is not atomic across the
  three databases — a FAILED order can coexist with a standing reservation.
  Faking atomicity (cross-DB writes, a transaction coordinator) was rejected;
  the honest PENDING → CONFIRMED/FAILED record is the foundation a future
  reconciliation/simulation story can build on.

## D18. Telemetry before twin ingestion — decided (2026-09-30)

- **Decision:** Instrument the target environment for metrics/traces/logs
  (Phase 3A) before building any System Twin runtime ingestion of that
  telemetry.
- **Rationale:** Ingestion built against uninstrumented services would define
  its data contracts against guesses. Real, running telemetry — with known
  span names, metric names, and correlation behavior verified end to end —
  gives the future ingestion work an observed contract to consume instead of
  a speculative one. The control plane still consumes nothing; the two sides
  of the pipeline are built and verified independently.
- **State:** Implemented: the four target services plus the control plane emit
  OTLP traces and Prometheus metrics; collection is verified locally. No twin
  ingestion exists.

## D19. Minimal Boot-managed observability, pull-based metrics — decided (2026-09-30)

- **Decision:** Two dependencies per service, both version-managed by the
  Boot 4.1.1 BOM: `spring-boot-starter-opentelemetry` (tracing + W3C context
  propagation) and `micrometer-registry-prometheus` (scrape endpoint). No
  hand-pinned OTel/Micrometer versions, no second instrumentation library.
  Metrics stay pull-based (`/actuator/prometheus`); OTLP metric export and
  OTLP log export are disabled. Logs stay on the console with a trace/span
  correlation pattern. Sampling is 1.0 — acceptable only because all traffic
  is local development volume.
- **Rationale:** Managed versions remove a whole class of span-duplication and
  version-skew faults. Pull-based metrics need no per-service export
  configuration and fail visibly (empty scrape) rather than silently (dropped
  push). A single local OpenTelemetry Collector (contrib, pinned image) is
  the only infrastructure: OTLP receiver for traces, Prometheus receiver
  re-scraping the services, debug exporter to its own log for trace
  verification, Prometheus exporter on :8889. No dashboards, log store, or
  trace store — visualization is Phase 3B.
- **State:** Implemented. Three custom business spans only (`checkout`,
  `inventory.reservation`, `payment.authorization`); everything else is
  automatic HTTP instrumentation. Six tag-free business counters
  (`checkout.success/failure`, `inventory.reservation.success/rejected`,
  `payment.authorization.success/declined`) — outcome in the name, no
  customer/order/product/trace labels. RestClient builders carry the
  observation registry explicitly so trace context crosses each HTTP hop.
- **Telemetry-error redaction (closure fix, 2026-09-30):** automatic
  client-error spans copied downstream 4xx response bodies into
  `exception.message`, and custom business observations recorded
  service-layer messages — both can carry customer/product/amount data.
  Verified root cause: the OTel bridge calls `Span.recordException`
  immediately in the tracing handler's `onError`, so a stop-time
  `ObservationFilter` would run too late. Fix: one stateless
  `ObservationErrorSanitizer` (`ObservationHandler<Observation.Context>`,
  `@Order(HIGHEST_PRECEDENCE)`, picked up by Boot's ordered handler
  registration) per deployable replaces the recorded error with a
  payload-free equivalent carrying only the original exception type name
  and, for `HttpStatusCodeException`, the HTTP status. No cause chain (a
  cause would reintroduce the payload via the stacktrace attribute).
  Thrown exceptions, API responses, and logs are untouched. Side effect,
  accepted: timer `error` tags report the sanitizer type on error
  observations instead of the original type (still low-cardinality).
   Regression test `TelemetryPrivacyTest` (order-service) fails without the
   sanitizer and passes with it — verified both directions.
- **Telemetry-error redaction, correction (2026-10-06):** the 2026-09-30
  `@Order(HIGHEST_PRECEDENCE)` fix was insufficient and live verification
  proved it: Boot 4.1.1 registers tracing/meter handlers through its
  `TracingAndMeterObservationHandlerGroup` composite AHEAD of ungrouped
  `ObservationHandler` beans, so the tracing handler's `onError` recorded
  the raw exception (`Span.recordException` → `exception.message` /
  `exception.stacktrace` / status description) BEFORE the late sanitizer
  replaced the final recorded error. Verified against the actual jars
  (Boot 4.1.1 `ObservationHandlerGroups.register` sorts groups and registers
  group composites before ungrouped handlers; Micrometer 1.17.1
  `SimpleObservation.notifyOnError` notifies handlers sequentially in
  registration order; Micrometer-tracing 1.7.1 `TracingObservationHandler`
  records `context.getError()` at notification time). Fix, per deployable:
  the sanitizer now belongs to its own `SanitizerObservationHandlerGroup`
  ordered before the tracing group, so its `onError` replaces the error
  first and the tracing handler records only the sanitized replacement.
  Joining the tracing group itself was rejected: group members dispatch
  first-match, which would suppress the real tracing handler. Preserved:
  HTTP status, original exception type name, observation/span names, trace
  context. Removed: response bodies, business messages, cause chain.
  `TelemetryPrivacyTest` now asserts on in-memory RECORDED SPAN DATA
  (event attributes + status description), and
  `TelemetryPrivacyUnsanitizedTest` proves the same payload leaks with the
  sanitizer removed — verified failing-without / passing-with on 2026-10-06,
  plus a live collector-log check with zero synthetic-token hits.

## D20. Prometheus + Grafana visualization on the existing metrics path — decided (2026-10-06)

- **Decision:** Extend the existing `observability/docker-compose.yml`
  (no second Compose file) with pinned `prom/prometheus:v3.5.0` (:9090)
  and `grafana/grafana:12.1.0` (:3000). Prometheus scrapes ONLY the OTel
  Collector's `:8889` exposition — the collector stays the sole scraper
  of the Java services, so no duplicate collection path exists. Grafana
  gets its Prometheus datasource and the single `Systivex Metrics`
  dashboard purely through file provisioning (`observability/grafana/`);
  local Grafana defaults, no secrets in the repo, no database files in Git.
  Per-service identity uses a `server_port` → `service` relabel in
  `prometheus.yml`, because the collector stamps `service_name="systivex"`
  on every scraped app metric. OTel histograms arrive without buckets on
  this path, so the latency panel is a sum/count average, not a p95 —
  recorded here so nobody "fixes" it back to a quantile that returns no
  data. No Java application changes; no Loki/Tempo, no twin ingestion, no
  agent use (all still deferred).
- **Rationale:** Visualization must consume the observed contract, not
  redefine it: one more scrape hop would double-load the services and
  split the metric identity scheme. Provisioning (not browser clicks)
  keeps the dashboard reproducible from a fresh checkout.
- **State:** Implemented and verified live 2026-10-06 (checkout + failure
  scenarios move the dashboard metrics; control plane verified on a
  temporary :8090 because :8080 is held by another project on this
  machine).

## D21. Loki log exploration over the existing OTLP path — decided (2026-10-07)

- **Decision:** Make application logs searchable in Grafana via Loki
  (`grafana/loki:3.5.0`, single binary, filesystem storage, no auth — local
  development only), fed by the existing OTel Collector. No second Compose
  file, no Tempo, no new dashboard: Grafana Explore is the log UI for this
  phase. Application logging stays exactly as Phase 3A defined it (console
  pattern `[service,traceId,spanId]`, outcome + internal IDs only); OTLP
  export is an additional sink, not a replacement.
- **Rationale:** Logs are the third signal of the already-observed contract
  (spans, counters, correlated lines). Reusing the OTLP path keeps one
  collection story instead of adding a parallel shipper per service.
- **The missing bridge (verified, not assumed):** setting Boot's
  `management.logging.export.otlp.*` properties alone ships nothing — Boot
  wires the SDK and the OTLP log exporter but installs no Logback bridge, so
  no record ever reaches the exporter. Each deployable therefore gets the
  smallest working addition: `opentelemetry-logback-appender-1.0` (version
  from an imported `opentelemetry-instrumentation-bom-alpha`,
  `2.28.1-alpha` — that line compiles against `opentelemetry-api 1.62.0`,
  exactly what Boot 4.1.1 manages, so no SDK skew) plus a
  `logback-spring.xml` (Boot base config + one OTEL appender; console
  untouched) and a small installer bean calling
  `OpenTelemetryAppender.install(openTelemetry)` on ready. Only the
  trace/span MDC keys are copied as attributes; nothing else about the log
  statements changes, and the sanitizer is untouched.
- **Collector:** one `logs` pipeline appended to the existing
  `observability/otel-collector.yml` (`otlp` receiver → `batch` →
  `otlphttp/loki`: `http://loki:3100/otlp` — the exporter appends
  `/v1/logs` itself, so the full path must NOT be configured or Loki
  answers 404). Traces/metrics pipelines unchanged. The contrib `loki`
  exporter was evaluated first and rejected: it no longer ships in
  collector 0.140.x (removed upstream after deprecation) in favor of
  OTLP export to Loki's native endpoint.
- **Label policy (binding):** only `service.name` becomes a Loki index
  label. Trace/span IDs, `service.instance.id` (fresh UUID per Boot start),
  SDK/process/host resource detail, and scope fields travel as structured
  metadata — searchable in LogQL (`| traceId="<hex>"`) but never stream
  labels, so one trace or restart can never fan out into per-trace streams.
  Customer/order/product/amount values never leave the JVM in log form, so
  they cannot become labels either. Verified against the running Loki via
  the `/labels` index endpoint (carries `service_name` only) plus
  positive/negative metadata-filter queries.
- **State:** Implemented; `OtlpLoggingExportTest` (export wired, checkout
  survives a missing collector) plus `OtlpLogDeliveryTest` (a checkout
  record actually arrives at a stub OTLP receiver with MDC trace context —
  the regression test the missing bridge would have failed). Live
  verification pending service restarts (see `PROJECT_STATUS.md`).

## D22. Read-only repository connector + localhost-only sync trigger — decided (2026-10-09)

- **Decision:** The first twin ingestion is a read-only repository
  connector (`observation` package, control plane only): a scanner reads
  the target-service tree — service directories, tracked
  `application.example.properties` (names, ports, datasource DB/owner,
  downstream URLs), controller `@RequestMapping`/`@XMapping` annotations,
  Flyway `CREATE TABLE` statements — and maps them onto the EXISTING
  closed enums (4 `SERVICE`, 3 `DATABASE`, 4 `TABLE`, 4 `API`; `CALLS` /
  `DEPENDS_ON` / `CONTAINS` / `WRITES` / `EXPOSES`). No enum expansion was
  needed; none is permitted without a documented incompatibility. Stable
  `target-*` external refs; ports/paths/ownership/provenance
  (`managedBy: repository-connector`, `phase4-v1`) in JSONB metadata.
  Sync is explicit (`POST /api/v1/twin/sync`), idempotent, and one
  transaction: discovery validates fully before any write; connector-owned
  rows are created/refreshed; manual rows are never touched; identity
  collisions abort with 409 and roll back; bad roots fail 400 with no
  writes. No watchers, schedulers, Git remotes, or telemetry ingestion.
- **Rationale:** The twin had schema but no content; the repository is the
  most deterministic source of truth available (configs, routes, and
  migrations are already reviewed facts), while telemetry ingestion would
  define consumption contracts against a noisier signal. An explicit
  trigger keeps observation reviewable and replayable — a twin that
  silently rewrites itself cannot be the authoritative record T3 demands.
- **Localhost boundary (binding):** this phase adds no authentication
  system, so the new mutating trigger must not serve the network.
  Verified starting position: no `spring-security`, no `server.address` —
  default bind is all interfaces, and the existing twin POST endpoints are
  already open. Boundary, two layers, no header trust: (1)
  `server.address=127.0.0.1` in the tracked example config (fresh copies
  bind loopback; existing live copies must add it — see `DEVELOPMENT.md`
  §10); (2) `LoopbackGuard` in the sync path refuses any
  non-loopback `getRemoteAddr()` peer with 403 (`X-Forwarded-For` ignored
  by design; missing peer denied). Covered by `LoopbackGuardTest` plus an
  HTTP 403 test. Full API authentication stays deferred (Milestone 2C
  item 2, still open).
- **What the scanner never touches:** live `application.properties`,
  process environment, and the repository itself (reads only; scanned
  files are never executed). Only placeholder defaults (the part after
  `:` in `${VAR:default}`) are consumed, so datasource passwords and
  local secrets cannot flow into twin metadata. Nothing is logged except
  counts/names/refs — all already-tracked facts.
- **State:** Implemented and tested (20 new tests, 51 green total, build
  succeeds). Local live boot pending the control-plane credential
  (see `PROJECT_STATUS.md`).

## D23. Deterministic read-only change-impact traversal — decided (2026-10-10)

- **Decision:** The first `intelligence` module is a read-only deterministic
  BFS over the persisted twin graph (`intelligence` package, control plane
  only): `GET /api/v1/twin/impact` resolves 1–10 starting entities (UUID or
  `external_ref`, sorted; blank/oversized → 400 before any graph work,
  unknown → 404) and returns impacted SERVICE/API/DATABASE/TABLE entities
  with ordered evidence paths. Every step records FORWARD vs. REVERSE
  against the stored direction (`CALLS`/`DEPENDS_ON`/`CONTAINS`/`WRITES`/
  `EXPOSES` per D22) plus a per-step sentence: reverse steps explain
  dependence ("may affect"), forward steps list downstream dependencies as
  reachability, never confirmed breakage. Types without documented domain
  meaning (including manual rows such as `READS`) are included as
  structural reachability with a generic sentence — no invented semantics.
  One `@Transactional(readOnly=true)` snapshot (single `findAll` with the
  existing `@EntityGraph`, so no N+1); two additive fetch-joined finders
  (`findBySourceId`/`findByTargetId`) cover adjacency queries and are
  repository-tested. Caps (500 nodes / 2000 paths) truncate explicitly with
  a reason; per-path revisit guard always terminates revisits and reports
  `cycleEncountered` only for genuine directed cycles (one consistent
  traversal direction around the loop — the acyclic Phase 4 graph reports
  false; bidirectional backtracks and undirected-only loops such as
  diamonds or service—table—database triangles terminate silently). `snapshotObservedAt` is an observation timestamp, not
  a graph revision — no `createdAt`-max revision claim, no migration. No
  new dependencies, no risk/policy/execution/telemetry content.
- **Rationale:** The twin has had content but no reasoning since Phase 4;
  impact traversal is the smallest useful consumer: it exercises the real
  stored directions in both directions, establishes the evidence-path
  pattern later simulation/risk/policy work must reuse, and stays inside
  the existing trust model (read-only GET alongside the existing open twin
  GETs — no new mutating surface, hence no new auth infrastructure; the
  loopback bind note below still applies).
- **Effective localhost boundary (verified, not assumed):** the tracked
  example config carries `server.address=127.0.0.1`, but the live
  git-ignored `application.properties` does NOT inherit tracked files —
  verified 2026-10-10 (22-line live file, no `server.address` line; file
  contents otherwise uninspected, file never edited). Effective bind is
  therefore the Spring default (all interfaces) unless the operator sets
  `SERVER_ADDRESS=127.0.0.1` or adds the bind to the live file. Previous
  live verification used the environment override for exactly this reason.
  No claim is made that loopback is active by default; `DEVELOPMENT.md`
  §10–§11 documents the required runtime configuration. Broad
  authentication (Milestone 2C item 2) stays open and still gates any
  non-local exposure.
- **State:** Implemented and tested (37 new tests, 88 green total, build
  succeeds). Live verification pending (see `PROJECT_STATUS.md`).

## D24. Pull-based twin telemetry ingestion with strict allowlist — decided (2026-10-13)

- **Decision:** The second twin ingestion is a pull-based telemetry
  connector (`telemetry` package, control plane only): an explicit
  `POST /api/v1/twin/observations` trigger (default body `{}`, no
  caller-supplied URLs) pulls Actuator health and Prometheus exposition
  from `127.0.0.1` ports already recorded in twin metadata, validates
  every payload through dependency-free deterministic parsers, and
  annotates only the matched connector-managed `target-service:*`
  entities under a namespaced `runtime` metadata key
  (`managedBy: telemetry-connector`, `phase6-v1`). No schedulers,
  watchers, bulk log import, raw trace storage, new entity types, new
  migrations, or new dependencies.
- **Allowlist (pinned to the instrumented contract, verified against the
  target-service sources):** service health status (`UP`/`DOWN`/
  `OUT_OF_SERVICE`/`UNKNOWN`) plus exactly six tag-free business counter
  families — `checkout.success/failure` (order),
  `inventory.reservation.success/rejected` (inventory),
  `payment.authorization.success/declined` (payment); gateway-service has
  no business counters (health only). Absent families are reported as
  absent with a limitation — never inferred as zero. Unknown
  business-flavoured families, any labels on allowlisted counters,
  unbounded payloads, malformed values, and privacy-sensitive content
  (credentials, tokens, CUST-/SKU- identifiers) fail the whole run with
  400/409 and full rollback before any write.
- **Availability vs. validity:** unreachable services are missing
  evidence, not malformed evidence — recorded per-service as unavailable
  with limitations (a run where nothing was reachable returns zero
  updates plus explicit limitations, never a success claim). Health is
  never inferred from missing telemetry and outcomes never from unrelated
  counters. Trace/log presence is deliberately not collected: no existing
  source offers a privacy-safe presence signal without bulk import, so
  the receipt carries that limitation instead of fabricated evidence.
- **Idempotency/timestamp contract:** `observedAt` always advances and
  stored timestamps advance with it (staleness can never be hidden);
  `factsUpdated` counts services whose health/counters differed while
  `factsUnchanged` counts identical-fact re-observations. A repeat poll
  with identical counters reports zero updates and still proves a fresh
  observation.
- **Composition:** Phase 4 sync preserves the `runtime` key across
  re-syncs (excluded from its same-check, so idempotency counts hold);
  telemetry never writes relationships, so Phase 5 impact shape and
  reachability are unchanged. Same localhost boundary as D22
  (`LoopbackGuard`, 403 off loopback, no new auth). Broad
  authentication stays open and still gates any non-local exposure.
- **State:** Implemented and tested (41 new tests, dependency-free
  parsers). Live verification pending (see `PROJECT_STATUS.md`).

## D25. Deterministic ordinal change-risk assessment — decided (2026-10-14)

- **Decision:** The second `intelligence` module is a read-only
  deterministic risk classification over one Phase 5 impact traversal
  plus Phase 6 runtime metadata (`intelligence` package only):
  `GET /api/v1/twin/risk` reuses the impact service for traversal and
  validation (same `from` 1–10, `maxDepth` 1–10 default 6,
  `includeTypes`, 400/404 conventions) and classifies the impacted set in
  one `@Transactional(readOnly=true)` unit. No BFS duplication, no new
  dependencies, no migration, no writes.
- **Taxonomy `risk-v1` (documented in `RiskTaxonomy`, no numeric scores
  anywhere):** depth 1 starts `MEDIUM`, depth 2+ starts `LOW`; five or
  more independent evidence paths raise one step (calibrated so routine
  triangle/diamond alternatives on the Phase 4 graph do not inflate);
  reported `DOWN`/`OUT_OF_SERVICE` sets `HIGH`; cumulative failure
  families (`.failure`/`.rejected`/`.declined` above zero) raise one
  step as lifetime evidence, never rates or incident proof; zero and
  success counters never lower. A `SERVICE` without `runtime` metadata
  is `UNKNOWN`; metadata older than 30 minutes is stale (boundary-tested:
  exactly 30m is fresh) — stale severe signals are retained while flagged
  `stale` + `uncertain`, so missing or stale evidence never reads safer.
  Only services carry runtime, so only services receive
  health/counter/freshness reasoning; tables/databases/APIs are assessed
  structurally, stated in the response note.
- **Rationale:** Impact paths say what is reachable; runtime facts say
  what was last observed. The classification joins them with the smallest
  explainable rule set (ordinal levels, sorted reasons, explicit flags)
  so a later policy story has a reviewable input instead of a black-box
  score. Same trust posture as the impact GET (read-only, local-only,
  no new auth); the result is advisory and never authorization (T4/T5).
- **State:** Implemented and tested (36 new tests: 22 pure rule-unit +
  14 HTTP/API integration tests against the real 15/18 graph). Live
  verification pending (see `PROJECT_STATUS.md`).

## D26. Deterministic change simulation over a hypothetical graph — decided (2026-10-15)

- **Decision:** The third `intelligence` module is a read-only
  deterministic change simulation (`POST /api/v1/twin/simulations`):
  the request carries analysis roots (`from` 1–10 UUID/external-ref,
  `maxDepth` 1–10 default 6, `includeTypes` default
  SERVICE/API/DATABASE/TABLE — validated with the exact impact-query
  semantics via the shared `parseInputs`) plus a change proposal of
  `REMOVE_ENTITY`, `ADD_RELATIONSHIP`, and `REMOVE_RELATIONSHIP`
  operations. `REMOVE_ENTITY` deletes the entity and all incident
  relationships in the hypothetical graph only. The service compares
  baseline versus hypothetical impact and risk using identical roots and
  settings, reusing the existing traversal (`analyzeResolved`) and risk
  classification (`assessResolved`) — no algorithm duplication — and
  returns deterministic deltas, explicit truncation/uncertainty
  limitations, and `simulated: true` with the unmistakable
  `SIMULATED — NOT EXECUTED` advisory. Nothing is persisted: no
  proposals, entities, relationships, or runtime metadata are written
  (one `@Transactional(readOnly=true)` unit).
- **Validation order (fixed, first violation wins per phase):**
  root/maxDepth/includeTypes (400/404, impact semantics) →
  operation shape, 400 (`type`/`entity`/`source`/`target`/
  `relationshipType` presence) → identifier resolution, 404 →
  proposal conflicts, 409, in listed order: removing an analysis root;
  duplicate exact operations (canonical UUID comparison, so UUID and
  external-reference spellings of the same endpoint collide);
  contradictory add-plus-remove of one edge; adding an existing edge or
  removing a non-existent one; any relationship operation incident to an
  entity removed by the same proposal. Unknown operation types fail 400
  at JSON binding through the existing handler.
- **Structure:** pure units (`SimulationProposalValidator`,
  `HypotheticalGraph`, `SimulationDiff` — no repository, no Spring,
  unit-tested without a database) orchestrated by a thin
  `SimulationService`; every new file stays under 400 lines. Runtime
  telemetry is reused unchanged and labelled as such — no runtime
  behaviour is simulated.
- **Rationale:** Impact says what is reachable and risk says how the last
  observation reads; simulation answers the next question — what would
  reachability and risk look like if a proposed change were applied —
  without touching the twin. Canonical (UUID-level) comparison closes
  the alias hole where the same endpoint spelled two ways would slip
  past duplicate/contradiction checks. The fixed validation order keeps
  400/404/409 semantics reviewable and testable. Same trust posture as
  the impact/risk GETs (read-only, local-only, no new auth); the result
  is advisory and never authorization (T4/T5).
- **State:** Implemented and tested (34 pure unit tests + controller
  slice tests green without Docker; full HTTP integration suite added
  for Docker environments). Live verification pending (see
  `PROJECT_STATUS.md`).

## D27. Advisory policy evaluation over server-computed simulation — decided (2026-10-15)

- **Decision:** The first `policy` module is a read-only deterministic
  advisory evaluation (`POST /api/v1/twin/policy-evaluations`) over the
  existing simulation pipeline. The request body is the exact Phase 8
  proposal wire format (the shared `SimulationRequest` record — same
  fields, same validation, no alternate names): caller-supplied risk or
  delta content is never accepted. `PolicyService` re-runs the proposal
  through `SimulationService` (identical 400/404/409 semantics, identical
  roots/settings) and classifies the server-computed hypothetical result
  with the pure, immutable `policy-v1` ruleset — no traversal,
  classification, or validation duplication.
- **Taxonomy `policy-v1` (documented in `PolicyTaxonomy`, fixed order,
  every fired rule reported, final precedence `DENY > REVIEW >
  ALLOW`):** `HIGH_RISK_REMAINS` (any HIGH in after-scope → advisory
  DENY); `RESULT_TRUNCATED` (either scope truncated → advisory DENY,
  fail-closed on partial evidence); `RISK_INCREASED` (newly impacted
  risks appear, or a level moves upward with `LOW < MEDIUM < HIGH`
  including any arrival at HIGH; UNKNOWN moves not reaching HIGH are
  uncertainty, not increase → REVIEW); `UNKNOWN_RISK_PRESENT` (any
  UNKNOWN in after-scope → REVIEW, missing evidence is not safety);
  `EVIDENCE_UNCERTAIN` (any uncertain/stale after-scope evidence →
  REVIEW). An ALLOW outcome is still advisory and never an authorization
  grant; missing evidence, exceptions, invalid proposals, and incomplete
  analysis never silently produce ALLOW. The taxonomy version travels in
  the response.
- **Response:** advisory `decision`, `policyVersion`, unmistakable
  `ADVISORY POLICY EVALUATION — NOT AN AUTHORIZATION; NOT EXECUTED`
  wording, sorted fired rules with stable codes, a bounded simulation
  summary (graph counts, delta, limitations — not full scopes), and a
  note stating nothing was authorized, approved, executed, or persisted.
- **Rationale:** Risk says how the last observation reads and simulation
  says what would change; policy answers whether the hypothetical
  outcome is affirmable under explicit, reviewable rules — without
  touching the twin and without granting anything. Recomputing
  server-side closes the untrusted-evidence hole; immutable code rules
  (no persistence, no config surface) keep the evaluated policy exactly
  the shipped policy. Same trust posture as the intelligence endpoints
  (read-only, local-only, no new auth); advisory and never authorization
  (T4/T5), with T6 approval and T7 audit records still deferred.
- **State:** Implemented and tested (pure rule-unit + controller slice
  green without Docker; HTTP integration suite added for Docker
  environments). Live verification pending (see `PROJECT_STATUS.md`).

## D28. Control-plane JWT authentication and role authorization — decided (2026-10-15)

- **Decision:** The control plane is an OAuth2 resource server with JWT
  bearer tokens only (`spring-boot-starter-security` +
  `spring-boot-starter-oauth2-resource-server`; no Basic auth, no local
  password database, no home-grown tokens, no invented production IdP).
  Configuration lives under `systivex.security.jwt.*` (`enabled`,
  `issuer-uri`, `jwk-set-uri`, `accepted-audience`, `role-claim`,
  `clock-skew-seconds`, `allowed-roles`) with defaults `roles` /
  `systivex` / 60s skew / `TWIN_READ,TWIN_ANALYZE,TWIN_OPERATOR,TWIN_ADMIN`.
- **Fail-closed contract:** the trusted `issuer-uri` (HTTP(S) URL) is
  mandatory whenever JWT security is enabled; `enabled=false` is rejected
  at startup, so no security-off fallback exists. A custom `jwk-set-uri`
  only overrides key discovery — issuer, audience, signature, expiry, and
  `nbf` are always validated (with clock skew). Missing/invalid
  configuration fails fast instead of serving an unauthenticated API.
- **Role mapping (allowlist-only):** only `allowed-roles` values from the
  configured claim become `ROLE_*` authorities; malformed claims grant
  nothing and unknown values never grant access. `TWIN_ADMIN` inherits
  `TWIN_READ`, `TWIN_ANALYZE`, and `TWIN_OPERATOR`. Authenticated without
  the required role is 403; missing/invalid bearer credentials are 401
  with fixed safe messages (never tokens, headers, secrets, or claims).
- **Endpoint policy:** twin/impact/risk GETs need `TWIN_READ`; twin POSTs
  need `TWIN_ADMIN`; simulations/policy-evaluations need `TWIN_ANALYZE`;
  sync and observations need `TWIN_OPERATOR` with the existing
  `LoopbackGuard` still authoritative (a valid operator token never
  bypasses it). `GET /actuator/health` stays anonymous with details hidden
  (`show-details=never`); `/actuator/prometheus` needs `TWIN_READ`
  (monitoring access policy: read role only); all other actuator paths and
  everything else are denied. Stateless sessions, CSRF disabled for this
  token-only API, no CORS rules.
- **Out of scope:** approvals, execution endpoints, audit-ledger
  persistence, migrations, and target-service authentication are
  explicitly not introduced here. Simulation/policy stays read-only and
  advisory — an advisory ALLOW never becomes authorization to execute.
- **Verification:** automated JWT tests use locally generated test signing
  keys only (never production credentials). Live authentication against a
  genuine identity provider is NOT verified in this phase — no trusted
  issuer is configured in this environment — and must not be claimed.
- **Rationale:** Milestone 2C item 2 (broad authentication) has been open
  since Phase 4 with only a loopback bind + guard as a development
  boundary. JWT resource-server validation closes that gap for the control
  plane with the smallest reviewable mechanism, while target-service APIs
  stay local-only and unchanged.
- **State:** Implemented and tested (see `PROJECT_STATUS.md` Phase 10).
  Live IdP verification pending a configured trusted issuer.

## D29. Governed change proposals and tamper-evident audit — decided (2026-10-15)

- **Decision:** Phase 11 turns an investigated hypothetical change into a
  governed, auditable proposal with no execution. A new `proposal` package
  persists immutable proposal revisions and a hash-chained append-only
  audit log (Flyway `V2`, two tables + chain-head row, no new
  dependencies). Lifecycle is `DRAFT → ASSESSED → AWAITING_APPROVAL →
  APPROVED | REJECTED`, with `WITHDRAWN` (proposer cancels),
  `EXPIRED` (TTL lapsed), and `SUPERSEDED` (amendment forks a new
  revision) as terminal states; terminal revisions accept no further
  transitions (409).
- **Roles and separation of duties:** a distinct `TWIN_APPROVER` role is
  added to the `allowed-roles` allowlist. `TWIN_ADMIN` does **not**
  inherit it — a trusted issuer must explicitly grant it, so approval
  power is never ambient. Propose/assess/submit need `TWIN_ANALYZE`;
  approve/reject need `TWIN_APPROVER`; reads and audit verification need
  `TWIN_READ`. Separation of duties is a hard 403 on the `(issuer,
  subject)` identity pair: the approver must differ from the proposer on
  every revision, including for identities holding both roles. Approval
  and rejection both require a written reason (≤1024 chars).
- **Assessment binding and staleness:** assessment recomputes
  simulation + policy server-side from caller operations only (the Phase
  9 trust boundary is unchanged) and stores the canonical operations, a
  SHA-256 `operationsHash`, the bounded assessment summary
  (`policy-v1` decision + fired rules, `risk-v1` taxonomy +
  `snapshotObservedAt`), and a SHA-256 `twinFingerprint`. A policy
  `DENY` is never approvable in v1 — no override, no break-glass path.
  Materially stale assessments are rejected before approval (409):
  structural fingerprint mismatch, risk-level escalation or new
  staleness on recompute, or lapsed TTL (7-day default, 30-day hard
  maximum, lazy expiration only — no scheduler).
- **Revision model (issue A, resolved):** business revisions and JPA
  concurrency are separate fields. Each amendment inserts a new immutable
  `proposal_revision` row (`proposal_key` + 1-based `revision_number`,
  prior row → `SUPERSEDED` in the same transaction); the JPA `@Version`
  column (`jpa_version`) exists only for optimistic locking and is never
  serialized, hashed, or exposed. Every approval/audit event references
  `(proposal_key, revision_number, operationsHash, assessmentHash)`
  exactly. Prior revisions stay inspectable via
  `GET /proposals/{key}/revisions[/{n}]`.
- **Snapshot consistency (issue B, resolved):** assessment loads the
  twin entity/edge lists once (read-only `REPEATABLE_READ`) and computes
  simulation, policy, risk, and fingerprint over those exact in-memory
  lists, so fingerprint and assessment are coherent by construction. The
  fingerprint is content-derived — sorted entity id/type/name/refs/
  environment plus structural metadata **minus** the `runtime` subtree,
  plus sorted relationship keys — never `updated_at`. Content hashing
  (not writer instrumentation) is what covers every write path: CRUD
  inserts, sync structural updates, and relationship inserts all change
  content, while telemetry polls touch only `metadata.runtime` (and
  `updated_at`, which `entities.save` advances on every run) and therefore
  never invalidate an assessment. Risk freshness is rechecked separately
  at approval: recompute risk over current rows for the stored
  roots/settings and reject on any level increase, new `UNKNOWN`, new
  staleness, or runtime evidence older than the 30-minute taxonomy
  threshold. Residual accepted risks: change-then-revert (ABA) content
  equality, and a millisecond check-then-act window inside the approval
  transaction (row-locked, human-scale staleness dominates).
- **Audit integrity:** `audit_event` rows carry actor, action, outcome,
  correlation ID, timestamp, `(proposal_key, revision_number, hashes)`,
  and a SHA-256 `event_hash` chained via `prev_hash` against a
  `SELECT … FOR UPDATE`-guarded `audit_chain_head` row. Every lifecycle
  mutation and its audit event commit in one transaction (audit failure
  rolls back the mutation). There is no audit deletion and no automatic
  repair. The chain is tamper-**evident**, not tamper-proof: a database
  superuser can rewrite records, and the `verify` endpoint exists to
  detect exactly that (`OK` vs `BROKEN_AT_SEQ`).
- **Out of scope:** execution endpoints, controlled dispatch, approver
  quorum > 1, DENY override, break-glass, schedulers, brokers, workflow
  engines, WORM/offline export (runbook-only), and target-service
  authentication are explicitly not introduced here. `APPROVED` is a
  terminal record — acting on it stays a human step outside the system.
- **Verification:** migration-compat, transition-matrix, concurrency
  (parallel approves → exactly one `APPROVED`), role×endpoint matrix,
  SoD, expiry with injectable clock, chain-tamper detection, and
  transaction-rollback tests (see `PROJECT_STATUS.md` Phase 11). No live
  IdP or live Prometheus verification is claimed.
- **Rationale:** closes the governance gap between advisory investigation
  (Phases 5–9) and any future controlled execution, using the smallest
  reviewable persistence + cryptography increment while keeping the
  LLM permanently out of authorization and execution paths.
- **State:** Slices S1–S5 implemented with green tests (see
  `PROJECT_STATUS.md` Phase 11); expiry handling (S6) pending —
  nothing in S6 may be claimed as built until its slice lands.

## Supersession rule

New decisions amend this file with date and rationale; they never silently edit
history. If reality diverges from any entry, `PROJECT_STATUS.md` records the
discrepancy first.
