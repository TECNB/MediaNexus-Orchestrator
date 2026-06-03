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
- Keep configuration defaults in `application.yml` / imported environment
  placeholders, not repeated in `@ConfigurationProperties` fields and again in
  service/client fallback logic.
- Use `@ConfigurationProperties` classes for binding and unconditional
  validation only. For values that are always required once the application
  starts, prefer `@Validated` with Bean Validation annotations over hand-written
  getter or setter fallback.
- Optional integration credentials may stay optional at startup. Validate them
  at the operation boundary that actually uses the external dependency.
- For conditionally enabled features, validate required options in the owner
  operation when the feature is enabled, rather than failing application startup
  for disabled functionality.
- Business services should call complete, semantic operations. Do not scatter
  caller-managed step pairs such as `updateTask(...)` followed by `writeLog(...)`
  through orchestration flows when a phase method can own the state/log contract.
- Java 17 is the backend target. Java 9+ APIs such as `Map.of(...)` and
  `List.of(...)` are acceptable here, but call out the version requirement when
  discussing Java 8 compatibility.

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
- Do not run full Maven build/test/typecheck commands unless the user explicitly
  asks or the task is specifically about fixing validation failures. Prefer
  focused checks such as `git diff --check`, targeted file inspection, or small
  scoped commands.

## Configuration Ownership

- `application.yml` is the single source for default values that can be
  overridden by `.env` or environment variables.
- `@ConfigurationProperties` classes should expose the bound value directly.
  Avoid `getEffective...()` methods and avoid repeated `null/zero/negative`
  fallback logic in consumers.
- Use validation annotations for unconditional invariants such as non-blank
  tool names, positive `Duration` values, and non-negative retry limits.
- Use runtime validation at the right owner for conditional invariants. Example:
  a disabled SSH tunnel should not make the application fail to start because
  tunnel credentials are blank; the tunnel lifecycle should validate those
  fields only when it starts the tunnel.
- If a configuration field feeds a domain template, avoid Spring placeholder
  syntax in default values. Prefer domain placeholders such as `{title}` over
  `${title}`.

## Scenario: API Documentation Contracts

### 1. Scope / Trigger

- Trigger: adding or changing controller endpoints, request/response DTOs, or
  integration-facing payload fields.
- Goal: make Knife4j/OpenAPI useful for human readers without polluting debug
  forms with fake business data.

### 2. Signatures

- Controllers that expose business APIs should use:
  - `@Tag` at class level.
  - `@Operation` at endpoint level.
  - `@Parameter` only where a path/query parameter needs human-readable
    contract text.
- Request and response DTO records should use `@Schema` on the record and on
  fields whose meaning is not obvious from the Java name alone.
- Service, config, and integration classes should use Javadoc for contracts,
  invariants, upstream behavior, and rationale. Do not add Javadoc to simple
  getters/setters or obvious pass-through code.

### 3. Contracts

- Keep API documentation text in Chinese because this project's user-facing
  messages, logs, and commit summaries are Chinese. Preserve protocol names,
  enum/status literals, route names, and external product names in English.
- Do not use field-level `example = ...` in controller parameters or DTO
  schemas by default. In Knife4j, examples can prefill debug forms with fake
  data and force users to delete placeholders before sending real requests.
- For optional request fields, prefer explicit input semantics:

```java
@Schema(
        description = "Bangumi 条目地址；可不传",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        nullable = true
)
String bgmUrl;
```

- For nullable response fields, state the null condition in the description and
  keep `nullable = true` for the OpenAPI schema:

```java
@Schema(description = "开播日期文本；上游未返回时为 null", nullable = true)
String airDate;
```

- For response enum/status fields, prefer human-readable descriptions over
  `allowableValues`. Knife4j appends `allowableValues` as dense inline text in
  model tables, which is harder to scan for long status lists:

```java
@Schema(description = "任务状态：PENDING、SUBMITTED、DOWNLOADING、ORGANIZING、SUCCEEDED、PARTIAL_SUCCESS、FAILED 或 INTERRUPTED")
String status;
```

- Avoid HTML paragraph tags such as `<p>` in Javadoc. Use blank lines inside
  Javadoc comments for readable source-level paragraphs.

### 4. Validation & Error Matrix

| Condition | Documentation Rule |
|-----------|--------------------|
| Required request field | Explain the requirement in `description`; add Bean Validation later only when runtime validation is intentionally introduced. |
| Optional request field | Use `requiredMode = Schema.RequiredMode.NOT_REQUIRED`; explain fallback/default behavior in `description`. |
| Nullable response field | Use `nullable = true`; state the exact condition that produces `null`. |
| Response status/phase field | List important status literals in `description`; do not use response `allowableValues` unless the UI display is verified to remain readable. |
| Fake sample value would prefill a debug form | Do not add `example`. |
| Complex service or integration behavior | Document the contract/rationale in Javadoc or a short inline comment near the behavior. |

### 5. Good/Base/Bad Cases

- Good: `@Schema(description = "终态任务完成时间；任务未进入终态时为 null", nullable = true)`.
- Base: `@Schema(description = "导入任务标题")` for a straightforward response field.
- Bad: `@Schema(description = "开播日期文本", example = "2023-09-29")` when the value is fake and will prefill Knife4j debug input.
- Bad: `@Schema(description = "任务状态", allowableValues = {...})` on long response enums when Knife4j renders the values as hard-to-read inline text.

### 6. Tests Required

- For documentation-only changes, do not run full Maven build/test commands
  unless explicitly requested.
- Minimum check: inspect Knife4j output manually when a documentation convention
  changes UI behavior, and run a whitespace/conflict-marker check such as
  `git diff --check`.
- If runtime validation annotations are added later, add focused tests for the
  new validation behavior and error response shape.

### 7. Wrong vs Correct

#### Wrong

```java
@Schema(description = "开播日期文本，可能为空", example = "2023-09-29")
String airDate;

@Schema(description = "任务状态", allowableValues = {"PENDING", "SUBMITTED", "DOWNLOADING", "ORGANIZING", "SUCCEEDED", "FAILED"})
String status;
```

#### Correct

```java
@Schema(description = "开播日期文本；上游未返回时为 null", nullable = true)
String airDate;

@Schema(description = "任务状态：PENDING、SUBMITTED、DOWNLOADING、ORGANIZING、SUCCEEDED 或 FAILED")
String status;
```

---

## Code Review Checklist

- Confirm excluded dependencies are not present in `pom.xml`.
- Confirm no domain business packages were introduced in bootstrap work.
- Confirm `/api/v1/health` returns the unified response envelope.
- Confirm README startup instructions match `application.yml`.
