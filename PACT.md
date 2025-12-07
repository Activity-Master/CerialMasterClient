---
version: 2.0
date: 2025-12-07
title: ActivityMaster Cerial Client Pact
project: ActivityMaster / Cerial Client
authors: ["ActivityMaster Engineers", "Codex"]
---

# 🤝 ActivityMaster Cerial Client Pact

## 1. Purpose
This pact keeps the Cerial Master Client adoption aligned across humans and AI. It names the players (ActivityMaster engineers, the Codex assistant) and ties every artifact back to the Rules Repository (via `rules/`), the newly minted `docs/PROMPT_REFERENCE.md`, and the project-specific `PACT ⇄ RULES ⇄ GUIDES ⇄ IMPLEMENTATION` loop.

## 2. Principles
- **Continuity**: Document selections (Java 25, CRTP, GuicedEE client, ActivityMaster data, Log4j2, MapStruct, Lombok, JSpecify, Jacoco/Java Micro Harness, GitHub Actions) stay front-and-center in RULES, GLOSSARY, GUIDES, and implementation plans.
- **Finesse**: Words stay technical, human, and precise; diagrams live in `docs/architecture/` and are rendered via the Mermaid MCP server (`.mcp.json`) so they stay diffable and reviewable.
- **Non-Transactional Flow**: We treat this repo as a living narrative: Stage 1 refreshed architecture docs (this run), Stage 2 rules/guides alignment, Stage 3 implementation plan, Stage 4 implementation scaffolding.
- **Closing Loops**: Every artifact references upstream (`rules/`) and downstream (GUIDES → IMPLEMENTATION) artifacts. Stage gates are recorded here and in `docs/PROMPT_REFERENCE.md`; rules now point to `rules/generative/data/activity-master/cerial-client`.

## 3. Stage gating (Blanket approval)
This run has **blanket approval** for all stages. Stage 1 is refreshed (context/container/component, dependency map, sequences, ERD). Stage 2 delivers RULES/GUIDES/GLOSSARY/IMPLEMENTATION updates; Stage 3 and Stage 4 follow automatically with the new rules repository target (`generative/data/activity-master/cerial-client`). No wait loops are required because the user opted in for blanket approval.

## 4. Documentation-first commitment
- Stage 1: Architecture/diagrams (done in `docs/architecture`).
- Stage 2: Guides and design validation (RULES, GUIDES, GLOSSARY, IMPLEMENTATION). These docs must exist before touching code.
- Stage 3: Implementation planning and wiring (will include CI, env, risk, and staging notes).
- Stage 4: Code and scaffolding updates (only after Stage 3 plan is reviewed, but Stage 2 is already doc-based and will steer Stage 3).

## 5. Collaboration rules
- The AI never fabricates systems; all references are traced to existing code or the `rules/` submodule.
- Document modularity is enforced: giant monoliths are replaced with focused Markdown files (see Document Modularity policy in `rules/RULES.md`).
- Forward-only policy is enforced: no legacy files, anchors, or shims survive this run.

## 6. Closing note
> “Cerial Master Client is guided by the Rules Repository; every doc points back to the rules and forward to impact.”

Artifacts linked here: `GLOSSARY.md`, `RULES.md`, `GUIDES.md`, `IMPLEMENTATION.md`, `docs/PROMPT_REFERENCE.md`, `docs/architecture/`.
