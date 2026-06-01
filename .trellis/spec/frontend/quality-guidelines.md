# Quality Guidelines

> Code quality standards for frontend development.

---

## Overview

<!--
Document your project's quality standards here.

Questions to answer:
- What patterns are forbidden?
- What linting rules do you enforce?
- What are your testing requirements?
- What code review standards apply?
-->

(To be filled by the team)

---

## Forbidden Patterns

<!-- Patterns that should never be used and why -->

(To be filled by the team)

---

## Required Patterns

<!-- Patterns that must always be used -->

(To be filled by the team)

## Commit Requirements

- Every commit in this repository must use the author identity `TECNB <3489044730@qq.com>`.
- Treat this as a required commit gate: verify the configured author before creating, amending, rebasing, cherry-picking, or force-pushing commits.
- Commit messages must follow the basic Conventional Commit shape while keeping the description in Chinese.
- Valid examples:
  - `feat: 接入动漫季度磁力导入编排`
  - `fix: 修复 Ani-RSS 搜索路由`
  - `chore: 忽略 Codex 工作区记录`
- Keep prefixes such as `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, and `test:` in English.
- Do not use English descriptions after the prefix; `feat: add anime season magnet ingest orchestration` and `chore: ignore codex workspace notes` are invalid.
- Before pushing, inspect recent commits with `git log --format='%h %an <%ae> %s'` and repair violations first.
- If a bad commit is already on the remote, ask for approval and rewrite it with `git push --force-with-lease`.

---

## Testing Requirements

<!-- What level of testing is expected -->

(To be filled by the team)

---

## Code Review Checklist

<!-- What reviewers should check -->

(To be filled by the team)
