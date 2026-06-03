# Logging Guidelines

> How logging is done in this project.

---

## Overview

Backend services use SLF4J for application logs and domain log tables for
user-facing long-running task progress. Keep these two audiences separate:
server logs are for operators and debugging, while task log rows are part of
the product workflow.

---

## Log Levels

- Use `INFO` task logs for normal lifecycle milestones: created, submitted,
  downloading, organizing, succeeded.
- Use `WARN` task logs for recoverable or partial outcomes that the user may
  need to review, such as skipped files or cleanup failures.
- Use `ERROR` task logs for terminal failures.
- Use SLF4J `warn`/`error` for backend exceptions that operators may need to
  inspect; do not rely on user-facing task logs as the only diagnostic trail.

---

## Structured Logging

- Long-running task log rows should carry the task id, stage, level, user-facing
  message, and optional detail.
- Keep stage values aligned with task state transitions. A status/stage update
  and its task log entry should be owned by one semantic phase operation rather
  than by scattered caller-managed `updateTask(...)` and `writeLog(...)` pairs.

---

## What to Log

- Log external task submission ids when they are safe and useful for support,
  such as OpenList offline task ids.
- Log path-level details for file organization decisions when they help the
  user understand skipped, moved, renamed, or deleted files.
- Log terminal task counts such as organized and skipped file totals on success
  or partial success.

---

## What NOT to Log

- Do not log authorization tokens, API keys, passwords, or raw secret-bearing
  configuration values.
- Do not put full magnet links in user-facing logs unless the workflow
  explicitly requires exposing them; prefer task ids, hashes, or sanitized
  context.
