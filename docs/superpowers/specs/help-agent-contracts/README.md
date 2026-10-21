# Help Agent Contracts (Design Code)

**Design:** `../2026-07-21-enterprise-help-support-agent-design.md`  
**Status:** Design-normative only — not on the production classpath.

When implementing P0:

1. Port Python models into `aegis-alpha-orchestrator/app/models/`.
2. Port Java DTOs into `com.aegis.alpha.service.help` (or `domain`).
3. Copy playbooks into `aegis-alpha-api/src/main/resources/help/playbooks/`.
4. Wire golden20 into API/orchestrator tests.

Do **not** import these files from runtime code.
