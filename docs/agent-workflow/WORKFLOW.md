# Anagno multi-agent workflow

## Branches and ownership
Use `main` as protected integration branch. Create `feat/<topic>` or `fix/<topic>` branches. For independent algorithm experiments, use separate Git worktrees/branches; don't let two agents write the same file in the same worktree. Lead architect assigns a single integration owner.

## Stage 0: Audit (first run)
- Locate entry points for OCR, bubble detection, segmentation, overlays, bubble zoom, panel detection, cache, and render transforms.
- Map call graph; list stale duplicates and any previous experimental paths, without deleting them.
- Determine Gradle modules and executable build/test tasks.
- Record a clean baseline commit and results, plus any environment blockers.

## Stage 1: Proposal
Lead defines explicit scope, success metrics, non-goals and rollback path. Research can run in parallel if questions are independent. Agent reports must include evidence and file ownership.

## Stage 2: Evaluation
Use fixed rights-cleared pages with diverse layouts; label ground-truth bubble boxes/masks or record counts when pixel masks unavailable. Evaluate baseline vs candidate on identical decoding/resolution settings. Record per-page false positives/misses, quality and latencies. Keep metrics as TBD until actually measured.

## Stage 3: Integration
A designated Android owner integrates an approved solution using small diffs. Add tests around coordinate mappings and no-regression behavior. Run `./gradlew tasks --all` first to identify valid task names; prefer `./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest` only if the app module and tasks exist.

## Stage 4: Verification
1. Build and unit tests (CI).
2. Detection dataset regression comparison.
3. Manual launch, import CBZ/CBR/PDF, navigate, Guided View, Bubble Zoom, bookmarks/progress and 3D page turn (as relevant).
4. Physical Pixel 8 Pro and Samsung A12 latency, crashes and memory observations; untested checks = BLOCKED, not PASS.

## Stage 5: Review / merge
Reviewer checks diff and records PASS/FAIL/BLOCKED with evidence. Do not merge when build fails or a severe regression remains. Lead summarizes trade-offs; owner approves merge. Keep rollback as revertable commit/PR.

## Suggested initial issue: Bubble Zoom V-next investigation
Investigate why earlier detectors missed bubbles, distorted boundaries, and lost performance; compare OCR-guided region proposal with classical edge/contour processing and a compact segmentation option. Do not implement a fourth approach until baseline failure categories have been measured.
