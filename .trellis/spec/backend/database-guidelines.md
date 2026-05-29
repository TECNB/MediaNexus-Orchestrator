# Database Guidelines

> Database patterns and conventions for this project.

---

## Overview

The Java backend is configured for MySQL 8 and MyBatis-Plus. The bootstrap
task only provides configuration and dependencies; it does not create schemas,
tables, mapper XML, entities, or migrations.

---

## Query Patterns

- Use MyBatis-Plus for persistence when real database-backed modules are added.
- Keep mapper interfaces under the application package so the root
  `@MapperScan("com.medianexus.orchestrator")` can discover them.
- Do not introduce JPA repositories.

---

## Migrations

- No migration tool is configured in the bootstrap phase.
- Do not add Flyway or Liquibase unless a later task explicitly introduces
  database schema management.

---

## Naming Conventions

- Table and column naming conventions are not established yet because no schema
  exists.
- `application.yml` uses `medianexus_orchestrator` as the example database name.

---

## Common Mistakes

- Do not add database-backed business logic during project initialization.
- Do not require a database schema just for the health endpoint.
