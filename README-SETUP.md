# Install Anagno Codex agent kit

This is an additive package; do not replace existing repository files blindly. Review/merge any existing AGENTS.md, .codex/config.toml and workflows before copying.

## Install
1. Create a new branch in your *actual* Anagno clone: `git switch -c chore/codex-agent-workflow`.
2. Copy the contents of this kit (including hidden `.codex` and `.github` directories) to the repository root; merge any existing files by hand.
3. Check `git status` and review config. The GitHub Action assumes a module named `app`, Gradle wrapper present and Java 17; verify against the repo before enabling it.
4. Run `./gradlew tasks --all` and identify real Android build/test tasks. Edit the workflow accordingly.
5. Start Codex in the repository and paste `docs/agent-workflow/FIRST-TASK.md`. In Codex CLI, `/subagents` can show active agents.
6. Commit only the setup changes after review; open a PR. Do not merge until CI is working.

## Device testing
CI validates builds/unit tests; it does **not** replace Pixel 8 Pro and Samsung A12 testing. Use manual test matrix and save real evidence before claiming on-device performance improvement.

## Notes
Agent definitions live in `.codex/agents/*.toml` and use Codex custom-agent schema (`name`, `description`, `developer_instructions`). Project config limits to four simultaneous spawned-agent threads to keep cost/work manageable. Agent spawning is requested by the task and AGENTS.md; actual execution depends on the installed Codex version and session.
