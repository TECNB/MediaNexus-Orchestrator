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
