# Cerial Master Client Rules

This project adopts the Rules Repository via the `rules/` submodule (https://github.com/GuicedEE/ai-rules.git). All host-level artifacts (PACT.md, RULES.md, GUIDES.md, IMPLEMENTATION.md, GLOSSARY.md) live outside the submodule and link back to topic indexes.

## Scope & Selected Stacks
- **Language:** Java 25 LTS (see `rules/generative/language/java/java-25.rules.md`). Module descriptors target the same level so this artifact compiles with the parent `activitymaster-group` bom.
- **Fluent API:** CRTP is enforced (`rules/generative/backend/fluent-api/crtp.rules.md`). Do not use Lombok `@Builder`; fluent setters must return `(J)this` and honor the CRTP guard rail discussed in the rules repo.
- **Dependency stack:** GuicedEE Client (`rules/generative/backend/guicedee/client/README.md`) + GuicedEE core services (`rules/generative/backend/guicedee/README.md`), Vert.x 5 runtime (`rules/generative/backend/vertx/README.md`), ActivityMaster Cerial Client (`rules/generative/data/activity-master/cerial-client/README.md`), and the GuicedEE logging/MapStruct/Lombok/JSpecify stack referenced below.
- **Data & Domain:** ActivityMaster Core/Client schemas (see `rules/generative/data/activity-master/README.md` and topic glossaries). The client exposes Config/MessageSpec/MessageResult DTOs aligned with the ActivityMaster vocabulary.
- **Frontend bridge:** JWebMP TypeScript generation (`rules/generative/frontend/jwebmp/typescript/README.md`) supports downstream TypeScript consumers declared via `com.jwebmp.plugins:typescript-client`.
- **Testing & Observatory:** Jacoco + Java Micro Harness (`rules/generative/platform/testing/jacoco.rules.md` and `rules/generative/platform/testing/java-micro-harness.rules.md`) complement GitHub Actions CI (`rules/generative/platform/ci-cd/providers/github-actions.md`).
- **Logging & Quality:** Log4j2 is mandatory (`rules/generative/backend/logging/LOGGING_RULES.md`). Lombok `@Log4j2` is preferred, and MapStruct 6 is the approved mapper tooling (`rules/generative/backend/mapstruct/mapstruct-6.md`). JSpecify ensures null contracts (`rules/generative/backend/jspecify/README.md`).

## Document Modularity & Forward-Only
Host documentation follows the Document Modularity Policy and Forward-Only Change Policy from `rules/RULES.md` (sections 4, 5, 6). Large explanations live in modular docs (`docs/architecture/*`, `GLOSSARY.md`, etc.). Stage gates are recorded in `PACT.md` and linked via `docs/PROMPT_REFERENCE.md`.

## Glossary and Guides
- Glossary terms live in `GLOSSARY.md` and link to topic glossaries so their definitions stay authoritative.
- Guides reference the same topics. Every addition to GUIDES.md, IMPLEMENTATION.md, or future guides must cite a `rules/` path and mention the relevant glossary entry.

## CI & Environment
- GitHub Actions workflow uses the shared GuicedEE job defined in `GuicedEE/Workflows/.github/workflows/projects.yml@master`. Workflow secrets (USERNAME, USER_TOKEN, SONA_USERNAME, SONA_PASSWORD) are required and documented in README and `.github/workflows/ci.yml`.
- `.env.example` lists the environment variables that align with `rules/generative/platform/secrets-config/env-variables.md` (service name, tracing toggles, observability, and database placeholders). Projects may extend this per environment, but shared names must match.

## Development & Testing
- Use Jacoco and Java Micro Harness instrumentation from the rules repo for coverage gates.
- Follow ActivityMaster cerial client testing advice in `rules/generative/data/activity-master/cerial-client/testing.rules.md` when adding new tests.
- Logging, telemetry, and Mutiny streams must comply with `rules/generative/backend/logging/LOGGING_RULES.md` and `rules/generative/backend/guicedee/README.md` expectations (especially for `ICerialMasterService`, `IComPortStatusChanged`, and reactive lifecycles).

## Closing Loop
The PACT, RULES, GUIDES, IMPLEMENTATION, GLOSSARY, and architecture docs reference back to `docs/PROMPT_REFERENCE.md` to keep traceability aligned. When adding code or docs, update this RULES file and the related artifacts to maintain the `Pact ⇄ Rules ⇄ Guides ⇄ Implementation` cycle.
