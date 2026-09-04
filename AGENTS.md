# Interview Project Agent Instructions

## Project layout

- `backend-java/`: Java 17, Spring Boot, GraphQL, REST, JPA, and H2.
- `frontend/`: Next.js, React, TypeScript, Apollo Client, and Tailwind CSS.

## Agent coordination

Use the project-scoped custom agents when the user requests delegation, parallel agent work, or an end-to-end feature:

1. Assign Java service and API work to the `backend` agent.
2. Assign Next.js UI and client integration work to the `frontend` agent.
3. Wait for implementation work to finish.
4. Assign the combined result to the `verifier` agent.
5. The primary agent owns requirements, cross-layer decisions, conflict resolution, and the final response.

Backend and frontend agents may work in parallel only when the API contract is already clear and they will edit separate files. If the GraphQL contract must change, establish the backend schema first, then update frontend operations and generated types.

For a task confined to one layer, use only that layer's agent, followed by the verifier when the risk or user request justifies it.

## Ownership

- The `backend` agent owns files under `backend-java/`, including the GraphQL schema.
- The `frontend` agent owns files under `frontend/`, including GraphQL operations and generated TypeScript.
- The `verifier` agent reviews and runs checks but does not edit application source files.
- Agents must preserve unrelated user changes.
- Agents must not commit, push, deploy, or add production dependencies unless the user explicitly requests it.

## Verification commands

Backend:

```bash
cd backend-java
./gradlew test
```

Frontend:

```bash
cd frontend
npm run typecheck
npm run build
```

For cross-stack changes, verify the affected UI behavior against a running backend when practical.
