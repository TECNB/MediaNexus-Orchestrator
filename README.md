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
- Optional SSH tunnel for connecting to the server-local MySQL container
- Test-only `test_users` CRUD endpoints for validating MySQL connectivity

No Anime, Emby, Ani-RSS, OpenList, Sonarr, Radarr, Prowlarr, PikPak, or Python backend replacement logic is implemented in this phase.

## Requirements

- JDK 17
- Maven 3.9+
- MySQL 8, if you want to run with a real local datasource

## Configuration

The default configuration lives in `src/main/resources/application.yml`.
Local secrets can be stored in `.env`; Spring Boot imports it automatically and
`.gitignore` keeps it out of git.

Useful environment variables:

```bash
MEDIANEXUS_DB_URL='jdbc:mysql://127.0.0.1:3307/medianexus_orchestrator?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
MEDIANEXUS_DB_USERNAME='TEC'
MEDIANEXUS_DB_PASSWORD='...'
```

The datasource points at a local forwarded port by default. Because the MySQL
container publishes `127.0.0.1:3306:3306` on the server, local development must
either start an SSH tunnel before the app starts:

```bash
ssh -L 3307:127.0.0.1:3306 root@YOUR_SERVER_HOST
```

Or enable the built-in Java tunnel in `.env`:

```bash
MEDIANEXUS_DB_SSH_TUNNEL_ENABLED=true
MEDIANEXUS_DB_SSH_HOST=YOUR_SERVER_HOST
MEDIANEXUS_DB_SSH_USERNAME=root
MEDIANEXUS_DB_SSH_PASSWORD=...
```

If the deployment uses a different `MYSQL_DATABASE`, update
`MEDIANEXUS_DB_NAME` and the database name inside `MEDIANEXUS_DB_URL`.

The app creates a lightweight `test_users` table on startup so database
connectivity can be tested through CRUD endpoints.

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

## Test User CRUD

```bash
curl http://localhost:8080/api/v1/test-users

curl -X POST http://localhost:8080/api/v1/test-users \
  -H 'Content-Type: application/json' \
  -d '{"username":"tec","email":"tec@example.com","displayName":"TEC"}'

curl -X PUT http://localhost:8080/api/v1/test-users/1 \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"TEC Updated","enabled":true}'

curl -X DELETE http://localhost:8080/api/v1/test-users/1
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
