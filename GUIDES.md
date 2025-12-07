# Cerial Master Client Guides

## Getting started
1. Review the adoption artifacts in `PACT.md`, `RULES.md`, `GLOSSARY.md`, `IMPLEMENTATION.md`, and `docs/PROMPT_REFERENCE.md`. These files close the loop between culture, constraints, language, and implementation.
2. Read the architecture bundle in `docs/architecture/` before modifying code so that every component change stays aligned with C4 and sequence diagrams.
3. Use the `rules/` submodule as the authoritative source for guidance. Follow `rules/generative/data/activity-master/cerial-client/README.md` plus `rules/generative/backend/guicedee/client/README.md`, `rules/generative/language/java/java-25.rules.md`, and `rules/generative/platform/ci-cd/providers/github-actions.md` before altering APIs or build artifacts.

## Sending retries, groups, and status updates
- **CRTP & GuicedEE**: `TimedComPortSender` must return `(J)this` for fluent setters (CRTP). Any helper that mutates `Config`, `MessageSpec`, or `AttemptFn` must honor `rules/generative/backend/fluent-api/crtp.rules.md` and avoid Lombok `@Builder`.
- **Connection lifecycle**: `ComPortConnection.getOrCreate` stays the single registry for each COM port. The connection registers `IReceiveMessage`, `IErrorReceiveMessage`, and `IComPortStatusChanged` service loader implementations from `rules/generative/backend/guicedee/services/services.md`.
- **Telemetry**: Publish `StatusUpdate` and `MessageProgress` events through Mutiny `Multi` helpers defined in `TimedComPortSender`. Attach snapshot consumers with `onStatisticsUpdated` to stay consistent with `rules/generative/backend/logging/LOGGING_RULES.md` and the ActivityMaster telemetry vocabulary.

## Configuration & Environment
- Defaults derive from `Config` objects that mirror `rules/generative/data/activity-master/cerial-client/configuration.rules.md`; override them via `Config.assignedRetry`, `assignedDelayMs`, and `assignedTimeoutMs` while keeping time computations in UTC (see `TimedComPortSender#toOffset`).
- Environment variables follow `rules/generative/platform/secrets-config/env-variables.md`. Use `.env.example` as a local checklist; align GitHub Actions secrets with those names as indicated in `RULES.md`.

## Observability & Testing
- Use Jacoco + Java Micro Harness coverage assertions; see `rules/generative/platform/testing/jacoco.rules.md` and `rules/generative/platform/testing/java-micro-harness.rules.md` for thresholds.
- Validate logging via Log4j2 appenders that capture retries and terminal states. Use Lombok `@Log4j2` so trace IDs include context without manual slf4j wrappers.
- Maintain topic-aware naming by consulting `GLOSSARY.md` (which links to `rules/generative/backend/guicedee/GLOSSARY.md`, `rules/generative/backend/logging/GLOSSARY.md`, etc.) when documenting new features.

## Frontend interoperability
- TypeScript generation via `com.jwebmp.plugins:typescript-client` adheres to `rules/generative/frontend/jwebmp/typescript/README.md` and companion glossaries. Emit DTO metadata that matches `MessageSpec` and `MessageResult` naming in `GLOSSARY.md` to keep the bridge synchronized.

## CI & Release
- GitHub Actions include the reusable `Guiced Injection` job. Update secrets and environment variables by documenting them in README (see `rules/generative/platform/ci-cd/providers/github-actions.md`).
- When releasing, mention the architecture diagrams and glossary entries so stakeholders understand how the client interacts with ActivityMaster systems.
