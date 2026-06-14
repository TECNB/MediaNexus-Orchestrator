<!-- 本文件为 Claude Code 的项目规则，内容镜像自 AGENTS.md（Codex/通用规则的来源）。两者要求保持一致；修改规则时请同步更新 AGENTS.md。 -->
<!-- TRELLIS:START -->
# Trellis Instructions

These instructions are for AI assistants working in this project.

This project is managed by Trellis. The working knowledge you need lives under `.trellis/`:

- `.trellis/workflow.md` — development phases, when to create tasks, skill routing
- `.trellis/spec/` — package- and layer-scoped coding guidelines (read before writing code in a given layer)
- `.trellis/workspace/` — per-developer journals and session traces
- `.trellis/tasks/` — active and archived tasks (PRDs, research, jsonl context)

If a Trellis command is available on your platform (e.g. `/trellis:finish-work`, `/trellis:continue`), prefer it over manual steps. Not every platform exposes every command.

On Claude Code, Trellis context is auto-injected via the SessionStart / UserPromptSubmit hooks in `.claude/`, and Trellis skills under `.claude/skills/` are auto-invoked. Equivalent helpers for other tools live in `.agents/skills/` (reusable Trellis skills) and `.codex/agents/` (optional custom subagents).

Managed by Trellis. Edits outside this block are preserved; edits inside may be overwritten by a future `trellis update` (note: `trellis update` currently rewrites this block in AGENTS.md only — keep this copy in sync manually).

<!-- TRELLIS:END -->

## Backend Logs

User owns backend start/stop. For errors, read `logs/dev-run.log` first, usually with `tail -n 200`.

```bash
mkdir -p logs
mvn spring-boot:run 2>&1 | tee logs/dev-run.log
```

## Commit Rules

- This is a hard requirement, not a preference: every commit in this repository must use `TECNB <3489044730@qq.com>`.
- Before creating, amending, rebasing, cherry-picking, or force-pushing commits, run or otherwise verify `git config user.name` and `git config user.email`.
- Commit messages must use Conventional Commit prefixes and Chinese descriptions.
- Keep the prefix in English and the summary in Chinese, for example:
  - `feat: 接入动漫季度磁力导入编排`
  - `fix: 修复 Ani-RSS 搜索路由`
  - `chore: 忽略 Codex 工作区记录`
- Forbidden: English summaries after the prefix, such as `feat: add anime season magnet ingest orchestration` or `chore: ignore codex workspace notes`.
- If a recent commit violates these rules, fix it before pushing; if it has already been pushed, ask for approval and repair it with `git push --force-with-lease`.

## Before acting

- If the request is ambiguous, state assumptions or ask — don't silently
  pick one reading and build it.

## When editing existing code

- Change only what the request requires. Don't refactor or restyle working
  code you weren't asked to touch. Match the existing style.

## Design Rules (strict)

Before changing code, check the rules below. If a change would violate one,
stop and explain the smaller redesign first.

Do not fix a banned smell by changing its shape: bool → enum/options,
checks → wrappers, flag/switch → Strategy, pass-through layer → facade/adapter.

1. Names must disambiguate — judge the full name, not the word.
   Test: does the full name state the thing's role? If not, rename.
   Red-flag words that usually fail (NOT a banned list — a self-check trigger,
   not exhaustive): data, info, result, handler, manager, process, utils,
   helper, service, wrapper, thing, value, tmp, obj — and the XxxManager /
   XxxHelper shape. `*_impl` / `do_*` usually signal a redundant layer or
   unnamed responsibility (legitimate only for pimpl).
   e.g. `ConfigManager` → `ConfigStore` / `loadConfig`.

2. Validate once at edges; trust invariants inside. Do not scatter
   defensive checks across trusted internal boundaries. No repeated
   `if x is None: return` / `if (!ptr) return -1;`. If the same check
   appears 3+ times, redesign the boundary. Only delete an internal check if
   some edge provably establishes that invariant — removing it without an
   upstream guarantee introduces a bug.

3. Comments document contracts, invariants, rationale, constraints, and
   rejected alternatives. Do not narrate code or compensate for bad
   names/boundaries.

4. No flag that makes one function behave like two. If a bool/enum/
   string/options arg switches the function between modes, split it into
   separate operations. A bool that is just a value (`setEnabled(bool)`) is
   fine; a param object whose fields are always all relevant is fine; a
   grab-bag that toggles behavior is not. Do not escape by reshaping
   bool → enum → options → Strategy.
   e.g. `save(bool publish)` → `saveDraft()` / `publish()`.

5. Right owner, complete operation. Put complexity where the decision,
   invariant, or external dependency lives. Expose complete operations, not
   caller-managed steps. Add no API/layer unless it hides caller knowledge,
   enforces an invariant, or adapts an external dependency. Do not stuff
   unrelated behavior together just to keep the API small.

## Stop signals (redesign, don't push through)

- One change spreads across many files → wrong owner or duplicated
  knowledge, not more patches.
- Naming gets hard, or a comment is explaining around an awkward interface
  → suspect the abstraction boundary before adding more words.

Scope: these rules govern the code you add or change — neither an excuse to
skip review nor a license to refactor untouched code.
