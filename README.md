# MediaNexus Orchestrator

`MediaNexus-Orchestrator` is the new Java backend for MediaNexus. It will be used to introduce new backend capabilities independently and gradually replace selected parts of the existing `MediaNexus-Core` Python backend when those Java modules become mature.

MediaNexus remains an upper-layer resource management and orchestration site for the Emby sharing workflow. It does not replace CD2, AutoSymlink, Emby, VidHub, Prowlarr, Sonarr, Radarr, OpenList, or PikPak.

## Current Scope

This first phase only provides the Spring Boot project skeleton:

- Spring Boot 3.x + Java 17 + Maven
- MySQL 8 configuration example
- MyBatis-Plus dependency and base configuration
- Sa-Token dependency and base configuration
- Knife4j/OpenAPI dependency and base configuration
- Unified API response wrapper
- Global exception handling skeleton
- `GET /api/v1/health`

No Anime, Emby, Ani-RSS, OpenList, Sonarr, Radarr, Prowlarr, PikPak, or Python backend replacement logic is implemented in this phase.

## Requirements

- JDK 17
- Maven 3.9+
- MySQL 8, if you want to run with a real local datasource

## Configuration

The default configuration lives in `src/main/resources/application.yml`.

Useful environment variables:

```bash
export MEDIANEXUS_DB_URL='jdbc:mysql://localhost:3306/medianexus_orchestrator?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai'
export MEDIANEXUS_DB_USERNAME='root'
export MEDIANEXUS_DB_PASSWORD=''
```

The Hikari datasource uses `initialization-fail-timeout: -1` so the application skeleton can start without failing fast before any database-backed feature exists. Add stricter datasource validation when real persistence logic is introduced.

## Local Startup

```bash
mvn spring-boot:run
```

The application starts on port `8080` by default.

## Health Check

```bash
curl http://localhost:8080/api/v1/health
```

Expected response:

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

## API Docs

Knife4j/OpenAPI is enabled for the skeleton. After startup, try:

- `http://localhost:8080/doc.html`
- `http://localhost:8080/swagger-ui.html`

## Excluded From This Phase

- PostgreSQL
- JPA
- Flyway
- Actuator
- Testcontainers
- Spring Cloud
- Message queues
- WebFlux
- Multi-module project structure
