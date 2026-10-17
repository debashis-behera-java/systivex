# Systivex — Agentic Software-System Twin & Change Intelligence Platform

Systivex builds a living **System Twin** of a software system — its code, architecture,
data flows, and runtime behavior — so proposed changes can be **impact-analysed,
risk-assessed, simulated, policy-evaluated, and governed through approval** before
anything is asked to touch a real system.

Systivex is deliberately a **control plane for safe engineering action**, not a point
tool. It grounds reasoning in an authoritative model of the system and routes every
consequential decision through explicit, auditable governance.

> **Controlled execution is not implemented.** An `APPROVED` proposal is a terminal
> permission record — it authorizes nothing by itself and executes nothing. Acting on an
> approval is a human step outside the system, and remains gated by the feasibility
> decision recorded in [`DECISIONS.md`](DECISIONS.md) (D30/D31). There is no execution
> endpoint, no executor role, and no target actuator in this repository.

## What Systivex is

- A **software-system twin**: a persisted graph of services, APIs, databases, tables,
  and the relationships between them, built by a read-only repository connector.
- A **change-intelligence workspace**: deterministic impact analysis, ordinal risk
  assessment, and *hypothetical* before/after simulation of a proposed change.
- A **governance and audit layer**: governed change proposals with assessment,
  amendment, approval/rejection, and a tamper-evident audit chain.

## What Systivex is not

- Not an AI code reviewer, an SRE chatbot, a generic LLM wrapper, or a chat assistant
  over logs.
- Not an execution engine or an autonomous agent. Nothing in this repository changes a
  target system, and no agent exists.

## Current status

Systivex is under **active local development**. It is **not production-ready** and is not
deployed anywhere by this project. The three categories below are kept deliberately
separate.

### 1. Implemented and tested

The backend control plane (`backend/`) is a Spring Boot 4.1.1 application on Java 21 with
PostgreSQL/Flyway persistence and JWT-secured HTTP APIs. The full backend suite is run
with `./mvnw clean test` (398 tests, 0 failures as of the latest recorded run; requires
Docker for Testcontainers).

- **System-twin persistence and REST** — `SystemEntity` / `SystemRelationship` with
  closed type enums, JSONB metadata, UUID keys, and stable 400/404/409 errors.
- **Twin observation** — a read-only repository connector that scans the sample target
  services into twin rows via `POST /api/v1/twin/sync` (idempotent, loopback-only).
- **Change-impact analysis** — deterministic, read-only BFS over the twin graph
  (`GET /api/v1/twin/impact`) with ordered evidence paths and explicit caps.
- **Runtime observation** — pull-based health plus six allowlisted business counters into
  the twin (`POST /api/v1/twin/observations`; loopback-only, fail-closed).
- **Change-risk assessment** — ordinal `LOW`/`MEDIUM`/`HIGH`/`UNKNOWN` classification over
  impact paths plus runtime facts (`GET /api/v1/twin/risk`); advisory, never authorization.
- **Hypothetical change simulation** — before/after comparison over an in-memory graph
  (`POST /api/v1/twin/simulations`). Responses carry `simulated: true` and a
  `SIMULATED — NOT EXECUTED` advisory; nothing is persisted or executed.
- **Advisory policy evaluation** — `ALLOW`/`REVIEW`/`DENY` classification
  (`POST /api/v1/twin/policy-evaluations`) over the server-recomputed simulation. An
  advisory `ALLOW` is **not** authorization.
- **Governed change proposals and tamper-evident audit** (`proposal` package): immutable
  proposal revisions, assessment/submission/amendment, approval/rejection with separation
  of duties, lazy expiry, and a hash-chained append-only audit with
  `GET /api/v1/twin/audit/verify`.
- **Control-plane authentication** — OAuth2 JWT resource server with allowlist-only role
  mapping and fail-closed configuration (see *Authentication* below).
- **Sample target environment** (`backend/target-services/`) and a **local telemetry
  pipeline** (`observability/`): four runnable services with their own databases, plus a
  collector/Prometheus/Loki/Grafana stack for traces, metrics, and logs.

### 2. Design and feasibility only

- **Controlled execution** — [`DECISIONS.md`](DECISIONS.md) D30 records the binding
  constraints any future execution work must satisfy; D31 records the Phase 12 S1
  feasibility gate. No current simulation operation has a meaningful, independently
  verifiable target actuator, so execution stays **unimplemented and gated**. `APPROVED`
  remains terminal (D29) and is not superseded.

### 3. Planned, not implemented

- Frontend/operator console (the repository has **no** frontend today).
- Agent orchestration, tool mediation, and an evidence model.
- Post-change verification logic.
- Twin trace/log presence signals and Git integration.
- Redis, message brokers, Kubernetes, and other infrastructure (deferred by decision).

The deterministic capabilities (impact analysis, risk assessment, simulation) are
independent of any model and are not advisory. The policy evaluation is **advisory** and
never authorization. Simulation is **hypothetical** and never execution.

## How it works (the main architectural idea)

Systivex separates a **control plane** from the **target environment** it observes:

- The **control plane** is a modular monolith (one Spring Boot deployment) that owns the
  twin model, the intelligence modules, policy evaluation, governance, and the audit log.
- The **target environment** is a set of independently deployable sample services that
  Systivex can observe but does not control.
- **Consequential actions cross the policy/execution boundary** — and the execution side
  of that boundary is intentionally not built yet.

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the target architecture, the deterministic
vs. advisory distinction, and the trust boundaries, and
[`THREAT_MODEL.md`](THREAT_MODEL.md) for the security principles.

## Authentication

The entire `/api/v1/twin/*` surface requires a **JWT bearer token**; the control plane is
an OAuth2 **resource server** and validates issuer, audience, signature, expiry, and
`nbf`, with allowlist-only role mapping.

- Roles: `TWIN_READ`, `TWIN_ANALYZE`, `TWIN_OPERATOR`, `TWIN_ADMIN`, `TWIN_APPROVER`.
  `TWIN_ADMIN` inherits the first three but never `TWIN_APPROVER`.
- Configuration is fail-closed via `systivex.security.jwt.*`; a trusted `issuer-uri` is
  **mandatory** and there is no security-off mode.
- There is **no login flow, no bundled identity provider, and no default credentials** in
  this repository. You must configure a trusted issuer and obtain a token from it.
- Automated tests use locally generated signing keys only; **live identity-provider
  verification is still pending** a configured trusted issuer and is not claimed.

Configuration details are in [`DEVELOPMENT.md`](DEVELOPMENT.md).

## Repository layout

```text
systivex/
├── backend/                 # Spring Boot control plane (Java 21, Maven wrapper)
│   │   ├── .../twin/        # twin model, persistence, REST
│   │   ├── .../observation/ # repo connector + POST /api/v1/twin/sync
│   │   ├── .../intelligence/# impact, risk, hypothetical simulation
│   │   ├── .../telemetry/   # pull-based runtime annotation
│   │   ├── .../policy/      # advisory policy evaluation
│   │   ├── .../proposal/    # governed proposals + tamper-evident audit
│   │   └── .../security/    # JWT resource server, role policy
│   └── target-services/     # sample target environment (gateway/order/payment/inventory)
├── observability/           # local collector + Prometheus + Loki + Grafana (telemetry only)
├── ARCHITECTURE.md          # target architecture + current status
├── DEVELOPMENT.md           # build, test, run, configure
├── PROJECT_STATUS.md        # phase-by-phase record of what exists
├── DECISIONS.md             # architectural decisions (D29–D32 relevant to governance/licensing)
├── THREAT_MODEL.md          # trust principles
├── CONTRIBUTING.md          # how to contribute
├── CODE_OF_CONDUCT.md       # contributor code of conduct
├── SECURITY.md              # how to report a vulnerability
└── LICENSE                  # Apache License 2.0
```

## Quick start

Prerequisite: **JDK 21** (`JAVA_HOME` must point at a JDK 21 installation) and, for the
tests, a running **Docker** daemon (Testcontainers PostgreSQL). PostgreSQL 18 is required
only to run the application locally.

```powershell
cd backend
$env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.10"
$env:Path="C:\Program Files\Java\jdk-21.0.10\bin;" + $env:Path
./mvnw clean test        # requires Docker (Testcontainers PostgreSQL)
./mvnw package -DskipTests
```

Running the application also requires a trusted JWT issuer plus a local PostgreSQL
database and the `SYSTIVEX_DB_PASSWORD` environment variable. Full instructions, including
authentication configuration, are in [`DEVELOPMENT.md`](DEVELOPMENT.md).

## Contributing

Contributions are welcome. Start with [`CONTRIBUTING.md`](CONTRIBUTING.md) for the
architecture overview, prerequisites, verified build/test commands, and review
expectations. All participation is covered by [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).

## Security

Please report vulnerabilities privately — **do not open a public issue**. See
[`SECURITY.md`](SECURITY.md) for the supported reporting route.

## Project rules (binding)

- Java 21, Spring Boot 4.1.1, Maven (wrapper).
- Modular monolith for the Systivex control plane; microservices are reserved for the
  target environment.
- PostgreSQL as primary persistence (Flyway-owned schema, Hibernate validates only).
- REST initially; no Kafka, RabbitMQ, Kubernetes, WebSockets, vector databases, MCP,
  multi-agent architecture, or cloud infrastructure at this stage.
- When the frontend lands, it follows the repository convention: **React/Vite with
  JavaScript, not TypeScript**, unless a future recorded decision changes it.
- Proposed execution capabilities, authentication changes, security-sensitive behavior,
  and new infrastructure require careful review and must not bypass existing decisions.

## License

Systivex is licensed under the **Apache License, Version 2.0**. The full license text is
in [`LICENSE`](LICENSE).

Copyright 2026 Soubhagya Kumar Behera.

Unless required by applicable law or agreed to in writing, software distributed under the
License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
This is a summary; the [`LICENSE`](LICENSE) file governs. It is not legal advice.
