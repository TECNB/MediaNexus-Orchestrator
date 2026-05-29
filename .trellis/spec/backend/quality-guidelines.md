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
- Do not implement Anime, Emby, Ani-RSS, OpenList, or related integration
  business logic during the bootstrap task.

---

## Required Patterns

- Use Java 17 and Spring Boot 3.x for the Java backend.
- Use Maven as the build tool.
- Keep API routes versioned under `/api/v1`.
- Use the unified `ApiResponse<T>` envelope for controller responses.

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
