# Anagno — repository instructions for Codex

## Mission
Maintain and improve Anagno, an Android comic/manga reader. Prioritize correctness, responsiveness, offline reading, and safe incremental improvements. Do not assume paths, APIs, or behavior: inspect the repository first.

## Known stack (verify against repository)
Kotlin, Jetpack Compose, Hilt, Room, Material 3, PDF/CBZ/CBR support. Important features include Guided View, Bubble Zoom, 3D page turn, collections, and reading state. Known test devices: Pixel 8 Pro and Samsung A12.

## Workflow
1. Before edits: read relevant code, identify the active implementation, and record a baseline. Search for legacy/duplicate detection implementations and call sites.
2. For complex or risky work, have the lead delegate independent investigation to `vision`, `android`, `performance`, and/or `qa`; request `reviewer` after integration. Do not spawn every agent for simple changes.
3. Each agent reports: findings, evidence, files examined, proposed change, risks, and tests. Research agents must not edit production files unless delegated explicit ownership.
4. Assign file ownership before parallel edits. Avoid simultaneous modification of the same files. Use separate branches/worktrees for competing implementations.
5. Make the smallest coherent change. Do not combine bubble detection with unrelated refactors.
6. Preserve public behavior unless change is explicitly requested. Do not delete existing features, migrate databases, change navigation, or swap dependencies without justification and approval.
7. Run the available Gradle checks (discover module names and tasks first). Report exact commands, outputs and tests not run. Never claim a device test was performed unless captured evidence exists.
8. Do not push, merge, publish releases, or overwrite user work unless explicitly requested. Never force-push or use destructive git commands on shared work.
9. Final answer: summary, files changed, validation/results, regressions/risks, and next action.

## Bubble Zoom / detection acceptance rules
- Create a reproducible dataset with representative pages, including varied bubble shapes, tails, small text, no-text bubbles, dense panels and false-positive regions. Use licensed/user-owned images only; don't commit copyrighted sample pages without rights.
- Keep page and coordinate system consistent across decode, detection, zoom and render. Explicitly test image scaling and crop mapping.
- Track baseline metrics: bubble recall, false positives/page, boundary quality (IoU where masks exist), end-to-end latency, memory/peak allocations, and device-specific performance.
- A candidate is not 'better' based on one screenshot. Compare identical pages and settings to baseline. Capture failures and classify them.
- Prefer offline and deterministic operation, with graceful degradation on the Samsung A12. Avoid network dependencies in the reader's detection path unless explicitly approved.
- Never silently remove working fast paths or cache safeguards.

## Collaboration
- Lead architect owns integration and final recommendations.
- `vision`: detection algorithm and dataset/evaluation design, with isolated prototypes.
- `android`: production Kotlin integration, app architecture and build health.
- `performance`: profiling and optimization, device-specific constraints.
- `qa`: repeatable tests and regression evidence.
- `reviewer`: independent read-only code review and merge gate.
- Read `docs/agent-workflow/WORKFLOW.md` for detailed checkpoints.
