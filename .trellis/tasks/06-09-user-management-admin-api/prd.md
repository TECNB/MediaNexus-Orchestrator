# User Management Admin API

## Goal

Add administrator-only Java backend APIs for the MediaNexus user management page.
Admins can inspect users, update global and per-user daily content creation
quotas, and reset a normal user's current-day usage count.

## Requirements

- Require `ADMIN` for every `/api/v1/admin/**` endpoint.
- List users with pagination, keyword search by username/email, role filter, and
  sorting by created time or today's used count.
- Return today's combined usage for `MAGNET_INGEST_CREATE` and
  `ANIME_SUBSCRIBE_CREATE`, plus per-action breakdowns.
- Store global default quota in a database-backed setting, with
  `MEDIANEXUS_DAILY_CONTENT_CREATE_LIMIT` as the fallback default.
- Store per-user quota override on `users.daily_content_create_limit_override`;
  `NULL` means inherit global default.
- Validate quota values as integers from `0` through `9`.
- Admin users are unlimited and cannot be quota-updated or usage-reset.
- Reset today usage to zero for both shared quota actions; resetting a user with
  no usage should still succeed.
- Record audit rows for global quota updates, per-user quota updates, restoring
  default quota, and usage resets. The V1 frontend does not display audit rows.

## API Shape

- `GET /api/v1/admin/users`
- `GET /api/v1/admin/users/summary`
- `GET /api/v1/admin/quota/default`
- `PUT /api/v1/admin/quota/default`
- `PUT /api/v1/admin/users/{id}/quota`
- `POST /api/v1/admin/users/{id}/usage/reset-today`
