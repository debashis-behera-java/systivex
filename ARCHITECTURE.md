# Systivex Architecture

Status: **target architecture (planned) + current implementation status (verified 2026-09-28)**.
Nothing below beyond "Current implementation" should be read as implemented.

## 1. High-level target architecture

```text
                        ┌─────────────────────────────────┐
                        │   SYSTIVEX CONTROL PLANE        │
                        │   (modular monolith, Spring)    │
                        │                                 │
  code/arch/data ──────▶│  System Twin                    │
  runtime signals ─────▶│  (code+arch+data+behavior model)│
                        │         │                       │
  change proposal ─────▶│  Change Intelligence:           │
                        │  impact / simulation / risk     │
                        │         │                       │
                        │  Policy + Approval Gate         │◀── human / policy approval
                        │         │                       │
                        │  Controlled Execution +         │
                        │  Verification                   │──▶ target environment
                        └─────────────────────────────────┘
                                           │
                                           ▼
                        ┌─────────────────────────────────┐
                        │   TARGET SOFTWARE ENVIRONMENT   │
                        │   (future microservices under   │
                        │    observation / management)    │
                        └─────────────────────────────────┘
```

Target flow: **observe → model (twin) → propose → simulate → assess risk →
authorize → execute under control → verify**.

## 2. Control-plane vs. target-environment separation

| Concern | Systivex control plane | Target environment |
|---|---|---|
| Role | Thinks, models, gates, authorizes, verifies | Is observed and acted upon |
| Shape | **Modular monolith** (Spring Boot) | **Microservices** (4 Spring Boot apps, Phase 2A) |
| Reasoning | Agent + twin-grounded analysis (planned) | Application business logic |
| Authority | Grants or denies consequential actions | Carries out only authorized actions |
| Trust | Authoritative for decisions and audit | Untrusted until verified |

This separation is deliberate: the thing that decides must not be the thing
being changed, and the thing being changed must never self-authorize.

## 3. Modular-monolith control plane (target)

The control plane is a single Spring Boot deployment with strict module boundaries.
The `twin` module exists in outline (`model`, `repository`, `service`, `api`
packages) plus the Phase 4 `observation` package (scanner + sync, repository
connector only), the Phase 5 `intelligence` package (read-only impact
analysis only), and the Phase 6 `telemetry` package (pull-based runtime
annotation only); the remaining target modules are **not yet created** — no placeholder
packages exist for them by decision:

- `twin` — System Twin model and connectors (**model + persistence + minimal
  CRUD API exist**; repository connector exists since Phase 4, health/counter
  runtime annotation exists since Phase 6, trace/log presence still planned)
- `intelligence` — change impact and risk (**deterministic read-only impact traversal
  exists since Phase 5**: `GET /api/v1/twin/impact`; **ordinal risk
  classification exists since Phase 7**: `GET /api/v1/twin/risk` over impact
  paths plus runtime metadata; simulation still planned)
- `policy` — policy evaluation and approval workflow (planned)
- `execution` — controlled, auditable action dispatch (planned)
- `verification` — post-change checks against twin + runtime (planned)
- `observation` — code/arch/runtime ingest (**repository connector exists
  since Phase 4**: read-only scan of `backend/target-services/` + explicit
  sync trigger; telemetry ingest still planned)
- `api` — REST + SSE surface (initial twin CRUD exists; only Actuator health
  existed before Phase 1)

Module rules (binding for future work): modules communicate through explicit
APIs; no cross-module persistence access; every consequential action crosses
the policy/execution boundary (see §6).

## 4. Target microservices (Phase 2B persistent runtime; observation planned)

Four independently deployable services under `backend/target-services/` form the
first **target environment** — the kind of system the twin will eventually model
and the control plane will act upon. They are ordinary business services with no
Systivex awareness in them:

```text
client ──▶ gateway-service (:8081, POST /api/v1/checkout)
               │ HTTP (RestClient, URL from config)
               ▼
           order-service (:8082, POST /api/v1/orders) ──▶ order_db (orders)
               ├──▶ inventory-service (:8084, POST /internal/v1/inventory/reserve) ──▶ inventory_db
               │         (inventory_items + inventory_reservations)
               └──▶ payment-service (:8083, POST /internal/v1/payments/authorize) ──▶ payment_db
                         (payments)
```

Database ownership (binding, see `DECISIONS.md` D17):

| Service | Database / user | Tables | Reads/writes other DBs? |
|---|---|---|---|
| order-service | `order_db` / `order_app` | `orders` (PENDING → CONFIRMED/FAILED) | Never |
| payment-service | `payment_db` / `payment_app` | `payments` (AUTHORIZED/DECLINED, both recorded) | Never |
| inventory-service | `inventory_db` / `inventory_app` | `inventory_items`, `inventory_reservations` | Never |
| gateway-service | None (stateless edge) | — | N/A |

The control-plane database (`systivex` / `systivex_app`) is separate and
shares nothing with these three. Each service migrates its own schema with its
own Flyway history (`V1` schema everywhere, plus a `V2` dev seed of
`SKU-1001 × 10` in inventory only); Hibernate validates (`ddl-auto=validate`)
and never migrates.

- Each has its own pom, Maven wrapper, application class, and Actuator health.
- Downstream URLs and datasource credentials come from configuration (env vars
  with localhost defaults), never from source. Fixed dev ports (8081–8084);
  control plane stays on 8080.
- Checkout persists at every step: PENDING row first, then reserve, then
  authorize, then CONFIRMED — or FAILED when any downstream step fails. Failed
  attempts are recorded, never dropped and never falsely CONFIRMED.
- Consistency is per-database, not global: the three services commit in their
  own local transactions with no coordinator. A FAILED order can coexist with
  a standing inventory reservation; a crash between the PENDING insert and the
  final update leaves a PENDING row. This is documented behavior (the honest
  record a future reconciliation story needs), not atomicity.
- Inventory concurrency is handled locally: the reserve path takes a
  row-level write lock (plus a `@Version` column) so concurrent reserves for
  the same product serialize instead of overselling. No Redis, no broker.
- Payment still declines amounts above 5000.00 by deterministic rule (no
  provider integration); declines are now persisted as DECLINED rows.
- Failures stay controlled: 400 validation, 422 business rejection, 503 order
  level (including order-storage outage), 502 gateway level, sanitized bodies
  throughout — no SQL text or stack traces on the wire.
- No Kubernetes, no message broker, no service mesh at this stage.
- The control plane does not call, scrape, or model these services yet. They
  remain strictly outside the control-plane trust boundary: untrusted until
  verified, never self-authorizing, carrying only their own local logic.

## 4a. Observability instrumentation (Phase 3A; collection verified, consumption planned)

Every service (four target services plus the control plane) emits
OpenTelemetry traces and Prometheus metrics from the same two
Boot-managed dependencies. Collection is local only:

 ```text
 gateway/order/payment/inventory (+ control plane)
   │  OTLP traces + logs (:4318)   │  /actuator/prometheus scrape
   ▼                               ▼
 ┌─────────────────────────────────────────────────┐
 │  OTel Collector (local, compose)                │
 │  traces → debug exporter (collector stdout)     │
 │  metrics → Prometheus receiver → :8889          │
 │  logs → OTLP/HTTP exporter → Loki :3100         │  (Phase 3B-2)
 └─────────────────────────────────────────────────┘
          │                        │  Prometheus scrape (:8889 only)
          ▼                        ▼
 ┌──────────────────┐    ┌──────────────────┐
 │  Loki :3100       │    │  Prometheus :9090 │  (Phase 3B-1)
 │  (Phase 3B-2)    │    └──────────────────┘
 └──────────────────┘              │  PromQL
          │                        ▼
          │              ┌──────────────────┐
          └─────────────▶│  Grafana :3000    │  (provisioned)
                         │  Metrics dashboard│  (Phase 3B-1)
                         │  Explore logs     │  (Phase 3B-2)
                         └──────────────────┘
 ```

Phase 3B-1 adds visualization without touching the collection path:
Prometheus scrapes only the collector's `:8889` exposition (never the
Java services directly — no duplicate collection). The collector stamps
all app metrics with its scrape-job resource (`service_name="systivex"`),
so per-service identity on this path comes from the receiver-kept
`server_port` label, mapped once in `prometheus.yml` to a `service`
label (control-plane/gateway-service/order-service/payment-service/
inventory-service). The single `Systivex Metrics` dashboard (provisioned
from `observability/grafana/`, no browser setup) shows services
reporting, request rate, requests by outcome, average latency, JVM heap,
process CPU, and the checkout/inventory/payment business counters — all
low-cardinality labels only; OTel histograms arrive without buckets on
this path, so latency is a sum/count average, not a histogram quantile.

- Tracing: automatic server/client HTTP spans everywhere; trace context
  (W3C `traceparent`) propagates gateway → order → inventory + payment —
  verified as one trace ID across all four services in the collector log.
  Three custom business spans mark what automatic instrumentation cannot:
  `checkout` (order), `inventory.reservation`, `payment.authorization`.
   Recorded observation errors are sanitized per deployable
   (`ObservationErrorSanitizer` in its own handler group ordered before
   Boot's tracing handler group, so the tracing handler records the
   sanitized error): type name + HTTP status only, no response
   bodies or business messages — see `DECISIONS.md` D19, `THREAT_MODEL.md`
   §3a.
- Metrics: standard HTTP server/client, JVM, and process families plus six
  tag-free business counters (`checkout.success/failure`,
  `inventory.reservation.success/rejected`,
  `payment.authorization.success/declined`).
- Logs: console output is unchanged (`traceId`/`spanId` correlation;
  outcome + IDs, never payloads or secrets), and the same records now also
  ship over OTLP to the collector and into Loki (Phase 3B-2, see below).
  Boot wires the OTLP log exporter but installs no logging bridge, so each
  deployable adds the smallest one: the OTel Logback appender (version
  paired with Boot's managed OTel SDK, see `DECISIONS.md` D21) declared in
  `logback-spring.xml` plus a tiny installer bean. Loki indexes
  `service.name` only; trace/span IDs travel as structured metadata —
  searchable (`| traceId="<hex>"`) but never stream labels, so no
  per-trace/per-request cardinality. Grafana Explore is the log UI; no log
  dashboard, no Tempo.
- Sampling is 1.0 (local development only). The control plane emits but
  consumes nothing — twin ingestion of telemetry is future work (D18).

## 5. System Twin concept (persistence exists; reasoning still planned)

The System Twin is the planned authoritative model of a target system, joining:

- **code** (structure, dependencies),
- **architecture** (services, boundaries, contracts),
- **data** (stores, flows, schemas),
- **runtime behavior** (health, metrics, traces, events).

Agent reasoning must be grounded in the twin and in live backend state —
never in LLM output alone. What exists today is the persistence footing:
`system_entity` / `system_relationship` tables (Flyway V1), JPA mappings kept
in lockstep by `ddl-auto=validate`, and CRUD behind DTOs — plus, since
Phase 4, the first real content: a read-only repository connector that
models the four target services, their databases/tables, APIs, and
dependencies as connector-owned rows (stable `target-*` external refs,
provenance in metadata) — plus, since Phase 5, the first reasoning over
that content: a read-only deterministic impact traversal (`intelligence`
package, `GET /api/v1/twin/impact`) that reports potentially affected
components with ordered evidence paths over persisted edges — plus, since
Phase 6, validated runtime annotation: health plus the six allowlisted
business counters pulled from the loopback Actuator surface into a
namespaced `runtime` metadata key (`telemetry` package,
`POST /api/v1/twin/observations`; trace/log presence deliberately absent).
No twin package beyond `twin` + `observation` + `intelligence` +
`telemetry`, no simulation/risk/policy/execution yet.

## 6. Trust boundaries

### 6a. Agent / control boundary (planned)

- The agent proposes; the control plane disposes.
- The agent has **no direct access** to production actions, credentials,
  or the target environment.
- Every tool call with side effects is mediated, validated, and logged
  by the control plane.

### 6b. Policy / risk / execution boundary (planned)

```text
agent proposal → risk assessment → policy evaluation → (approval if high-risk)
→ authorized execution → audit record → verification
```

- Consequential actions require **control-plane authorization**.
- High-risk actions additionally require **explicit human/policy approval**.
- All consequential actions must produce an **auditable record**
  (who/what/policy-decision/result).

See `THREAT_MODEL.md` for the principles these boundaries enforce.

## 7. API shape (initial REST exists)

- **REST** for twin queries, change proposals, approvals, execution records.
  Implemented so far: twin entity/relationship CRUD under `/api/v1/twin`
  (no filtering/pagination yet) plus read-only impact analysis
  (`GET /api/v1/twin/impact`: 1–10 starts by UUID or external ref,
  `maxDepth` 1–10 default 6, `includeTypes` default
  SERVICE/API/DATABASE/TABLE; deterministic BFS with FORWARD/REVERSE
  evidence paths, per-path revisit cycle guard, 500-node / 2000-path caps,
  400 on invalid input, 404 on unknown identifiers) plus runtime
  observation (`POST /api/v1/twin/observations`: `{}` default, loopback
  only with 403 off loopback, health + six allowlisted counters into the
  namespaced `runtime` metadata key, bounded receipt, 400/404/409
  fail-closed with full rollback);
  proposals/approvals/records are still planned.
  Change-risk assessment (`GET /api/v1/twin/risk`: same `from`/`maxDepth`/
  `includeTypes` conventions as impact; ordinal `LOW`/`MEDIUM`/`HIGH`/
  `UNKNOWN` levels with sorted reasons and `uncertain`/`stale` flags over
  impact depth, path fan-out, service health, cumulative failure counters,
  and 30-minute freshness; read-only, advisory, never authorization —
  see `DECISIONS.md` D25);
- **SSE** for streaming long-running analysis/simulation progress (planned).
- No WebSockets at this stage (see `DECISIONS.md`).

## 8. Current implementation status (verified 2026-09-30)

Implemented:

- `backend/`: Spring Boot 4.1.1, Java 21 (`release 21`), Maven build.
- Dependencies: `webmvc`, `validation`, `actuator`, `data-jpa`,
  `spring-boot-starter-flyway` + `flyway-database-postgresql`, `postgresql`
  driver, `spring-boot-starter-opentelemetry`,
  `micrometer-registry-prometheus` (+ test: `webmvc-test`,
  `restclient-test`, `testcontainers-postgresql`). Nothing else.
- `twin` module: `SystemEntity` / `SystemRelationship` entities (UUID keys,
  closed enum types, JSONB metadata via native Hibernate mapping), two
  repositories, a small transactional service, DTO-based REST controller,
  and a narrow exception handler (400/404/409).
- Persistence: PostgreSQL 18, Flyway `V1__create_system_twin.sql` runs at
  startup; `spring.jpa.hibernate.ddl-auto=validate`, Hikari defaults.
- Local config stays out of Git: live `application.properties` ignored, only
  `application.example.properties` (password via `SYSTIVEX_DB_PASSWORD`) tracked.
- Verified: `./mvnw clean test` → 29 tests, 0 failures (8 service unit +
  3 migration + 6 repository + 11 API + 1 contextLoads, Testcontainers PG 18);
  `./mvnw package -DskipTests` → executable jar; local run against PostgreSQL →
  Flyway migrates V1, `GET /actuator/health` → `{"status":"UP"}`,
   entity/relationship REST cycle + 400/404 paths exercised.
- Target environment persistence (Phase 2B, see §4): the four target services
  now run against service-owned PostgreSQL databases with per-service Flyway
  histories, validated JPA mappings, persistent orders / payments /
  inventory+reservations, and a checkout flow backed by real databases with a
  documented per-database consistency model.
- Observability (Phase 3A, see §4a): OTLP + Prometheus instrumentation in all
  five deployables, error redaction at the observation boundary, local
  collector pipeline (`observability/`), distributed trace across the
  checkout chain verified in the collector log, business counters and
  correlated logs verified live and in tests (85 tests total:
  control plane 31, gateway 8, order 18, payment 12, inventory 16).
- Twin observation (Phase 4): read-only repository connector + explicit
  sync trigger in the control plane (`observation` package); 15 entities /
  18 edges modelling the target environment, verified in tests (control
  plane suite now 51: 31 pre-existing + 20 new).
- Change-impact analysis (Phase 5): read-only deterministic BFS over the
  persisted twin graph in the control plane (`intelligence` package);
  `GET /api/v1/twin/impact` returns impacted services/APIs/databases/
  tables with ordered FORWARD/REVERSE evidence paths and per-step
  explanations (see `DECISIONS.md` D23). No migration, no new
  dependencies; verified in tests (control plane suite now 88: 51
  pre-existing + 37 new).
- Runtime observation (Phase 6): pull-based health/counter annotation in
  the control plane (`telemetry` package);
  `POST /api/v1/twin/observations` pulls loopback Actuator health plus the
  six allowlisted business counters into the namespaced `runtime`
  metadata key of matched `target-service:*` rows (one transaction,
  fail-closed, bounded receipt; see `DECISIONS.md` D24). No migration, no
  new dependencies; trace/log presence deliberately absent.
- Change-risk assessment (Phase 7): ordinal classification in the control
  plane (`intelligence` package); `GET /api/v1/twin/risk` joins the Phase 5
  impacted set with Phase 6 runtime facts (depth base, 5+ path fan-out,
  health, cumulative failures, 30-minute freshness; sorted reasons,
  uncertainty flags; see `DECISIONS.md` D25). No traversal duplication, no
  migration, no new dependencies, no writes.

Why PostgreSQL + Flyway + a relational graph at this stage: the records the
control plane will eventually authorize against (twin state, proposals,
decisions, executions) need integrity guarantees and a readable audit trail
more than they need graph-traversal speed. Two tables with FK/unique/check
constraints cover Phase 1; a graph store would add operational weight with no
consumer yet (see `DECISIONS.md` D10).

Intentionally absent (not bugs — deferred by decision):

- Twin trace/log presence signals, simulation, policy,
  execution, verification modules (the repository connector is Phase 4,
  above; read-only impact traversal is Phase 5, above; health/counter
  annotation is Phase 6, above; ordinal risk classification is Phase 7,
  above).
- Redis, Spring AI / Ollama, security, frontend.
- Grafana log dashboards, Tempo trace UI, unified trace/log UI, anomaly
  detection, any agent use of telemetry (later 3B slices and later).
- Docker packaging of the Java services, Kafka / RabbitMQ / Kubernetes /
  WebSockets / vector DB / MCP / multi-agent / cloud.
- Target-service cross-database atomicity (rejected in D17; reconciliation is
  future work, not missing plumbing).

Next architectural step (see `PROJECT_STATUS.md`): policy evaluation over
the versioned risk taxonomy — still without agents or execution, and never
exposed beyond the local machine before the deferred authentication story
lands.
