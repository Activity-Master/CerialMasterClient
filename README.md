# 🥣 CerialMasterClient

[![CI](https://github.com/Activity-Master/CerialMasterClient/actions/workflows/ci.yml/badge.svg)](https://github.com/Activity-Master/CerialMasterClient/actions/workflows/ci.yml)
[![Issues](https://img.shields.io/github/issues/Activity-Master/CerialMasterClient)](https://github.com/Activity-Master/CerialMasterClient/issues)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://github.com/Activity-Master/CerialMasterClient/pulls)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![JDK](https://img.shields.io/badge/JDK-25%2B-0A7?logo=java)](https://openjdk.org/projects/jdk/25/)
[![Build](https://img.shields.io/badge/Build-Maven-C71A36?logo=apachemaven)](https://maven.apache.org/)

<!-- Tech icons row -->
![Vert.x](https://img.shields.io/badge/Vert.x-5-4B9?logo=eclipsevertdotx&logoColor=white)
![Hibernate Reactive](https://img.shields.io/badge/Hibernate-Reactive-59666C?logo=hibernate)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15+-4169E1?logo=postgresql&logoColor=white)
![Guice](https://img.shields.io/badge/Guice-Enabled-2F4F4F)
![GuicedEE](https://img.shields.io/badge/GuicedEE-Client-0A7)

Cerial Master Client is the ActivityMaster FSDM open-source library that orchestrates scheduled COM port messaging, telemetry, and GuicedEE service hooks for the Cerial Master addon. It ships as a Java 25 client-facing module with Mutiny-based status streams, CRTP-heavy DTOs, and TypeScript generation support so downstream Angular/TypeScript consumers can remain in sync with the stage-gated architecture.

## ✨ Features
- Reliable COM-port messaging with retry/backoff and progress snapshots
- Pause/resume/cancel controls and group/batch processing
- Reactive telemetry streams (Mutiny Uni/Multi) for status and events
- GuicedEE DI integration and lifecycle hooks for clean boot/shutdown
- DTO mapping and optional TypeScript client generation alignment
- JPMS-friendly module with ServiceLoader discovery

## 📦 Install (Maven)
Add the dependency to your Maven project. Versions are managed by the parent/BOM.

```xml
<dependency>
  <groupId>com.guicedee.activitymaster</groupId>
  <artifactId>cerial-master-client</artifactId>
</dependency>
```

## 🚀 Quick Start
1. Add the dependency above to your host application.
2. Copy `.env.example` to `.env` and provide required values.
3. Build and run tests locally:

```bash
mvn -B clean verify
```

4. Wire the client into your GuicedEE bootstrap, then publish commands and subscribe to telemetry streams.

## ⚙️ Configuration
Environment variables follow the Rules Repository guidance (`rules/generative/platform/secrets-config/env-variables.md`). Cerial-specific keys are documented under `rules/generative/data/activity-master/cerial-client/`. Typical examples you may encounter:
- CERIAL_ENABLED=true|false
- CERIAL_PORT, CERIAL_BAUD, CERIAL_DATA_BITS, CERIAL_STOP_BITS, CERIAL_PARITY
- CERIAL_GROUP_SIZE, CERIAL_RETRY_DELAY_MS, CERIAL_MAX_RETRIES
- LOG_LEVEL, TRACING_ENABLED

Treat the above as examples; consult the rules index for the authoritative list and semantics. Keep secrets and machine-specific values out of VCS. Use `.env` locally and GitHub Action secrets in CI.

## 🧩 JPMS & SPI
- JPMS-ready; services and configurators use `ServiceLoader` for discovery.
- Ensure your host module declares the appropriate `uses`/`provides` where applicable.

## Overview
- **Intent:** Deliver reliable, retry-aware COM port messaging with progress snapshots, group processing, and cancellation/pause control.
- **Tech stack:** Java 25 LTS, GuicedEE client services, Vert.x reactive telemetry, Lombok + JSpecify, Log4j2 logging, MapStruct 6, Mutiny Multi/Uni flows, and the ActivityMaster client domain vocabulary.
- **Guidance:** All conventions flow from the Rules Repository submodule (`rules/`)—specifically `rules/generative/data/activity-master/cerial-client`—and the host artifacts that close the PACT ⇄ RULES ⇄ GUIDES ⇄ IMPLEMENTATION loop.

## Getting started
1. Clone, then initialize the Rules Repository submodule that provides the shared policies:
   ```bash
   git submodule update --init --recursive rules
   ```
2. Review `PACT.md` to understand the stage-gated collaboration rules and blanket approval status before modifying any docs or code.
3. Follow the anchor docs (PACT, RULES, GUIDES, GLOSSARY, IMPLEMENTATION) and the architecture diagrams in `docs/architecture/` before coding; the Document Modularity policy requires you to link to these artifacts whenever you add new guidance.
4. Use `.env.example` to mirror the production variable names defined in `rules/generative/platform/secrets-config/env-variables.md` and keep secrets (such as `OAUTH2_CLIENT_SECRET` or `DB_PASS`) out of version control.

## Documentation bundle
- [PACT.md](PACT.md): human/AI collaboration, stage approval summary, and roadmap that confirm blanket approval for this run.
- [RULES.md](RULES.md): project scope, stack selections (Java 25, CRTP, GuicedEE client), and forward-only commitments linking back to topic indexes.
- [GUIDES.md](GUIDES.md): how to apply the rules when sending messages, configuring telemetry, and keeping TypeScript bridges aligned.
- [GLOSSARY.md](GLOSSARY.md): topic-first glossary with precedence links to the submodule’s canonical definitions.
- [IMPLEMENTATION.md](IMPLEMENTATION.md): current layout, architecture wiring, testing expectations, and the Stage 3 implementation roadmap for future work.
- [docs/PROMPT_REFERENCE.md](docs/PROMPT_REFERENCE.md): quick reference for selected stacks, active diagrams, MCP servers, and prompt rules for future AI runs.
- Architecture diagrams live in `docs/architecture/` (context/container/component C4 plus dependency map, sequence flows, and the messaging ERD) and are indexed by `docs/architecture/README.md`.

## How to use these rules
- Use `rules/generative/data/activity-master/cerial-client/README.md` as the topic index before prompting or coding; follow its modular links for lifecycle, configuration, telemetry, integration, and testing guidance. When working with the core Activity Master client library (not the Cerial addon), use `rules/generative/data/activity-master/client/README.md`.
- Keep host docs (PACT, RULES, GUIDES, IMPLEMENTATION, GLOSSARY, docs/architecture) outside the `rules/` submodule and update them together to honor the forward-only policy.
- Load `.mcp.json` so the Mermaid MCP server is available when editing diagrams; align terminology with the topic glossary before adding new docs or APIs.

## Repository & Packages
- GitHub: https://github.com/Activity-Master/CerialMasterClient
- Organization: ActivityMaster / Cerial Client

## Architecture & implementation pointers
- Stage 1 architecture artifacts (C4 diagrams, sequences, ERD) are committed under `docs/architecture/`. Stage 2 docs (PACT, RULES, GUIDES, GLOSSARY, IMPLEMENTATION) close the documentation loop, while Stage 3 now outlines the implementation roadmap still to be executed.
- Refer to `docs/architecture/README.md` for the diagram index and `IMPLEMENTATION.md` for descriptions of the send engine, registry, telemetry streams, and test wiring.

## Environment & CI
- `.env.example` reflects the variables recommended by `rules/generative/platform/secrets-config/env-variables.md` (service identity, tracing toggles, database credentials, and testing overrides).
- CI runs via the shared GuicedEE reusable GitHub Actions job (`.github/workflows/ci.yml`), which expects secrets such as `USERNAME`, `USER_TOKEN`, `SONA_USERNAME`, and `SONA_PASSWORD`.
- Coverage and harness expectations derive from `rules/generative/platform/testing/jacoco.rules.md` and `rules/generative/platform/testing/java-micro-harness.rules.md`.

## 🧰 Troubleshooting & Best Practices
- Verify the serial device path and permissions on your host OS; confirm `.env` values are loaded.
- Start with conservative retry/backoff to avoid device saturation; tune with telemetry feedback.
- Initialize the `rules/` submodule if you need to browse referenced documentation.

## Contributing
- Issues: https://github.com/Activity-Master/CerialMasterClient/issues
- Pull Requests: https://github.com/Activity-Master/CerialMasterClient/pulls
- Please read `PACT.md` and `RULES.md` before contributing; follow the forward-only Documentation-as-Code policy.

## Contribution guidelines
- Treat the Rules Repository as authoritative: link to `rules/` paths for stacks, do not duplicate definitions, and keep all new documentation modular (no new monoliths or archived anchors).
- Keep project-specific docs outside `rules/`. If you must update the submodule, run `git submodule update --remote rules`, verify the new pointer, and commit the change separately.
- Respect the forward-only policy: do not leave behind legacy docs/anchors; remove or replace them when you evolve the architecture.
- Before Stage 4 code changes, revisit `IMPLEMENTATION.md` and `docs/architecture/` to verify that implementation work stays traceable to the documented plan.

## License
- Apache 2.0 (inherited from the parent `activitymaster-group` project and consistent with the rules repository adoption).
