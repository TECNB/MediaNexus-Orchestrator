# Quality Guidelines

> Code quality standards for backend development.

---

## Overview

Keep early backend tasks narrow and runnable. The first Java backend phase
should establish foundations without pulling in large platform choices before
they are needed.

---

## Forbidden Patterns

- Do not add PostgreSQL, JPA, Flyway, Actuator, Testcontainers, Spring Cloud,
  message queues, or WebFlux during the bootstrap task.
- Do not create a multi-module Maven project during the bootstrap task.
- Do not implement unrelated Anime, Emby, Ani-RSS, OpenList, or related
  integration business logic outside an explicit task scope.

---

## Required Patterns

- Use Java 17 and Spring Boot 3.x for the Java backend.
- Use Maven as the build tool.
- Keep API routes versioned under `/api/v1`.
- Use the unified `ApiResponse<T>` envelope for controller responses.
- Prefer JDK and existing Spring Boot dependencies for small integrations
  before adding new client libraries.

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

- Add focused tests when business logic or changed behavior is introduced.
- For the bootstrap task, static review and minimal syntax checks are acceptable
  unless the user explicitly asks for a local Maven build/test run.

---

## Code Review Checklist

- Confirm excluded dependencies are not present in `pom.xml`.
- Confirm no domain business packages were introduced in bootstrap work.
- Confirm `/api/v1/health` returns the unified response envelope.
- Confirm README startup instructions match `application.yml`.
