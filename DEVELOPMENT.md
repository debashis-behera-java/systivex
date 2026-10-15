# Systivex Development Guide

Scope: **control plane (Phases 0–1) + target services (Phases 2A–2B) +
telemetry pipeline (Phase 3A) + twin observation (Phase 4)**
(verified 2026-10-09). §§1–7 cover the control plane in `backend/`;
§8 covers the target services in `backend/target-services/`; §9 covers the
local telemetry infrastructure in `observability/`; §10 covers twin
synchronization.
No cache, AI runtime, or frontend — none are required yet.

## 1. Prerequisites

| Requirement | Version / note | Status |
|---|---|---|
| JDK | **21** (e.g. `21.0.10 LTS`) | Required. Project compiles with `release 21`. |
| Maven | Wrapper-provided (3.9.16 via `./mvnw`) | No separate install needed |
| OS / shell | Windows 10/11 + PowerShell 5.1 (verified); any OS with JDK 21 works | — |
| PostgreSQL | 18.x locally (`systivex` + 3 target dbs, see §8a) | Required to run; tests use Testcontainers instead |
| Docker | Docker Desktop (daemon running) | Required for `./mvnw test` (Testcontainers spins up PG 18) and for the Phase 3A telemetry collector (`observability/`) |
| Redis | **Not required** in this phase | Deferred, only when justified |
| Ollama / Spring AI | **Not required** in this phase | Deferred |
| Node / React | **Not required** (no frontend yet) | Deferred |
| K8s / brokers | **Not required** (services run locally, not containerized) | Deferred |

> Environment setup (2026-09-28, updated): the machine default `JAVA_HOME`/PATH
> points at JDK 17, which **cannot** build this project. This is solved
> workspace-locally — no global change, Java 17 projects unaffected:
>
> - **Inside VS Code:** `.vscode/settings.json` (local-only, git-ignored; see the
>   tracked template `.vscode/settings.json.example`) pins the Java extension to
>   `C:\Program Files\Java\jdk-21.0.10` and injects `JAVA_HOME` + a prepended
>   JDK 21 `bin` entry into every **new** integrated terminal. Just open the
>   `Systivex` folder, kill any old terminal (trash-can icon), and open a new
>   one (`Ctrl+Shift+\``) — `java -version` must report 21.x.
> - **Outside VS Code** (standalone PowerShell/cmd): set it manually per session:
>
> ```powershell
> $env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.10"
> $env:Path="C:\Program Files\Java\jdk-21.0.10\bin;" + $env:Path
> ```

Verify:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -version   # expect 21.x
./mvnw -version                             # expect Java version: 21.x
```

## 2. Layout

```text
backend/
├── pom.xml            # spring-boot-starter-parent 4.1.1, java.version 21
├── mvnw / mvnw.cmd / .mvn/wrapper/
└── src/
    ├── main/java/com/soubhagya/systivex/
    │   ├── SystivexApplication.java
    │   └── twin/{model,repository,service,api}/
    ├── main/resources/
    │   ├── application.properties                 # LOCAL ONLY, git-ignored
    │   ├── application.example.properties         # tracked safe template
    │   └── db/migration/V1__create_system_twin.sql
    └── test/java/com/soubhagya/systivex/  # unit + Testcontainers integration tests
```

Backend coordinates: `com.soubhagya:systivex:0.0.1-SNAPSHOT`.
Active dependencies: `webmvc`, `validation`, `actuator`, `data-jpa`, `flyway`
(+ `flyway-database-postgresql`, `postgresql` driver,
`spring-boot-starter-opentelemetry`, `micrometer-registry-prometheus`,
`opentelemetry-logback-appender-1.0` for the Phase 3B-2 Loki sink,
`spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server`
for Phase 10 JWT authentication;
test: `webmvc-test`, `restclient-test`, `testcontainers-postgresql`,
`spring-security-test`).
Phase 4 adds no dependencies.
Do not add more without a recorded decision.

## 2a. Local database setup (once per machine, never committed)

The app expects PostgreSQL 18 on `localhost:5432` with database `systivex`
and user `systivex_app` (owner of the database so Flyway can migrate).
Create them with your admin credentials; the app password itself lives only
in your shell session, never in a file:

```powershell
$env:SYSTIVEX_DB_PASSWORD="<your local systivex_app password>"
```

`backend/src/main/resources/application.properties` reads
`spring.datasource.password=${SYSTIVEX_DB_PASSWORD:}` and is git-ignored —
verify with `git check-ignore` if unsure. The tracked
`application.example.properties` shows the same shape with no secrets.
Flyway migrates automatically at startup; Hibernate only validates
(`ddl-auto=validate`). Never set `update`/`create`/`create-drop` here.

## 3. Maven Wrapper commands

All commands run from `backend/`. In a **new** VS Code integrated terminal the
JDK 21 environment is automatic (workspace settings); in an external shell,
export `JAVA_HOME`/PATH first (see §1).

```powershell
cd backend

# run the test suite
./mvnw test

# full build (runs tests, produces executable jar)
./mvnw package

# build without tests (not a substitute for ./mvnw test in verification)
./mvnw -q package -DskipTests

# start the backend (foreground, Tomcat on :8080)
./mvnw spring-boot:run
```

## 4. How to run tests

```powershell
cd backend
$env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.10"
$env:Path="C:\Program Files\Java\jdk-21.0.10\bin;" + $env:Path
./mvnw test
```

Expected (verified 2026-10-15): full suite green — service unit tests
plus Testcontainers-backed migration, repository, and API integration
tests, 2 observability tests (tracing active, health UP),
20 Phase 4 observation tests (scanner mapping/fail-closed, sync
idempotency/ownership/rollback, HTTP trigger, loopback guard),
37 Phase 5 impact tests (18 deterministic BFS unit tests + 19 HTTP/API
integration tests against the real 15/18 graph),
41 Phase 6 telemetry tests (15 Prometheus-parser + 9 health-parser pure
unit tests, 12 transactional service tests, 5 HTTP/API tests with a fake
source — no live collector),
36 Phase 7 risk tests (24 pure rule-unit tests + 14 HTTP/API tests against
the real 15/18 graph — no live services), Phase 8/9 simulation + policy
unit suites and controller slices (Docker-gated HTTP suites included),
and 31 Phase 10 security tests (role-mapping + fail-closed config unit
tests plus real-HTTP JWT authentication/authorization tests with locally
generated test keys — not live IdP verification). Docker Desktop must be
running; the developer's local database is never touched by tests.

## 5. How to start the backend

```powershell
cd backend
$env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.10"
$env:Path="C:\Program Files\Java\jdk-21.0.10\bin;" + $env:Path
$env:SYSTIVEX_DB_PASSWORD="<your local systivex_app password>"
./mvnw spring-boot:run
```

Expected startup (verified): Spring Boot `v4.1.1`, Tomcat `11.0.24` on port `8080`,
Flyway migrates `V1` on first boot, Hibernate validates, then
`Started SystivexApplication`.

Health check:

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" | ConvertTo-Json
# {"status":"UP","groups":["liveness","readiness"]} (shape may vary; "status":"UP" is the assertion)
```

Only `health` and `prometheus` actuator endpoints are exposed under
`/actuator` (Phase 3A; previously health only). Health is anonymous and
exposes status only (`show-details=never`); Prometheus requires a
`TWIN_READ` bearer token (Phase 10 monitoring policy); every other
actuator path is denied.
The twin API lives under `/api/v1/twin/entities` and
`/api/v1/twin/relationships` (create/get/list, JSON, 400/404/409 on misuse)
— every call needs a JWT bearer token with the endpoint's role
(see §16).

## 6. What is NOT needed yet

- No service containers or orchestration; the only Compose file is
  `observability/docker-compose.yml` (telemetry collector, §9).
- No Redis connection, no vector database, no broker.
- No `OLLAMA_*` configuration, no model pull.
- No `frontend/` install or build.

If a future milestone introduces any of these, this guide must be updated
at the same time as the code — docs must reflect reality.

## 7. Contribution rules for this phase

1. Do not add dependencies unless required for the current phase (recorded in `DECISIONS.md`).
2. Do not create placeholder packages or speculative modules.
3. Do not add Docker Compose, Redis/AI configuration, or frontend scaffolding.
4. Do not weaken tests to make them pass.
5. Do not claim features work unless verified by `./mvnw test` / startup / endpoint check.
6. Never commit `application.properties`, credentials, or machine-local settings.
7. Keep target services free of control-plane code (and vice versa) — the only
   link between them today is documentation.

## 8. Target services (Phase 2B)

Four standalone services under `backend/target-services/`. Each has its own
`pom.xml`, Maven wrapper, and application class — build and run each from its
own directory with the same JDK 21 setup as §1. Order, payment, and inventory
need their own PostgreSQL database (gateway stays stateless); tests use
Testcontainers, so Docker must be running for `./mvnw.cmd test`.

| Service | Directory | Port | Database / user |
|---|---|---|---|
| gateway-service | `backend/target-services/gateway-service/` | 8081 | — (none) |
| order-service | `backend/target-services/order-service/` | 8082 | `order_db` / `order_app` |
| payment-service | `backend/target-services/payment-service/` | 8083 | `payment_db` / `payment_app` |
| inventory-service | `backend/target-services/inventory-service/` | 8084 | `inventory_db` / `inventory_app` |

### 8a. Local target-database setup (once per machine, never committed)

The bootstrap script `backend/target-services/create-target-databases.sql`
creates the three databases and application users (each database owned by its
user). It contains **no passwords** — supply one per service on the command
line — never touches the control-plane `systivex` database, and issues no
destructive commands. Run it as a PostgreSQL superuser (e.g. `postgres`):

```powershell
$env:PGPASSWORD = "<your local postgres superuser password, never committed>"
& psql -h localhost -U postgres `
  -v order_pw="<choose a local order_app password>" `
  -v payment_pw="<choose a local payment_app password>" `
  -v inventory_pw="<choose a local inventory_app password>" `
  -f backend/target-services/create-target-databases.sql
Remove-Item Env:\PGPASSWORD
```

Then export the three service passwords for every shell that runs a service
(the live `application.properties` files are git-ignored and read them only
from the environment — same convention as the control plane):

```powershell
$env:ORDER_DB_PASSWORD="<your local order_app password>"
$env:PAYMENT_DB_PASSWORD="<your local payment_app password>"
$env:INVENTORY_DB_PASSWORD="<your local inventory_app password>"
```

The tracked `application.example.properties` in each service shows the same
shape with no secrets. Flyway migrates automatically at startup (inventory
also seeds `SKU-1001 × 10` units of clearly-marked dev stock via `V2`);
Hibernate only validates (`ddl-auto=validate`). Never set
`update`/`create`/`create-drop` here.

### 8b. Build, test, run (per service)

First run per service (same pattern for order/payment/inventory — example
shown once):

```powershell
cd backend/target-services/order-service
Copy-Item src/main/resources/application.example.properties src/main/resources/application.properties
$env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.10"
$env:Path="C:\Program Files\Java\jdk-21.0.10\bin;" + $env:Path
./mvnw.cmd clean test
./mvnw.cmd -q package -DskipTests
```

The live `application.properties` is git-ignored (same convention as the
control plane); the tracked `.example` file is the template. No secrets exist
in tracked files — ports, downstream URLs, and datasource URLs/usernames are
safe to commit; passwords arrive only via `ORDER_DB_PASSWORD`,
`PAYMENT_DB_PASSWORD`, `INVENTORY_DB_PASSWORD`.

Run all four (one shell each, inventory/payment first, then order, then
gateway — or any order; each waits on its own port):

```powershell
./mvnw.cmd spring-boot:run   # from each service directory
```

Health: `GET http://localhost:808{1..4}/actuator/health` → `{"status":"UP"}`.

Checkout (verified end to end over real HTTP against real databases):

```powershell
Invoke-RestMethod -Uri "http://localhost:8081/api/v1/checkout" -Method Post `
  -Body (@{productId="SKU-1001"; quantity=1; customerId="CUST-1001"; amount=1499.00} | ConvertTo-Json) `
  -ContentType "application/json" | ConvertTo-Json
# -> {"orderId":"...","status":"CONFIRMED",...} with reservationId + authorizationId
```

Behavior notes (persistent, Phase 2B): inventory rejects quantities above
stored stock (422, seeded `SKU-1001` holds 10 — so quantity 99 fails while 1
succeeds), payment declines amounts above 5000.00 (422), dead downstream
surfaces as 503 at order level and 502 at gateway level. Both rejections are
recorded in their service's database. Checkout is not atomic across the three
databases (see `ARCHITECTURE.md` §4): a FAILED order can coexist with a
standing reservation.

Tests (57 total across the four services, Testcontainers PostgreSQL, no local databases touched): each
service has real-HTTP boundary tests — inventory/payment/order boot against
throwaway containers (Flyway-migrated, so migration startup is covered),
order/gateway tests stub only the downstream side with loopback stub servers.
Inventory additionally covers stock-decrease persistence, rejection paths, a
concurrent-reserve locking test, and repository constraint behavior. Each
service also has an observability test: Prometheus exposition plus business
counters, and (gateway/order) W3C traceparent propagation to stubbed
downstream hops — no collector needed for tests. Order-service additionally
has two telemetry privacy tests proving downstream 4xx bodies never reach
recorded span data (positive span-event assertions plus a negative-direction
test that fails with the sanitizer unregistered).

## 9. Telemetry pipeline (Phase 3A) + visualization (Phase 3B-1)

The Java services run locally (see §5/§8b); only the collection
infrastructure runs in Docker. From `observability/`:

```powershell
docker compose up -d          # collector + Prometheus + Grafana (all pinned images)
docker compose logs -f otel-collector
docker compose down           # stop it
```

What it does: receives OTLP traces on :4317/:4318, prints spans to its own
log (debug exporter — the trace verification path), scrapes each service's
`/actuator/prometheus` (ports 8080–8084 via `host.docker.internal`), and
re-exposes the collected metrics Prometheus-compatible on :8889. Collector
health: `GET http://localhost:13133/`.

Phase 3B-1 adds, in the same Compose file: Prometheus on :9090 scrapes
only the collector's `:8889` (never the Java services directly — single
metrics path, no duplicates), and Grafana on :3000 reads Prometheus
through a provisioned datasource (local defaults, no secrets) with one
provisioned `Systivex Metrics` dashboard — a fresh `docker compose up -d`
reproduces both with no browser setup. Per-service identity in queries is
the `service` label (`prometheus.yml` maps the collector-kept
`server_port` to service names, because the collector stamps
`service_name="systivex"` on everything it scrapes).

Phase 3B-2 adds, in the same Compose file: Loki on :3100 (single binary,
filesystem storage in a Docker volume, no auth — local only, see
`loki-config.yml`) plus a provisioned Loki datasource in Grafana, so
Explore works with no browser setup and no new dashboard. The Java
services ship the same console records over OTLP/HTTP
(`management.logging.export.otlp.*`, on top of the unchanged console
pattern) through a new `logs` pipeline in the existing
`otel-collector.yml` (`otlp` receiver → `batch` → OTLP/HTTP export to
`http://loki:3100/otlp`; the exporter appends `/v1/logs` itself).
The bridge that makes records flow is per-service and small: the OTel
Logback appender (`logback-spring.xml` + `OtelLogAppenderInstaller`;
Boot does not install one itself — properties alone ship nothing).
In Loki, only `service.name` is an index label; trace/span IDs stay
searchable structured metadata (`{service_name="order-service"} |
traceId="<hex>"`), never stream labels — see `loki-config.yml` and
`DECISIONS.md` D21. Logs are evidence about runtime behavior, not
authoritative truth.

Per-service checks (services running):

```powershell
Invoke-RestMethod -Uri "http://localhost:8082/actuator/prometheus"  # raw exposition
```

Distributed-trace check: `POST /api/v1/checkout` (§8b), then find the single
trace ID spanning `gateway-service` / `order-service` /
`inventory-service` / `payment-service` in the collector log
(`http post /api/v1/checkout` server span plus `checkout`,
`inventory.reservation`, `payment.authorization` business spans share it).

Configuration: `management.opentelemetry.tracing.export.otlp.endpoint`
defaults to `http://localhost:4318/v1/traces` and is overridable via
`OTEL_TRACING_ENDPOINT` — never a secret. Sampling is 1.0, correct only for
local development volume. Telemetry endpoints bind locally with no auth;
do not expose them beyond the machine (see `THREAT_MODEL.md` §3a).

## 10. Twin synchronization (Phase 4)

The control plane can model the target environment in its own twin with
one explicit trigger (control plane running per §5, against your local
PostgreSQL — tests use Testcontainers instead):

```powershell
# Repository root resolves automatically (backend/target-services from here);
# override per machine with $env:SYSTIVEX_OBSERVATION_REPOSITORY_ROOT or per call:
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/twin/sync" -Method Post `
  -ContentType "application/json" -Body '{}' | ConvertTo-Json
# -> {"entitiesCreated":15,...,"relationshipsCreated":18,...} (first run;
#    re-runs report created:0/unchanged:15+18 — idempotent)
```

Inspect the graph through the existing endpoints:

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/twin/entities" |
  Where-Object { $_.externalRef -like 'target-service:*' } |
  Select-Object type, name, externalRef
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/twin/relationships" | Measure-Object | Select-Object Count
```

Notes and limits:

- The connector reads `application.example.properties`, controller route
  annotations, and Flyway migration SQL under the repository root. It never
  reads live `application.properties`, never touches the environment, and
  never writes to the repository — so no password or secret can flow into
  the twin. Scanned files are never executed.
- Only connector-owned rows (metadata `managedBy: repository-connector`)
  are created/updated. Manual rows are preserved; an identity collision
  fails the whole sync with 409 and rolls back (nothing partial).
- Bad roots fail with 400 before any write. The run is one transaction.
- Localhost-only: the tracked `application.example.properties` binds
  `127.0.0.1`, and the endpoint refuses non-loopback peers with 403.
  A pre-existing live `application.properties` does NOT inherit the example
  file (verified 2026-10-10: the live file has no `server.address` line),
  so for local runs either add `server.address=127.0.0.1` to the live file
  or start with the environment override `SERVER_ADDRESS=127.0.0.1`
  (relaxed binding for `server.address`). Do not bind `0.0.0.0` without an
  authentication story and a recorded review. No watchers, schedulers, Git
  remotes, or telemetry ingestion — those are deferred by decision.

## 11. Change-impact analysis (Phase 5)

Read-only BFS over the synced twin graph (control plane running per §5,
twin synced per §10 — tests use Testcontainers instead):

```powershell
# Potentially affected components for a table change, with evidence paths:
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/twin/impact?from=target-table:inventory_db.inventory_items" |
  Select-Object -ExpandProperty impacted | Select-Object type, externalRef, depth, viaStart
# -> inventory-service + inventory_db (depth 1), order-service, sibling tables,
#    inventory API (depth 2), gateway-service (depth 3), ... each with >= 1 path
#    in .paths recording the FORWARD/REVERSE direction of every step.

# Multiple starts (union), bounded depth, filtered output types:
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/twin/impact?from=target-table:inventory_db.inventory_items&from=target-table:payment_db.payments&maxDepth=3&includeTypes=SERVICE"
```

Notes and limits:

- `from` accepts entity UUIDs or `external_ref` values, 1–10 per call;
  blank or oversized input fails 400 before any graph work; unknown
  identifiers fail 404. `maxDepth` defaults to 6 (bounded 1–10).
  `includeTypes` defaults to SERVICE, API, DATABASE, TABLE; filtered-out
  types are still traversed through, only omitted from the output.
- One `@Transactional(readOnly=true)` snapshot per call: entity and
  relationship counts are identical before and after every query (asserted
  in tests). No migration, no writes, no filesystem or network access.
- Traversal follows stored directions both ways and records each step:
  reverse CALLS/DEPENDS_ON/WRITES/CONTAINS/EXPOSES explain which components
  depend on the changed one ("may affect"); forward steps list downstream
  dependencies explicitly as reachability, never confirmed breakage.
  Relationship types without documented domain meaning (including manual
  rows, e.g. READS) are included as structural reachability with a generic
  explanation — no invented semantics.
- Over-approximation is by design: `WRITES` links a service to every table
  in its owned database (Phase 4 granularity), and no `READS` edges exist,
  so results are "potentially affected", not a blast-radius proof.
- Cycles: any revisit is cut so traversal terminates, but
  `cycleEncountered:true` is reported only for genuine directed cycles
  (one consistent traversal direction around the loop — directed 2-cycles
  over distinct relationships, self-loops, returns to a start via another
  edge). Bidirectional backtracks over the same relationship and
  undirected-only loops (diamonds, service—table—database triangles)
  terminate silently. Alternative paths
  to an already-reached node are still reported. Caps truncate explicitly:
  500 nodes (`truncationReason: node-cap`) / 2000 paths (`path-cap`).
- `snapshotObservedAt` is the observation timestamp of the call, not a
  graph revision. Same input over an unchanged graph returns identically
  ordered results (starts, adjacency, impacted, and paths are all sorted).

## 12. Runtime observation (Phase 6)

Pull-based health/counter annotation over the synced twin graph (control
plane running per §5, twin synced per §10, target services running for
live evidence — tests use fixtures instead):

```powershell
# Annotate matched target-service:* rows with validated runtime facts:
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/twin/observations" -Method Post `
  -ContentType "application/json" -Body '{}' | ConvertTo-Json -Depth 10
# -> {"observedAt":"...","servicesProcessed":4,"factsUpdated":4,"factsUnchanged":0,...}
# Re-polling with identical counters reports factsUpdated:0 while observedAt advances.
```

Notes and limits:

- Ingests only the pinned contract: Actuator health status plus the six
  tag-free business counters (`checkout.success/failure`,
  `inventory.reservation.success/rejected`,
  `payment.authorization.success/declined`; gateway health only).
  Collection targets come from twin metadata on `127.0.0.1` — the request
  carries no URLs. No schedulers, watchers, bulk log import, or raw trace
  storage; trace/log presence is not collected (no privacy-safe source).
- Absent counter families stay absent (never zero-filled); unreachable
  services are recorded per-service as unavailable with limitations. A run
  where nothing was reachable returns zero updates plus explicit
  limitations — never a success claim.
- Validation is fail-closed before any write: unknown business families,
  labels on allowlisted counters, oversized/malformed payloads, and
  privacy-sensitive content abort the run (400/409) with full rollback.
  One transaction per run; only the namespaced `runtime` metadata key is
  written (manual rows, other keys, and all relationships untouched).
  Phase 4 re-syncs preserve `runtime`; Phase 5 impact semantics unchanged.
- Localhost-only like the sync trigger: the endpoint refuses non-loopback
  peers with 403. Do not expose it beyond the machine without the
  deferred auth story.

## 13. Change-risk assessment (Phase 7)

Read-only classification over the impact traversal plus runtime metadata
(control plane running per §5, twin synced per §10 and observed per §12 —
tests use Testcontainers instead):

```powershell
# Ordinal risk levels with reasons for a table change:
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/twin/risk?from=target-table:inventory_db.inventory_items" |
  Select-Object -ExpandProperty risks | Select-Object externalRef, level, uncertain, stale
# -> depth-1 rows MEDIUM, deeper rows LOW on a fresh healthy graph; DOWN or
#    recorded failures raise toward HIGH; services without runtime UNKNOWN.
```

Notes and limits:

- Same `from` (1–10 UUID/external-ref), `maxDepth` (1–10, default 6),
  and `includeTypes` conventions as §11; blank/oversized input fails 400,
  unknown identifiers 404. One `@Transactional(readOnly=true)` unit: entity
  and relationship counts are identical before and after every query
  (asserted in tests). No migration, no writes, no new dependencies.
- Taxonomy `risk-v1` (see `RiskTaxonomy`): depth base, 5+ evidence-path
  fan-out, service health, cumulative failure counters (never rates, never
  incident proof; zero never lowers), 30-minute freshness (exactly 30m is
  fresh; older is stale with severe signals retained). Only services carry
  runtime, so only services receive runtime reasoning.
- Every entry carries sorted reasons plus `uncertain`/`stale` flags.
  Advisory only: potentially affected is not confirmed breakage, and the
  result never authorizes an action. Read-only GET at the same trust level
  as the impact endpoint; do not expose beyond the machine without auth.

## 14. Change simulation (Phase 8)

Hypothetical before/after comparison over the persisted twin graph plus
runtime metadata (control plane running per §5, twin synced per §10 and
observed per §12 — tests use Testcontainers instead):

```powershell
# What would change if inventory_items and its edges were gone?
$proposal = @{
  from = @("target-service:gateway-service")
  operations = @(
    @{ type = "REMOVE_ENTITY"; entity = "target-table:inventory_db.inventory_items" }
  )
} | ConvertTo-Json -Depth 10
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/twin/simulations" -Method Post `
  -ContentType "application/json" -Body $proposal |
  Select-Object simulated, advisory,
    @{ n = "removed"; e = { $_.delta.impactedRemoved } },
    @{ n = "graph"; e = { $_.graph } }
# -> simulated True, advisory "SIMULATED — NOT EXECUTED",
#    removed lists inventory_items, graph shows one fewer entity and the
#    cascaded edges gone from the hypothetical counts.
```

Notes and limits:

- Same `from` (1–10 UUID/external-ref), `maxDepth` (1–10, default 6),
  and `includeTypes` conventions as §11, validated identically
  (blank/oversized → 400, unknown → 404). Operations are limited to
  `REMOVE_ENTITY` (entity plus all incident relationships disappear from
  the hypothetical graph only), `ADD_RELATIONSHIP`, and
  `REMOVE_RELATIONSHIP`. Fixed validation order: shape → 400,
  resolution → 404, proposal conflicts → 409 (analysis-root removal,
  duplicate/contradictory operations compared canonically so UUID and
  external-reference spellings collide, adding an existing edge,
  removing a missing edge, edges incident to a removed entity).
- Both scopes reuse the Phase 5 traversal and the Phase 7
  classification over identical roots and settings — no duplicated
  algorithms. The response pairs `before`/`after` impact+risk scopes
  with a deterministic `delta` (sorted added/removed refs, risk
  membership and level changes, uncertainty counts, truncation-changed
  flag), a baseline/hypothetical `graph` summary, and explicit
  `limitations` (truncation lines, uncertain-evidence lines, runtime
  reuse, caps). Runtime telemetry is reused unchanged; no runtime
  behaviour is simulated.
- One `@Transactional(readOnly=true)` unit: nothing is persisted — no
  proposals, entities, relationships, or runtime metadata (entity and
  relationship counts are identical before and after every simulation,
  asserted in tests). `simulated: true` and the `SIMULATED — NOT
  EXECUTED` advisory mark every response. No migration, no writes, no
  new dependencies. Same trust level as the impact/risk endpoints; do
  not expose beyond the machine without auth.

## 15. Advisory policy evaluation (Phase 9)

Deterministic ALLOW/REVIEW/DENY over the server-computed hypothetical
result (control plane running per §5, twin synced per §10 and observed
per §12 — tests use Testcontainers instead). The request body is the
exact Phase 8 proposal format; caller-supplied risk or deltas are never
accepted:

```powershell
# Advisory decision for removing inventory_items from the gateway scope:
$proposal = @{
  from = @("target-service:gateway-service")
  operations = @(
    @{ type = "REMOVE_ENTITY"; entity = "target-table:inventory_db.inventory_items" }
  )
} | ConvertTo-Json -Depth 10
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/twin/policy-evaluations" -Method Post `
  -ContentType "application/json" -Body $proposal |
  Select-Object decision, policyVersion, @{ n = "rules"; e = { $_.rulesFired.code } }
# -> decision REVIEW or DENY with stable rule codes on this graph
#    (services without runtime read UNKNOWN); ALLOW only on a fresh,
#    healthy, untruncated graph with no increases.
```

Notes and limits:

- Same proposal validation as §14 (blank/oversized → 400, unknown →
  404, conflicts → 409) before any evaluation; rejected proposals yield
  no decision. The service re-runs the simulation server-side and
  evaluates the computed after-scope risks, delta, and truncation flags
  against the immutable `policy-v1` ruleset (see `PolicyTaxonomy`):
  HIGH remaining or either scope truncated → advisory DENY; increased,
  UNKNOWN, or uncertain/stale evidence → REVIEW; otherwise advisory
  ALLOW. Every fired rule is reported, sorted by code; final precedence
  is DENY > REVIEW > ALLOW.
- The response carries a bounded simulation summary (graph counts,
  delta, limitations — not full scopes), the taxonomy version, stable
  reason codes, and explicit non-authorization wording (`ADVISORY POLICY
  EVALUATION — NOT AN AUTHORIZATION; NOT EXECUTED`). ALLOW is still
  advisory and never an authorization grant; no approval, execution, or
  audit record is created.
- One `@Transactional(readOnly=true)` unit: nothing is persisted — no
  proposals, decisions, approvals, executions, or runtime metadata
  (entity and relationship counts are identical before and after every
  evaluation, asserted in tests). No migration, no writes, no new
  dependencies, no configurable policy surface. Same trust level as the
  intelligence endpoints; do not expose beyond the machine without auth.

## 16. Control-plane authentication (Phase 10)

The control plane is an OAuth2 JWT resource server (see `DECISIONS.md`
D28). Every `/api/v1/twin/*` call and `/actuator/prometheus` needs a
`Authorization: Bearer <JWT>` header from the configured trusted issuer:

| Endpoint | Required role |
|---|---|
| Twin/impact/risk GETs | `TWIN_READ` |
| Twin entity/relationship POSTs | `TWIN_ADMIN` |
| Simulations, policy-evaluations | `TWIN_ANALYZE` |
| Sync, observations | `TWIN_OPERATOR` (+ loopback guard, still authoritative) |
| `GET /actuator/health` | anonymous (status only) |
| `GET /actuator/prometheus` | `TWIN_READ` |

`TWIN_ADMIN` inherits the other three roles. Missing/invalid tokens get
401, valid tokens without the role get 403. Configuration
(`application.example.properties`, never committed with secrets):

```properties
systivex.security.jwt.enabled=true
systivex.security.jwt.issuer-uri=${SYSTIVEX_ISSUER_URI:}
systivex.security.jwt.jwk-set-uri=${SYSTIVEX_JWK_SET_URI:}
systivex.security.jwt.accepted-audience=systivex
systivex.security.jwt.role-claim=roles
systivex.security.jwt.clock-skew-seconds=60
systivex.security.jwt.allowed-roles=TWIN_READ,TWIN_ANALYZE,TWIN_OPERATOR,TWIN_ADMIN
```

Set `$env:SYSTIVEX_ISSUER_URI="https://<trusted-issuer>"` before
starting; without it the application fails fast instead of serving an
unauthenticated API, and `enabled=false` is rejected. Tests use locally
generated signing keys (`security/TestJwt.java`) — automated JWT
coverage, not live identity-provider verification. The four
target-service APIs are unchanged (open local-only endpoints).
