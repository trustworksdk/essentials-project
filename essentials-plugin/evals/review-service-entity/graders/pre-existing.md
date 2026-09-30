---
type: llm
---

The agent's final message is the report `/essentials:review` printed for a change to a test project. The project already had stack-contract problems before the change (missing S2.1 dependencies, no `-parameters`, no Spring Boot parent, no types-spring-web, no OpenAPI contract).

PASS if the report counts those as pre-existing (a number, not a list of rows) and points to `/essentials:upgrade` for them, while listing as findings only what the change introduced.
FAIL if it lists the pre-existing stack findings as finding rows, or does not mention them at all.
