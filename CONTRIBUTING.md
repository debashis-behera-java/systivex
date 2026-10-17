# Contributing to Systivex

Thanks for your interest in Systivex. This guide is written for a first-time
contributor. It covers what to read first, how to build and test, and what we expect from
changes. If anything here is unclear, please open an issue so we can improve it.

By participating you agree to follow the [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).

## Read this first

Systivex has a small, deliberate architecture. Before writing code, read:

1. [`README.md`](README.md) — what Systivex is, what exists, and what is deliberately not built.
2. [`ARCHITECTURE.md`](ARCHITECTURE.md) — the control-plane/target-environment split, module
   boundaries, and the deterministic-vs-advisory distinction.
3. [`DEVELOPMENT.md`](DEVELOPMENT.md) — build, test, run, and configure commands.
4. [`DECISIONS.md`](DECISIONS.md) — binding architectural decisions (read the entries relevant to
   the area you are touching before changing it).
5. [`PROJECT_STATUS.md`](PROJECT_STATUS.md) — the phase-by-phase record of what actually exists.
6. [`THREAT_MODEL.md`](THREAT_MODEL.md) — the security principles the design protects.

The backend control plane lives in `backend/` (packages under
`backend/src/main/java/com/soubhagya/systivex/`). The sample target environment is under
`backend/target-services/`; the local telemetry stack is under `observability/`.

## Prerequisites

| Requirement | Notes |
|---|---|
| **JDK 21** | Required. Set `JAVA_HOME` to a JDK 21 install; the build uses `release 21`. |
| **Maven wrapper** | Bundled at `backend/mvnw` (`backend/mvnw.cmd` on Windows). No separate Maven install. |
| **Docker** | Required to run the test suite: integration tests use Testcontainers PostgreSQL. Your Docker daemon must be running. |
| **PostgreSQL 18** | Required only to *run* the application locally (not for tests). |

There is no frontend, no Node toolchain, and no cache/AI runtime required today.

> The wrapper scripts are committed without the executable bit on some checkouts. On
> Linux/macOS run `chmod +x mvnw` once from `backend/` if `./mvnw` is not executable.

## Build and test

Run all commands from `backend/` (the Maven wrapper is there). Use `mvnw` on
Linux/macOS and `mvnw.cmd` on Windows.

```bash
cd backend

# Full verification used by CI (requires Docker for Testcontainers)
./mvnw clean test

# Build a runnable jar without running tests
./mvnw package -DskipTests
```

The individual sample target services each have their own wrapper and tests, e.g.:

```bash
cd backend/target-services/order-service && ./mvnw test
```

**Do not weaken, skip, or delete tests to make a build pass.** If a test suite cannot run
in your environment, describe the precise blocker in your pull request instead of masking
it.

## Branch and pull-request expectations

- Branch from `main`; use a short descriptive name (for example `docs/contributing` or
  `test/health-parser-edges`).
- Keep each pull request focused on one change. Split unrelated work into separate PRs.
- Reference the issue your change addresses in the PR description, if one exists.
- Write a clear summary: what changed, why, and how you verified it.
- Prefer small, reviewable commits with meaningful messages.
- Fill in the pull-request template, including the verification and security sections.

## What we look for in a change

- **Tests** — new behavior needs tests; bug fixes should include a regression test. Add
  tests in the same area as the existing suite.
- **Error handling** — preserve the existing stable HTTP error contract (the
  `{status, error, message, path, fieldErrors}` envelope with 400/404/409 semantics).
- **Security** — never bypass or weaken the JWT resource-server configuration, role
  policy, loopback guards, or fail-closed startup behavior. Never log tokens, secrets, or
  request bodies.
- **Documentation** — update the relevant document when behavior or commands change.
- **Scope** — keep the change minimal and consistent with existing decisions.

## Secrets, data, and privacy

- Never commit secrets, credentials, tokens, passwords, private keys, or real production
  data — including in tests, fixtures, logs, screenshots, or issue reports.
- Local configuration is git-ignored on purpose: only `application.example.properties`
  templates are tracked. Provide passwords through environment variables (for example
  `SYSTIVEX_DB_PASSWORD`); never hard-code them.
- Sanitize any logs, traces, or payloads you paste into an issue or pull request.

## Reporting a bug, requesting a feature, or proposing architecture changes

- **Bug** — open an issue using the bug report form. Include reproducible steps, expected
  behavior, actual behavior, and sanitized environment details.
- **Feature** — open an issue using the feature request form. Describe the problem,
  your proposed approach, alternatives, and scope.
- **Significant architectural change** — discuss it in an issue **before** implementing.
  Changes to execution capabilities, authentication, security-sensitive behavior, or new
  infrastructure are treated as significant and must not bypass existing decisions.

Execution-related proposals must respect the recorded decisions: controlled execution is
**not implemented and remains gated**, an `APPROVED` proposal stays terminal, and
simulation must remain clearly distinct from real execution (see
[`DECISIONS.md`](DECISIONS.md) D29–D31).

## Frontend convention (for when a frontend exists)

The repository has **no frontend yet**. When one is added, the convention is
**React with Vite in JavaScript — not TypeScript** — unless a future recorded decision
explicitly changes it (see `.gitignore` and `PROJECT_STATUS.md`). Do not start a frontend
without a decision authorizing it.

## Good first contributions

These are bounded, low-risk starting points grounded in current gaps. They are ideas, not
published issues — open an issue (or comment on an existing one) before starting so we can
coordinate.

1. **Add an `.editorconfig`.** Define consistent indentation and line endings for Java,
   Markdown, YAML, and properties files. No behavior change.
2. **Expand the deterministic parser/validator unit tests.** Add edge-case tests for the
   telemetry parsers (`HealthParser`, `PrometheusCounterParser`) and the impact/risk query
   validators. Pure test additions; no production code change required.
3. **Document a sanitized manual API walkthrough.** Add a token-based, step-by-step
   example (sync → impact → risk → simulation → policy) to `DEVELOPMENT.md`, using
   placeholders and no committed secrets or tokens.
4. **Add a CI status badge to the README.** Once the backend workflow is running on the
   primary branch, add the status badge near the top of `README.md`.
5. **Design note for twin list pagination.** The twin entity/relationship `GET` endpoints
   currently return unpaged lists. Draft a short design note (an issue, not code) for
   adding pagination and filtering consistently with the existing API conventions before
   implementing it.
