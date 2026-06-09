# MediaNexus Orchestrator

`MediaNexus-Orchestrator` is the new Java backend for MediaNexus. It will be used to introduce new backend capabilities independently and gradually replace selected parts of the existing `MediaNexus-Core` Python backend when those Java modules become mature.

MediaNexus remains an upper-layer resource management and orchestration site for the Emby sharing workflow. It does not replace CD2, AutoSymlink, Emby, VidHub, Prowlarr, Sonarr, Radarr, OpenList, or PikPak.

## Current Scope

The backend currently provides the Spring Boot foundation, Anime orchestration
APIs, and the first authentication endpoints for MediaNexus:

- Spring Boot 3.x + Java 17 + Maven
- MySQL 8 configuration example
- MyBatis-Plus dependency and base configuration
- Sa-Token authentication with `Authorization: Bearer <token>`
- BCrypt password storage
- Knife4j/OpenAPI dependency and base configuration
- Unified API response wrapper
- Global exception handling skeleton
- `GET /api/v1/health`
- Optional SSH tunnel for connecting to the server-local MySQL container
- `POST /api/v1/auth/register` for registration-code signup
- `POST /api/v1/auth/login` for username/email login
- `POST /api/v1/auth/logout`
- `GET /api/v1/auth/me`
- `GET /api/v1/resources/anime/search` for Mikan search through ani-rss REST `/api/mikan`

Authentication is intentionally only the first batch. Business API login
guards, daily usage quota, task ownership isolation, and administrator
management endpoints are not implemented yet.

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
MEDIANEXUS_ANI_RSS_BASE_URL='http://example.invalid:7789'
MEDIANEXUS_ANI_RSS_API_KEY=''
MEDIANEXUS_ANI_RSS_TIMEOUT='10s'
MEDIANEXUS_AUTH_REGISTRATION_CODE='your-registration-code'
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

If the deployment uses a different `MYSQL_DATABASE`, update the database name
inside `MEDIANEXUS_DB_URL`.

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
  "code": 200,
  "message": "success",
  "data": {
    "status": "UP",
    "service": "MediaNexus-Orchestrator"
  }
}
```

## Auth API

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{
    "username": "tengen",
    "email": "tengen@example.com",
    "password": "ChangeMe123",
    "confirm_password": "ChangeMe123",
    "registration_code": "your-registration-code"
  }'

curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"tengen","password":"ChangeMe123"}'

curl http://localhost:8080/api/v1/auth/me \
  -H 'Authorization: Bearer YOUR_TOKEN'

curl -X POST http://localhost:8080/api/v1/auth/logout \
  -H 'Authorization: Bearer YOUR_TOKEN'
```

Register and login return:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "YOUR_TOKEN",
    "user": {
      "id": 1,
      "username": "tengen",
      "email": "tengen@example.com",
      "role": "USER",
      "created_at": "2026-06-09T12:00:00"
    }
  }
}
```

Passwords are stored as BCrypt hashes. To prepare an administrator account
manually, generate a BCrypt hash locally:

```bash
htpasswd -bnBC 10 "" 'ChangeMe123' | tr -d ':\n'
```

Then insert the administrator user with the generated hash:

```sql
INSERT INTO users (username, email, password_hash, user_role)
VALUES ('admin', 'admin@example.com', '<BCrypt hash from htpasswd>', 'ADMIN');
```

## Anime Mikan Search

```bash
curl 'http://localhost:8080/api/v1/resources/anime/search?term=anime'
```

Expected response shape:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "items": [
      {
        "id": "mikan:1234",
        "title": "Anime Title",
        "cover": "https://...",
        "source_url": "https://mikanime.tv/Home/Bangumi/1234",
        "score": 8.1,
        "exists": false,
        "week_label": "Search"
      }
    ],
    "total": 1
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
