# Directory Structure

> How backend code is organized in this project.

---

## Overview

`MediaNexus-Orchestrator` starts as a single-module Spring Boot application.
Keep the backend monolith simple until real module boundaries emerge from
implemented features.

---

## Directory Layout

```
src/main/java/com/medianexus/orchestrator
├── MediaNexusOrchestratorApplication.java
├── common
│   ├── exception
│   └── response
├── config
├── controller
├── dto
├── integration
└── service

src/main/resources
└── application.yml
```

---

## Module Organization

- API controllers live under `controller` for project-level endpoints such as
  `/api/v1/health`.
- Shared API response and exception primitives live under `common`.
- Framework integration beans live under `config`.
- External service clients live under `integration/<service-name>`.
- Business orchestration and response shaping live under `service`.
- Request/response DTOs live under `dto`, with domain subpackages when needed
  for non-test features.
- Do not create a multi-module Maven layout unless a later task explicitly
  requires it.
- Keep domain packages narrow and task-scoped; do not add unrelated Emby,
  OpenList, Sonarr, Radarr, Prowlarr, PikPak, or Python replacement logic.

---

## Naming Conventions

- Java package root: `com.medianexus.orchestrator`.
- Maven artifactId: `medianexus-orchestrator`.
- REST routes use `/api/v1/...`.
- The application entry point is `MediaNexusOrchestratorApplication`.

---

## Examples

- `controller.HealthController` is the baseline example for a small API
  controller returning the unified response wrapper.
