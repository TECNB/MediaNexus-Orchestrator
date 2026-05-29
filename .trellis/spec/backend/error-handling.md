# Error Handling

> How errors are handled in this project.

---

## Overview

Backend API responses use a small unified envelope. The first skeleton keeps
error handling intentionally simple and avoids a large error taxonomy before
business modules exist.

---

## Error Types

- `ErrorCode` stores common business codes and default messages.
- `BusinessException` carries a business code and message for expected
  application-level failures.
- Unexpected exceptions are handled by `GlobalExceptionHandler` and returned as
  `INTERNAL_ERROR`.

---

## Error Handling Patterns

- Controllers should return `ApiResponse<T>` for normal responses.
- Throw `BusinessException` for expected business failures.
- Let unexpected exceptions propagate to `GlobalExceptionHandler`.
- Do not return raw maps or ad hoc JSON response objects from controllers.

---

## API Error Responses

Success response:

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

Error response:

```json
{
  "code": 500,
  "message": "internal server error",
  "data": null
}
```

For `GET /api/v1/health`, the success payload is:

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "status": "UP",
    "service": "MediaNexus-Orchestrator"
  }
}
```

---

## Common Mistakes

- Do not introduce per-endpoint response envelopes.
- Do not leak stack traces or exception class names in API responses.
