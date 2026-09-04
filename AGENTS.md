# Interview Project Agent Instructions

## Project layout

- `backend-java/`: Java 17, Spring Boot, GraphQL, REST, JPA, and H2.
- `frontend/`: Next.js, React, TypeScript, Apollo Client, and Tailwind CSS.

## Working principles

- Optimize for a correct, customer-friendly end-to-end result, not the amount of code generated.
- Keep V1 small and coherent. Avoid speculative abstractions or unrelated cleanup.
- Make decisions and trade-offs explicit instead of silently choosing an architecture.
- Preserve unrelated user changes.
- Do not commit, push, deploy, or add production dependencies unless the user explicitly requests it.

## Planning before implementation

Before coding, the primary agent must give the user a concise, reviewable plan that includes:

1. The target user and complete user journey.
2. The V1 scope and explicitly deferred behavior.
3. The proposed data model and API contract.
4. Implementation milestones: data model, backend/API, frontend, error handling, and verification.
5. Observable acceptance criteria and how each criterion will be verified.

Ask the user about ambiguities that materially change product behavior or architecture. If the request is already precise, state reasonable assumptions and proceed without unnecessary delay.

## Agent coordination

Use the project-scoped custom agents when the user requests delegation, parallel agent work, or an end-to-end feature:

1. Establish the V1 boundary and cross-layer API contract.
2. Assign Java service and API work to the `backend` agent.
3. Assign Next.js UI and client integration work to the `frontend` agent.
4. Run backend and frontend work in parallel only when the contract is clear and file ownership does not overlap.
5. Integrate and inspect both results.
6. Assign the combined result to the `verifier` agent.
7. Route failed checks or concrete findings back to the responsible implementation agent, then re-run verification.
8. The primary agent owns requirements, decisions, conflict resolution, and the final response.

If the GraphQL contract must change, establish the backend schema first, then update frontend operations and generated types. For a task confined to one layer, use only that layer's agent, followed by the verifier when the task risk or user request justifies it.

While subagents run, the primary agent must continue useful work: inspect existing behavior, refine acceptance criteria, prepare integration checks, review completed output, and communicate material changes in direction. Do not duplicate edits owned by a running agent.

## Ownership

- The `backend` agent owns files under `backend-java/`, including the GraphQL schema.
- The `frontend` agent owns files under `frontend/`, including GraphQL operations and generated TypeScript.
- The `verifier` agent reviews and runs checks but does not edit application source files.
- Agents may cross ownership boundaries only when the primary agent explicitly reassigns a file or follow-up task.

## Closed-loop execution

Every implementation task must follow this loop:

1. Inspect the relevant code and state the expected outcome.
2. Implement the smallest complete change.
3. Run the most relevant automated check or behavioral probe.
4. Diagnose and fix failures caused by the change.
5. Re-run the affected check and report the final evidence.

Do not rerun an unchanged failing command without a new hypothesis or corrective action. If verification cannot run, report the exact blocker and the unverified behavior.

## Product and communication expectations

- Evaluate the full user journey, including loading, empty, success, validation, failure, and retry behavior.
- Consider usability, accessibility, and clear feedback as part of correctness.
- Explain significant technical decisions and trade-offs as work progresses.
- Surface incorrect assumptions and change direction explicitly when evidence invalidates the plan.
- Once V1 is verified, suggest at most one small, time-boxed extension. Do not implement it without user direction.

## Agent handoff format

Each subagent must return:

- Scope understood and assumptions made.
- Decisions and meaningful trade-offs.
- Files changed.
- Verification commands or manual checks performed, with results.
- Remaining risks, gaps, or decisions for the primary agent.

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

For cross-stack changes, verify the affected API and UI journey against a running backend when practical. A task is complete only when its acceptance criteria have evidence or are explicitly marked unverified with a reason.
