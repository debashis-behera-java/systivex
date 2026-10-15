# Systivex — Agentic Software System Twin & Change Intelligence Platform

> Systivex is an **agentic software-system twin and engineering safety platform** connecting
> code, architecture, data, runtime behavior, simulation, policy-controlled execution, and verification.

Systivex builds a living **System Twin** of a software system — its code, architecture,
data flows, and runtime behavior — so that proposed changes can be **simulated, risk-assessed,
policy-gated, executed under control, and verified** before and after they touch real systems.

Systivex is **not**:

- an AI code reviewer,
- an SRE chatbot,
- a generic LLM wrapper,
- a chat assistant over logs.

Those are point tools. Systivex is a **control plane for safe engineering action**:
it grounds agent reasoning in an authoritative model of the system and only permits
consequential actions through explicit control-plane authorization.

## Current state (honest snapshot)

**Implemented** (verified 2026-09-30):

- Spring Boot 4.1.1 backend (`backend/`) on Java 21 / Maven.
- Application starts; `GET /actuator/health` returns `{"status":"UP"}`.
- PostgreSQL persistence: Spring Data JPA + Hibernate, Flyway-owned schema
  (`ddl-auto=validate` — Hibernate never modifies the schema).
- Initial twin persistence model: `SystemEntity` (7 closed types) and directed
  `SystemRelationship` (8 closed types), JSONB metadata, UUID keys,
  FK/unique/check constraints, `ON DELETE CASCADE`.
- Minimal twin REST surface (`/api/v1/twin/entities`, `/api/v1/twin/relationships`)
  with DTOs, Bean Validation, and stable 400/404/409 error responses.
- 29 control-plane tests green: service unit tests plus Testcontainers-backed
  migration, repository, and API integration tests (now 31 with Phase 3A
  observability tests).
- Phase 2B target environment (`backend/target-services/`): four independently
  runnable Spring Boot services — gateway (8081) → order (8082) → inventory
  (8084) + payment (8083) — with real HTTP calls between processes and
  service-owned PostgreSQL databases (`order_db`, `payment_db`,
  `inventory_db`; gateway stateless; control-plane `systivex` separate).
  Persistent orders (PENDING → CONFIRMED/FAILED), recorded payment
  authorizations (AUTHORIZED/DECLINED), and real stock with locked
  reservations (seeded `SKU-1001 × 10` for local demos). Checkout across the
  three databases is deliberately not atomic — documented, not hidden.
  Controlled 400/422/502/503 error handling with nothing internal on the
  wire, and service tests green (Testcontainers-backed).
- Phase 3A observability (verified 2026-09-30): Boot-managed OpenTelemetry +
  Prometheus instrumentation in all five deployables; a local collector
  (`observability/`, Compose — telemetry infrastructure only, services still
  run locally) receives OTLP traces and re-exposes scraped metrics. One
  checkout produces one distributed trace across gateway → order →
  inventory + payment (verified in the collector log), with three business
  spans (`checkout`, `inventory.reservation`, `payment.authorization`),
  tag-free business counters, and trace-correlated console logs. Success,
  rejection, decline, and downstream-outage paths all emit telemetry. The
   control plane emits but consumes nothing yet — no twin ingestion, no
   dashboards, no anomaly detection, no agent use.
- Phase 3B-1 metrics visualization (verified 2026-10-06): Prometheus
  (`observability/`, Compose) scrapes the collector's `:8889` exposition —
  the single metrics path, no direct app scrapes — and Grafana (provisioned
  datasource + one `Systivex Metrics` dashboard, no manual setup) shows
  request rate, errors by outcome, average latency, JVM heap/CPU, service
  reporting, and checkout/inventory/payment business counters. Checkout and
  failure scenarios move the dashboard metrics live.
- Phase 3B-2 log exploration (implemented 2026-10-07, live verification
  pending service restarts): the same console records now also ship over
  OTLP through the existing collector into Loki (`observability/`,
  Compose — single binary, local filesystem, no auth), searchable in
   Grafana Explore via a provisioned Loki datasource (no new dashboard).
   Per-service Logback bridge (`logback-spring.xml` +
   `OtelLogAppenderInstaller`; Boot alone ships nothing — see
   `DECISIONS.md` D21). Only `service.name` is a Loki index label;
   trace/span IDs stay searchable structured metadata, never stream
   labels. Outcome + IDs only, same privacy guarantees as Phase 3A.
- Phase 4 twin observation (implemented 2026-10-09, local live boot
  pending): a read-only repository connector (`backend/.../observation/`)
  scans `backend/target-services/` configs, controller routes, and Flyway
  migrations into the twin — 4 services, 3 databases, 4 tables, 4 APIs
  (15 entities, 18 CALLS/DEPENDS_ON/CONTAINS/WRITES/EXPOSES edges) via an
  explicit `POST /api/v1/twin/sync` trigger. Idempotent, transactional,
  connector-owned rows only (manual rows preserved, conflicts fail 409).
  Localhost-only: `server.address=127.0.0.1` bind plus a loopback-peer
  guard (403 off loopback, no header trust). 51 tests green (31 existing
  + 20 new). The control plane still consumes no telemetry and no agent
  exists — see `DECISIONS.md` D22.
- Phase 5 change-impact analysis (implemented 2026-10-10, live verification
  pending): the first real `intelligence` module (`backend/.../intelligence/`)
  answers read-only `GET /api/v1/twin/impact?from=<uuid|externalRef>&...`
  (1–10 starts, `maxDepth` 1–10 default 6, `includeTypes` default
  SERVICE/API/DATABASE/TABLE) with deterministic BFS over the persisted
  graph — impacted services/APIs/databases/tables plus ordered evidence
  paths recording the FORWARD/REVERSE direction of every step and a
  per-step explanation (downstream dependencies labelled as reachability,
  never confirmed breakage; unknown relationship types transparently
  generic). Cycles terminate via a per-path revisit guard and are reported;
  caps (500 nodes / 2000 paths) truncate explicitly. One read-only
  transaction, no migration, no new dependencies. 88 tests green (51
  existing + 37 new). Runtime note: the tracked example config binds
  `127.0.0.1`, but a pre-existing live `application.properties` does not
  inherit it — run with `SERVER_ADDRESS=127.0.0.1` (or add the bind to the
  live file) to keep the local boundary; see `DECISIONS.md` D23 and
   `THREAT_MODEL.md` §3a.
- Phase 6 runtime observation (implemented 2026-10-13, live verification
  pending): the first `telemetry` module (`backend/.../telemetry/`)
  pulls loopback Actuator health plus the six allowlisted tag-free
  business counters into a namespaced `runtime` metadata key on the
  matched `target-service:*` rows via an explicit
  `POST /api/v1/twin/observations` trigger (`{}` default, no caller URLs,
  403 off loopback). Dependency-free deterministic parsers fail closed on
  unknown families, labels, oversize/malformed payloads, and
  privacy-sensitive content; absent counters and unreachable services are
  recorded as limitations, never inferred; repeat polls report
  updated-vs-unchanged facts while the observation timestamp always
  advances. One transaction per run, no migration, no new dependencies.
  Trace/log presence deliberately not collected (no privacy-safe source).
  41 new tests green. See `DECISIONS.md` D24.
- Phase 7 risk assessment (implemented 2026-10-14, live verification
  pending): ordinal classification in `backend/.../intelligence/` joining
  the Phase 5 impacted set with Phase 6 runtime facts via
  `GET /api/v1/twin/risk` (same validation conventions; taxonomy `risk-v1`:
  depth base, 5+ path fan-out, health, cumulative failure counters, 30m
  freshness; sorted reasons with `uncertain`/`stale` flags; read-only,
  advisory, never authorization). No traversal duplication, no migration,
  no new dependencies. 36 new tests green. See `DECISIONS.md` D25.
- Phase 8 change simulation (implemented 2026-10-15, live verification
  pending): hypothetical before/after comparison in
  `backend/.../intelligence/` via `POST /api/v1/twin/simulations` —
  validated change proposals (`REMOVE_ENTITY` with incident-edge cascade,
  `ADD_RELATIONSHIP`, `REMOVE_RELATIONSHIP`; fixed 400/404/409 order)
  replayed through the Phase 5 traversal and Phase 7 classification over
  identical roots and settings, returning deterministic deltas, explicit
  truncation/uncertainty limitations, and `simulated: true` with the
  `SIMULATED — NOT EXECUTED` advisory. Nothing is persisted or executed.
  No algorithm duplication, no migration, no new dependencies. 34 new
  unit tests green (+ controller slice; HTTP integration suite added for
  Docker environments). See `DECISIONS.md` D26.
- Phase 9 advisory policy evaluation (implemented 2026-10-15, live
  verification pending): deterministic ALLOW/REVIEW/DENY in the new
  `backend/.../policy/` package via `POST /api/v1/twin/policy-evaluations`
  — accepts the exact Phase 8 proposal format, re-runs the simulation
  server-side (caller-supplied risk never trusted), and classifies the
  computed hypothetical result with immutable `policy-v1` rules (HIGH or
  truncation → advisory DENY; increase/unknown/uncertain → REVIEW; else
  advisory ALLOW, never authorization). Bounded summary with fired
  rules, stable codes, and explicit non-authorization wording. Nothing
  persisted, approved, or executed. No new dependencies. 17 new unit
  tests green (+ controller slice; HTTP integration suite added for
  Docker environments). See `DECISIONS.md` D27.
- Phase 10 control-plane authentication & authorization (implemented
  2026-10-15, live IdP verification pending — no trusted issuer
  configured): OAuth2 resource-server JWT bearer authentication with
  fail-closed `systivex.security.jwt.*` configuration (trusted issuer
  mandatory, no security-off mode, audience/signature/expiry/`nbf`
  always validated) and allowlist-only role mapping (`TWIN_READ`,
  `TWIN_ANALYZE`, `TWIN_OPERATOR`, `TWIN_ADMIN`; admin inherits the
  three lesser roles; malformed/unknown claims grant nothing).
  Twin/impact/risk GETs need `TWIN_READ`, twin POSTs `TWIN_ADMIN`,
  simulations/policy-evaluations `TWIN_ANALYZE`, sync/observations
  `TWIN_OPERATOR` behind the still-authoritative loopback guards;
  `/actuator/health` stays anonymous (status only),
  `/actuator/prometheus` needs `TWIN_READ`, everything else denied.
  401 on missing/invalid credentials, 403 on insufficient authority,
  safe error bodies, no token logging. 31 new automated tests green
  (test signing keys only — not live identity verification). No
  approvals, execution, audit persistence, migrations, or
  target-service auth added. See `DECISIONS.md` D28.

**Not yet implemented** (planned architecture only):

- Twin trace/log presence signals (health/counter annotation is Phase 6),
  Git integration
- Agent orchestration, tooling, evidence model
- Approval workflows and controlled execution
  (impact paths are Phase 5, risk classification Phase 7, hypothetical
  simulation Phase 8, advisory policy evaluation Phase 9 — all
  read-only and advisory), verification
- Cross-database checkout atomicity/reconciliation, cache (Redis), Spring AI / Ollama, frontend
- Trace exploration UI (Tempo), unified trace/log UI, anomaly detection,
  any agent or automated use of telemetry (Loki log exploration is done;
  Tempo is a later slice)

See [`PROJECT_STATUS.md`](PROJECT_STATUS.md) for the phase record,
[`ARCHITECTURE.md`](ARCHITECTURE.md) for target vs. current architecture,
and [`DEVELOPMENT.md`](DEVELOPMENT.md) for how to build and run what exists today.

## Repository layout

```text
Systivex/
├── backend/                 # IMPLEMENTED: Spring Boot control plane + twin persistence
│   ├── pom.xml              # Spring Boot 4.1.1, Java 21
│   ├── mvnw / mvnw.cmd / .mvn/
│   └── src/
│       ├── main/java/com/soubhagya/systivex/
│       │   ├── SystivexApplication.java
│       │   ├── twin/{model,repository,service,api}/
│       │   ├── observation/           # Phase 4: read-only repo connector + POST /api/v1/twin/sync
│       │   ├── intelligence/          # Phase 5: impact BFS + GET /api/v1/twin/impact; Phase 7: risk + GET /api/v1/twin/risk; Phase 8: simulation + POST /api/v1/twin/simulations
│       │   ├── policy/                # Phase 9: advisory ALLOW/REVIEW/DENY + POST /api/v1/twin/policy-evaluations
│       │   ├── security/              # Phase 10: JWT resource server, role policy, fail-closed config
│       │   └── telemetry/             # Phase 6: pull-based runtime annotation + POST /api/v1/twin/observations
│       ├── main/resources/application.properties          # LOCAL ONLY, git-ignored
│       ├── main/resources/application.example.properties  # tracked safe template
│       ├── main/resources/db/migration/V1__create_system_twin.sql
│       └── test/java/com/soubhagya/systivex/  # unit + Testcontainers integration tests
│   └── target-services/         # IMPLEMENTED (Phase 2B): persistent target runtime Systivex will observe
│       ├── create-target-databases.sql  # LOCAL-ONLY bootstrap: 3 dbs + users, no passwords inside
│       ├── gateway-service/     # :8081, forwards POST /api/v1/checkout to order (stateless)
│       ├── order-service/       # :8082, persisted checkout orchestration (order_db)
│       ├── payment-service/     # :8083, recorded authorizations (payment_db)
│       └── inventory-service/   # :8084, real stock + reservations (inventory_db, V2 dev seed)
│           # each: own pom/mvnw/app class + Flyway history; live application.properties git-ignored,
│           # application.example.properties tracked; datasource passwords via env only
├── observability/           # IMPLEMENTED (Phase 3A + 3B-1 + 3B-2): local telemetry ONLY
│   ├── docker-compose.yml   # Collector + Prometheus (:9090) + Loki (:3100) + Grafana (:3000); Java NOT containerized
│   ├── otel-collector.yml   # OTLP traces -> debug log; Prometheus scrape -> :8889; OTLP logs -> Loki
│   ├── prometheus.yml       # scrapes collector :8889 only (single metrics path)
│   ├── loki-config.yml      # single-binary Loki, filesystem storage, low-cardinality label policy (service.name only)
│   └── grafana/             # provisioned Prometheus + Loki datasources + Systivex Metrics dashboard (no clicks needed)
├── README.md                # this file
├── ARCHITECTURE.md          # target architecture + current status
├── DEVELOPMENT.md           # build / test / run instructions
├── PROJECT_STATUS.md        # phase record
├── DECISIONS.md             # architectural decisions
├── THREAT_MODEL.md          # trust principles
└── .gitignore               # root ignores
```

No `frontend/`, no service containers — intentionally absent
(see `DECISIONS.md`: no premature infrastructure). The only Compose file is
`observability/docker-compose.yml`, scoped to the telemetry collector.
Database configuration exists
but is local-only: `application.properties` is git-ignored, only the safe
`application.example.properties` template is tracked.

## Quick start (current phase)

Prerequisite: **JDK 21** (`JAVA_HOME` must point to a JDK 21 installation).

```powershell
cd backend
$env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.10"
$env:Path="C:\Program Files\Java\jdk-21.0.10\bin;" + $env:Path
./mvnw test                      # needs Docker (Testcontainers PostgreSQL)
$env:SYSTIVEX_DB_PASSWORD="<local systivex_app password>"
./mvnw spring-boot:run           # needs local PostgreSQL, see DEVELOPMENT.md
# then: GET http://localhost:8080/actuator/health -> {"status":"UP"}
```

(Outside VS Code the `PATH` prepend matters too; inside a new VS Code
integrated terminal both variables are set automatically.)

Full instructions: [`DEVELOPMENT.md`](DEVELOPMENT.md).

## Documentation map

| Document | Purpose |
|---|---|
| `README.md` | Vision + honest current-state snapshot (this file) |
| `ARCHITECTURE.md` | Target architecture, control/target separation, trust boundaries, current status |
| `DEVELOPMENT.md` | Local build/test/run, prerequisites |
| `PROJECT_STATUS.md` | What is done, what is deliberately absent, next milestone |
| `DECISIONS.md` | Initial architectural decisions and rationale |
| `THREAT_MODEL.md` | Initial trust principles (LLM untrusted, authorization, audit) |

## Project rules (binding)

- Java 21, Spring Boot 4.1.1, Maven.
- Modular monolith for the Systivex **control plane**; microservices are reserved
  for the future **target software environment**, not for Systivex itself at this stage.
- PostgreSQL as primary persistence (Flyway-owned schema, Hibernate validates only);
  Redis only when justified; Spring AI + Ollama later.
- REST + SSE initially.
- Do not introduce Kafka, RabbitMQ, Kubernetes, WebSockets, vector databases, MCP,
  multi-agent architecture, or cloud infrastructure at this stage.
