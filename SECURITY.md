# Security Policy

## Supported versions

Systivex is under active local development and is **not production-ready**; it is not
deployed anywhere by this project. Security fixes target the `main` branch only.

| Version | Supported |
|---|---|
| `main` | Yes |
| Any older commit or fork | No |

## Reporting a vulnerability

**Please do not report security vulnerabilities through public issues, pull requests, or
discussions.**

Preferred route — GitHub private reporting:

1. Go to the repository's **Security** tab.
2. Choose **Report a vulnerability** (GitHub Security Advisories). This opens a private
   channel visible only to the maintainers.

If private reporting is not available to you, contact the maintainer privately through
their GitHub profile at <https://github.com/soubhagya-behera> and ask for a private
channel; do not post the details publicly.

Please include, where possible:

- A description of the issue and its impact.
- Steps to reproduce (a minimal reproduction is ideal).
- Affected component, version, or commit.
- Any suggested remediation.

We will acknowledge your report, investigate, and keep you informed of the outcome. We
will credit reporters who wish to be credited.

## Scope and context

- The control plane is an OAuth2 JWT **resource server**; it validates issuer, audience,
  signature, expiry, and `nbf`, and maps roles through an allowlist. It has **no login
  endpoint, no bundled identity provider, and no default credentials**.
- Live identity-provider verification is **pending** a configured trusted issuer; the
  automated tests use locally generated signing keys only.
- Sync and runtime-observation endpoints are additionally guarded by a loopback-only
  check.
- The sample target services in `backend/target-services/` are local demonstration code,
  not hardened production services.

## Handling secrets

Never commit secrets, credentials, tokens, passwords, private keys, or real production
data. Local configuration is git-ignored by design; only `application.example.properties`
templates are tracked, and passwords are supplied through environment variables (for
example `SYSTIVEX_DB_PASSWORD`).

## Out of scope

- Vulnerabilities in third-party dependencies should be reported upstream; dependency
  licenses and security are the responsibility of their respective projects.
- Findings that depend on a deliberately unsupported configuration (for example,
  disabling security, which the application already refuses to start with).
