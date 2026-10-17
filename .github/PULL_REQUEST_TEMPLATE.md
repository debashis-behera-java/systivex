# Pull request

## Summary

<!-- What changed and why. Keep it focused on one change. -->

## Linked issue

<!-- e.g. "Closes #123" or "Related to #123". If there is no issue, say so. -->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Refactor / internal
- [ ] Tests only
- [ ] Documentation only
- [ ] Build / CI

## Verification performed

<!-- Describe exactly what you ran and the result. Do not claim tests you did not run. -->

- [ ] `./mvnw clean test` (backend, requires Docker) — result:
- [ ] Other commands:

## Documentation

- [ ] I updated the relevant documentation (`README.md`, `ARCHITECTURE.md`,
      `DEVELOPMENT.md`, `PROJECT_STATUS.md`, `DECISIONS.md`, `THREAT_MODEL.md`) if behavior or
      commands changed.
- [ ] Not applicable (no documentation change needed).

## Security implications

- [ ] This change does not alter authentication, authorization, policy enforcement, or
      security controls.
- [ ] If it does, a decision record and additional review are included.

- [ ] I have not committed secrets, credentials, tokens, passwords, private keys, or real
      production data.
- [ ] Any logs or payloads included have been sanitized.

## Compatibility

- [ ] No changes to public API shape, database schema, or persisted formats.
- [ ] If there are such changes, they are described above and justified.

## Checklist

- [ ] The change is focused and does not include unrelated edits.
- [ ] New or changed behavior includes tests; bug fixes include a regression test.
- [ ] `git diff --check` passes (no whitespace errors).
- [ ] I have read `CONTRIBUTING.md` and `CODE_OF_CONDUCT.md`.
