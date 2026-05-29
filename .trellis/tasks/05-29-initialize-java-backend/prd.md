# Initialize MediaNexus Java backend project

## Goal

Initialize `MediaNexus-Orchestrator` as a new Spring Boot Java backend that can gradually take over selected capabilities from the existing `MediaNexus-Core` Python backend. This task only establishes the first runnable backend skeleton.

## What I already know

- MediaNexus is the upper-layer resource management site for an Emby sharing workflow.
- It does not replace the underlying tools; it provides a unified entry point, workflow visualization, status observation, and link compensation.
- Playback chain: CD2 -> AutoSymlink -> `.strm` -> Emby -> VidHub.
- Resource acquisition chain: Prowlarr -> Sonarr/Radarr -> Blackhole -> bridge script -> OpenList API -> PikPak.
- Migration strategy is incremental: Java implements new capabilities independently first, frontend can call both Python and Java APIs later, and mature Java modules can gradually take over old capabilities.
- The first broader Java phase will focus on Anime completion, but this task must not implement Anime business logic.

## Confirmed Decisions

- Package name: `com.medianexus.orchestrator`.
- Maven artifactId: `medianexus-orchestrator`.
- Default port: `8080`.
- Project shape: single-module Spring Boot application.

## Requirements

- Initialize a Spring Boot 3.x application using Java 17 and Maven.
- Include dependencies for MySQL 8, MyBatis-Plus, Sa-Token, and Knife4j.
- Provide a basic package and directory structure for controllers, common response types, exception handling, and config.
- Implement `GET /api/v1/health`.
- Return health data through the common response wrapper.
- Provide a unified response structure.
- Provide a global exception handling skeleton.
- Provide example `application.yml` configuration.
- Provide README startup instructions.

## Acceptance Criteria

- [x] The project contains a valid Maven `pom.xml` for Spring Boot 3.x and Java 17.
- [x] The project is a single-module Spring Boot application.
- [x] `GET /api/v1/health` returns a unified response with service health data.
- [x] Common response, error code, business exception, and global exception handler classes exist.
- [x] `application.yml` includes example server, datasource, MyBatis-Plus, Sa-Token, and Knife4j settings.
- [x] `README.md` explains the project, requirements, local startup, config, and health endpoint.
- [x] Excluded technologies are not introduced.
- [x] No Anime, Emby, Ani-RSS, OpenList, Sonarr, Radarr, Prowlarr, PikPak, or Python replacement business logic is implemented.

## Out of Scope

- PostgreSQL.
- JPA.
- Flyway.
- Actuator.
- Testcontainers.
- Spring Cloud.
- Message queues.
- WebFlux.
- Complex multi-module project structure.
- Anime, Emby, Ani-RSS, OpenList, Sonarr, Radarr, Prowlarr, or PikPak business logic.
- Frontend integration.
- Replacing Python backend routes.

## Technical Notes

- Existing `.trellis/spec/backend/*` guidelines are currently placeholders, so this task should establish conservative conventions without over-design.
- The repository directory currently contains Trellis metadata and no Java application files.
- Full-project validation commands are forbidden by workspace agreement unless explicitly requested. Verification should be limited to code inspection or narrowly scoped checks.
