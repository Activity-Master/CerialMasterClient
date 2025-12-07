# Architecture Documentation

This folder contains the Stage 1 architecture bundle for `com.guicedee.activitymaster.cerialmaster.client`. All diagrams are text-based Mermaid rendered through the Mermaid MCP server (`.mcp.json`) so they stay diff-friendly and traceable to source code. Treat these diagrams as the current truth; forward-only edits should extend or replace them without keeping legacy anchors.

## Diagram Index
- [Context diagram](c4-context.md): caller boundary, client library boundary, and external integrations.
- [Container diagram](c4-container.md): Timed sender engine, multi-port manager, registries, telemetry.
- [Component diagram for the sender engine](c4-component-sender.md): queue managers, attempt coordination, telemetry publisher.
- [Dependency and integration map](dependency-map.md): internal/external dependencies, generators, and observability.
- [Sequence diagram: send-message-flow](sequence-send-message.md): single message lifecycle and telemetry.
- [Sequence diagram: group-processing-flow](sequence-group-processing.md): group and priority promotion.
- [ERD: messaging-domain](erd-messaging-domain.md): in-memory DTO relationships (Config, MessageSpec, stats, snapshots).

## Trust boundaries and threat notes
- ActivityMaster callers are trusted within the library boundary; COM port drivers, SPI listeners, and TypeScript emission are outside the boundary and require validation plus Log4j2 audit logging.
- Mutiny streams and Vert.x publishers are controlled channels; enforce backpressure and disable publishing when consumers are untrusted.
- GuicedEE service-loader modules and port drivers are the main threat vectors (misconfiguration, malformed payloads). Mitigate through module-info.java requirements, registry validation, and consistent telemetry snapshots for forensic review.

## Interaction and data flows
- Sequence diagrams cover enqueue → attempt → retry/timeout → status emission. Use them when adjusting queue behavior or Mutiny emissions.
- The dependency map shows how pom.xml, GuicedEE services, Vert.x publishing, and TypeScript generation compose around the TimedComPortSender core.
- The ERD highlights which DTOs are persisted in memory; align any new DTOs with the glossary and the rules under `rules/generative/data/activity-master/cerial-client`.

All artifacts are linked from `docs/PROMPT_REFERENCE.md` so future prompts reload this architecture bundle before altering code or rules.
