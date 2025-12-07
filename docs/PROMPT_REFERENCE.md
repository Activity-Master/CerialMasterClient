# Prompt Reference

## Selected Stacks & Policies
- Java 25 LTS (rules/generative/language/java/java-25.rules.md).
- Specification-Driven Design, DDD, and Documentation-as-Code (per PROMPT input).
- TDD attitude with Jacoco, Java Micro Harness, Log4j2 logging, Lombok + JSpecify, CRTP fluent API conventions.
- GuicedEE client-oriented services plus Activity Master data stacks. Use Cerial-specific rules under `rules/generative/data/activity-master/cerial-client` for this addon; the core Activity Master client library remains at `rules/generative/data/activity-master/client`.
- GitHub Actions CI guidance (rules/generative/platform/ci-cd/providers/github-actions.md).
- MCP servers: load `.mcp.json` to register the Mermaid MCP HTTP endpoint before rendering or updating diagrams.

## Architecture Diagrams (Stage 1)
- [Context overview](architecture/README.md#diagram-index)
- [C4 context diagram](architecture/c4-context.md)
- [C4 container diagram](architecture/c4-container.md)
- [C4 component diagram for the sender engine](architecture/c4-component-sender.md)
- [Dependency and integration map](architecture/dependency-map.md)
- [Sequence flow: send-message](architecture/sequence-send-message.md)
- [Sequence flow: group-processing](architecture/sequence-group-processing.md)
- [ERD: messaging domain](architecture/erd-messaging-domain.md)

## Forward-Only & Document Modularity Touchpoints
- Architecture documentation references actual Java packages (`com.guicedee.activitymaster.cerialmaster.client`) and existing services without inventing new modules.
- Later deliverables (GLOSSARY.md, RULES.md, GUIDES.md, IMPLEMENTATION.md) link back to these diagrams to close the loop (PACT ↔ docs ↔ RULES). Host docs use `rules/generative/data/activity-master/cerial-client` for this library while continuing to reference `rules/generative/data/activity-master/client` when the core Activity Master client applies.
- Glossary composition is topic-first: defer to `rules/generative/data/activity-master/cerial-client/GLOSSARY.md`, then `rules/generative/data/activity-master/GLOSSARY.md`, and only include host-level terms not already defined there.

## Next-stage reminders
- Stage 2 aligns RULES/GUIDES/GLOSSARY/IMPLEMENTATION against the refreshed architecture and new rules repository location.
- Stage 3 plans implementation and publishing steps; Stage 4 may proceed without pauses because blanket approval is active for this run.

*This file must be loaded by future prompts to honor the adoption of the Rules Repository and keep the architecture bundle in sync.*
