# External Integrations

> Conventions and known integration details for upstream services used by the
> orchestrator.

---

## ANIRSS

- Do not infer the final ANIRSS HTTP path from controller `@PostMapping` values
  alone. Check global route configuration such as ANIRSS
  `WebMvcConfig.configurePathMatch()`.
- ANIRSS adds `/api` to `@RestController` routes, so controller mappings like
  `/searchBgm` are exposed as `/api/searchBgm`.
- When adding or debugging an ANIRSS endpoint, verify the final route with a
  direct upstream request before changing the orchestrator client path.
- Ani-RSS timeout defaults belong in `application.yml` and are bound through
  `AniRssProperties`. The client should consume the configured timeout directly
  and should not provide a second fallback value.

## OpenList

- OpenList defaults belong in `application.yml` / `.env` placeholders. Avoid
  duplicating defaults in `OpenListProperties` fields, client helpers, and
  business services.
- Keep OpenList `base-url` and `authorization` optional at application startup
  so the service can run when OpenList-backed features are unused. Validate them
  inside the OpenList client before making an upstream call.
- Keep tool names, delete policy, retry limits, and timeouts as validated
  configuration. Callers should not second-guess them with fallback logic.
- For anime path and rename templates, use `{placeholder}` syntax in default
  configuration values, for example `{themoviedbName}` and `{seasonFormat}`.
  Avoid `${placeholder}` in YAML defaults because Spring treats it as a
  configuration placeholder. Existing code may accept `${placeholder}` only for
  backward compatibility with older local configuration.
- OpenList offline task wait defaults should be short enough to fail visibly
  for broken magnet jobs. The current default is `10m`; callers may override it
  through `MEDIANEXUS_OPENLIST_OFFLINE_TIMEOUT`.
- Reuse the established media organization lifecycle after files land under the
  final save path: build the complete plan first, rename files in their current
  parent directories, move files from nested directories into the final target
  directory, delete rejected files, and finally remove empty directories.
  Movie, series, and anime ingestion already follow this ordering.
- Do not create application-owned hidden, staging, transaction, or temporary
  directories in OpenList for direct-upload workflows. Upload files directly
  under the resolved target media directory, perform all collision checks
  before writing, then use the existing OpenList rename/move/remove operations
  only when the actual source layout requires them. Local process temporary
  directories used for bounded archive inspection and extraction are allowed;
  they must not be mirrored into OpenList.

## Conditional Integrations

- For conditionally enabled integrations such as the development database SSH
  tunnel, keep defaults in `application.yml` but validate required connection
  fields only when the feature is enabled and the lifecycle starts.
- Do not add unconditional Bean Validation annotations to disabled-by-default
  integration credentials if that would block unrelated application startup.
- For the development database SSH tunnel, use the system OpenSSH client rather
  than an in-process SSH library. The tunnel is a local-development convenience;
  deployments that run beside the database should disable it and connect to the
  server-local MySQL port directly. The OpenSSH path may use an askpass helper
  that reads the password from the child process environment, but the helper
  file must not contain the raw password and logs must never print passwords or
  raw credential values.
