# Glossary

## Glossary precedence policy
1. **Topic-first**: Host-level entries only introduce terms that are not already covered by the selected topic glossaries. Every term below links back to a topic-specific glossary so that the topic definition is authoritative.
2. **Minimal duplication**: When a topic glossary already defines a concept, reference it instead of repeating it. This keeps the host glossary as an index rather than a sworn transcript.
3. **Prompt-language mappings** are only copied when explicitly enforced by the prompt (e.g., WebAwesome does not apply here).

## Terms

### Java 25 LTS
Defines the language level, toolchain, and [rules/generative/language/java/java-25.rules.md](rules/generative/language/java/java-25.rules.md) expectations for this client library. The project adheres to the compiler settings, modules, and Null Safety guidelines listed in that topic.

### CRTP (Curiously Recurring Template Pattern)
The enforced fluent API strategy; [rules/generative/backend/fluent-api/crtp.rules.md](rules/generative/backend/fluent-api/crtp.rules.md) explains how sender classes return `(J)this` and avoid Lombok `@Builder` when GuicedEE is in play.

### GuicedEE Client Services
This library is a GuicedEE client module. Follow [rules/generative/backend/guicedee/client/README.md](rules/generative/backend/guicedee/client/README.md) for SPI discovery, lifecycle hooks, and Mutiny-friendly wiring used in `ComPortConnection`.

### ActivityMaster Cerial Client Domain
Refer to [rules/generative/data/activity-master/cerial-client/README.md](rules/generative/data/activity-master/cerial-client/README.md) for domain modeling, Config/MessageSpec naming, telemetry expectations, and Vert.x publishing defaults for `TimedComPortSender` and `MultiTimedComPortSender`.

### Log4j2 Logging
Log4j2 is the required logging backend; [rules/generative/backend/logging/LOGGING_RULES.md](rules/generative/backend/logging/LOGGING_RULES.md) describes @Log4j2 usage plus appenders that capture retries/kpi events emitted by the sender.

### Lombok + JSpecify
Lombok keeps the DTOs terse, and JSpecify annotations lock nullness contracts. The interplay is documented in [rules/generative/backend/lombok/README.md](rules/generative/backend/lombok/README.md) and [rules/generative/backend/jspecify/README.md](rules/generative/backend/jspecify/README.md).

### MapStruct 6
Any DTO mapping (e.g., status snapshots) should follow [rules/generative/backend/mapstruct/mapstruct-6.md](rules/generative/backend/mapstruct/mapstruct-6.md) for null-safe, constructor-driven mapper builds aligned with CRTP.

### Jacoco & Java Micro Harness
Unit and micro-harness coverage targets are defined by [rules/generative/platform/testing/jacoco.rules.md](rules/generative/platform/testing/jacoco.rules.md) and [rules/generative/platform/testing/java-micro-harness.rules.md](rules/generative/platform/testing/java-micro-harness.rules.md). Use these to assert coverage bands and long-running harness tests for `TimedComPortSender` behaviors.

### GitHub Actions CI
CI/workflow guidance comes from [rules/generative/platform/ci-cd/providers/github-actions.md](rules/generative/platform/ci-cd/providers/github-actions.md). The new workflow references the `GuicedEE/Workflows` reusable job and documents required secrets.

### Document Modularity & Forward-Only
The host repository honors the Document Modularity Policy and Forward-Only Change Policy described in [rules/RULES.md](rules/RULES.md). That means each section above links to modular docs and we remove legacy anchors instead of keeping them.
