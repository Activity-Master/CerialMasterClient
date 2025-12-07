# Cerial Master Client Implementation

## Module layout
- `pom.xml` inherits from `com.guicedee.activitymaster:activitymaster-group:3.0.0-SNAPSHOT`. Dependencies include `activity-master-client`, `typescript-client`, `guiced-cerial`, and Log4j2 (as declared in the parent). All code compiles at Java 25 and exposes JPMS modules via the existing `module-info.java` files.
- `rules/` remains a Git submodule; the actual implementation files live in `src/main/java` and `src/test/java`.

## Key packages
- `com.guicedee.activitymaster.cerialmaster.client`: hosts domain DTOs (`MessageSpec`, `MessageResult`, `MessageProgress`, `MessageStat`, `Config`, etc.) plus the core scheduler `TimedComPortSender` and `MultiTimedComPortSender` used by ActivityMaster controllers.
- `com.guicedee.activitymaster.cerialmaster.client.services`: defines SPI contracts (`IComPortStatusChanged`, `IReceiveMessage`, `IErrorReceiveMessage`, `ITerminalReceiveMessage`) that service loaders populate through GuicedEE (see `rules/generative/backend/guicedee/services/services.md`).
- `com.guicedee.activitymaster.cerialmaster.client.services` also holds `IComPortStatusChanged` callbacks that `ComPortConnection` notifies when the serial port status changes.

## Architecture wiring
- Refer to `docs/architecture/c4-context.md`, `c4-container.md`, `c4-component-sender.md`, and `docs/architecture/dependency-map.md` for how the Timed Sender, connection registries, telemetry streams, and external integrations interact.
- Sequence diagrams (`docs/architecture/sequence-send-message.md`, `sequence-group-processing.md`) capture retry lifecycles, queue promotions, and status emissions.
- The `docs/architecture/erd-messaging-domain.md` ERD documents the in-memory relationships between `MessageSpec`, `Config`, `GroupResult`, `MessageResult`, `MessageStat`, and `SenderSnapshot`.

## Testing
- `src/test/java/com/.../NotifiesAdherenceTest` and `testimpl/TestStatusListener` validate actual Mutiny streams. Tests should continue to target the same packages, while abiding by `rules/generative/platform/testing/jacoco.rules.md` for coverage.
- Jacoco coverage reports get generated during the shared GitHub Actions workflow (see `.github/workflows/ci.yml`).

## Observability & Releases
- `TimedComPortSender` exposes `Multi<StatusUpdate>` and `Multi<MessageProgress>` for downstream subscribers; register snapshot consumers via `onStatisticsUpdated` as shown in the source.
- The final release process must mention this doc set (PACT, RULES, GUIDES, GLOSSARY, IMPLEMENTATION, docs/architecture, README) so adopters can trace requirements back to the Rules Repository.

## Stage 3 implementation roadmap
- **Scaffolding plan**: Expand the GuicedEE client wiring by documenting new provider modules, aligning `module-info.java`, and keeping the `com.guicedee.activitymaster.cerialmaster.client` package sterile of legacy anchors (per Document Modularity policy). Rules live under `rules/generative/data/activity-master/cerial-client`.
- **Build and annotation plan**: Keep Maven aligned with `rules/generative/language/java/build-tooling.md` by vetting annotation processors (MapStruct, Lombok) and arithmetic autop-run tests during CI. Future modules must declare dependencies in `pom.xml` and update `module-info.java`.
- **CI and validation**: CI uses the reusable GuicedEE GitHub Actions job. Validation is driven by Jacoco/Java Micro Harness instrumentation plus the existing Mutiny-based tests. Each stage should emit status updates via `StatusUpdate` and `MessageProgress` Multi streams so integration harnesses can assert proper retries/timeouts.
- **Rollout plan and risk mitigations**: Stage 1 architecture verification is refreshed. Stage 2 doc alignment lands in RULES/GUIDES/GLOSSARY/IMPLEMENTATION plus the new rules repository location. Stage 3 will add implementation scaffolding or refactors only once new requirements are requested. Risks include serial port hardware behavior (unreliable hardware handshake) and service loader resolution; mitigation is thorough testing/observability and consistent registration via `ComPortConnection.PORT_CONNECTIONS`.
- **Validation approach**: Use `NotifiesAdherenceTest`, `TestStatusListener`, and any new test harness (Java Micro Harness) to exercise paused/completed/cancelled states. Capture telemetry snapshots described in architecture docs for traceability.
