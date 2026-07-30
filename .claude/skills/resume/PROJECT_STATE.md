Last synced commit: 7ca1fc287ed4038ce0d408cea0573bf5d1069a4a (2026-07-30, branch feature/spring-boot-blueprint)

# Project State — java-spring-ecosystem-fundamentals

## Repo purpose

Personal Vietnamese-language Spring Boot learning mono-repo (full conventions in root `.claude/CLAUDE.md` and `.claude/rules/*.md`). 9 sequential sub-projects under `projects/01`–`09`, each a self-contained Spring Boot app (own `pom.xml`) teaching one topic, increasing in difficulty. Java 21, Spring Boot latest stable, Maven wrapper, MySQL 8 via Docker by default (H2 allowed for quick/no-setup tests). The `.claude/rules/*.md` files are prescriptive (layered architecture, constructor injection only, DTOs as Java records, specific testing stack) — several sub-projects don't fully comply yet, see Known Issues below.

## Commit message convention (hard rule)

Every commit message: `type(scope): subject`, single line, all lowercase, ≤60 characters, no body/footer, never a `Co-Authored-By` trailer. Mechanically enforced by `.husky/commit-msg` — see `.claude/skills/writing-commit-messages/SKILL.md` for full rules. This is stricter than generic conventional-commits guidance (e.g. the superpowers `writing-commit-messages` skill's 72-char default) — this repo's local skill overrides it.

## Branch / repo state

- Default branch: `main`. `feature/user-management` (all of project 09's work) was merged via PR #15.
- Project 09's email-verification bypass is committed to `main`: `aaca986 fix(user-management): comment out email verification check`. This is a **known temporary hack currently shipped**, not just a local uncommitted workaround — see Project 09 section.
- Active work is on `feature/spring-boot-blueprint` (project 10), currently 47 commits ahead of `main` / not yet merged. `main` is still at `a2a332f` (project 10's initial README only) — none of project 10's actual code (entities/services/controllers/JWT) has reached `main` yet.

## Sub-projects (01–09)

| # | Topic | Notes |
|---|---|---|
| 01-rest-controller | REST basics, `@RestController` vs `@Controller` | Minimal, fine as-is for its scope. |
| 02-ioc-and-di | IoC/DI, 3 injection types | Field `@Autowired` in `Dev.java` — likely intentional (teaching all injection types side by side). |
| 03-crud-rest-api | CRUD + layered arch, H2 | **Violation**: `ProductController`/`ProductService` use field `@Autowired`, not constructor injection — contradicts `coding-standards.md`. |
| 04-rest-api-jpa-mysql | JPA + MySQL | Full layers, DTOs are records. No `docker-compose.yml`. |
| 05-mvc-thymeleaf | Thymeleaf | Full layers, DTOs are records. No `docker-compose.yml`. |
| 06-spring-security-jwt | JWT auth, MySQL | Full layers. Has `docker-compose.yml`. |
| 07-spring-security-oauth2-mvc | OAuth2 social login (Google/GitHub) | No `dto/` package unlike sibling security projects. No `docker-compose.yml`. |
| 08-spring-security-auth0-mvc | Auth0 OIDC | Minimal layers (config+controller only). No `docker-compose.yml`. |
| 09-fullstack-user-management | Full backend+frontend, most mature project (merged to `main`) | See dedicated section below. |
| 10-spring-boot-blueprint | Production-style REST API blueprint/template (module-per-feature, not layer-per-topic like 01-09), currently on unmerged `feature/spring-boot-blueprint` | See dedicated section below. |

**Cross-cutting gap:** projects 01–08 have zero real tests — only the Spring Boot–generated `contextLoads()` stub — despite `tech-defaults.md` mandating JUnit5/Mockito/`@DataJpaTest`/`@WebMvcTest`. Project 09's backend is the same; its frontend has **no test framework installed at all** (no Vitest/RTL, zero `*.test.*` files). Project 10 is the same again — only `ApplicationTests.java` (`contextLoads()` stub), despite the project's own README explicitly listing unit/slice/integration tests as a TODO.

**Docs currency:** root `README.md` documents only projects 01–08 — **projects 09 and 10 are both entirely missing from it**, despite each having its own `docs/guides/*.md` (09 only) and `docs/plans/*.md` (both). Biggest doc gap in the repo.

Root `package.json` lists `lint-staged` as a devDependency but it is **not** wired up anywhere — `.husky/pre-commit` is a no-op (just echoes "husky: pre-commit ok"), no `.lintstagedrc`/`lint-staged` config exists. Only `.husky/commit-msg` actually enforces anything (message format: `type(scope): subject`, lowercase, single line, ≤60 chars, types `feat|fix|docs|style|refactor|perf|test|chore|revert|ci`).

## Project 09 — fullstack-user-management (deep context)

**Stack:** Backend Spring Boot, package root `com.maaitlunghau.__fullstack_user_management`, full layered structure (`controller/service/repository/entity/dto/{request,response}/exception/config/security/spec/seeder/util`). Frontend React 19 + Vite 8 + TypeScript, TanStack Query, react-hook-form+zod, Tailwind v4, axios (`withCredentials: true`, CSRF+refresh-token interceptor), js-cookie.

**Dev environment** (`backend/docker-compose.yml`): MySQL (3306), Redis (6379), Mailpit — SMTP `:1025`, web UI `:8025` — and phpMyAdmin (`:8080`). Backend runs on `:8081`, frontend Vite dev server on `:5173`. `CorsConfig.java` hardcodes `:5173` as the **only** allowed CORS origin — any other origin/port (e.g. `vite preview`'s default `:4173`) gets a hard `403 Invalid CORS request` on every API call.

**Auth design:** httpOnly cookies — `access_token` (path `/`), `refresh_token` (path `/api/auth/refresh-token`) — both `SameSite=Lax`, `secure=false` (dev only, intentional). CSRF via `CookieCsrfTokenRepository` (`XSRF-TOKEN`, non-httpOnly) enforced on state-changing requests outside `/api/auth/**`. Seeded admin account: `admin@usermanagement.dev` / `112233` (`DataSeeder.java`), already `isEmailVerified=true`.

**Known architecture smell (unresolved, not yet a confirmed user-facing bug):** `SecurityConfig.java` sets `sessionCreationPolicy(IF_REQUIRED)` instead of `STATELESS` whenever Auth0 credentials exist in `backend/src/main/resources/application-local.properties` (gitignored; present in this dev environment). This creates a new `JSESSIONID` + rotates the `XSRF-TOKEN` cookie on every JWT-authenticated request (confirmed via curl). Verified this does NOT break GET requests (`/api/me` etc. — CSRF only checks state-changing methods), but could cause intermittent 403s on PATCH/POST/DELETE if the client's cached `XSRF-TOKEN` races the server-side rotation. Worth investigating if profile-update / user-CRUD calls ever 403 unexpectedly.

**RESOLVED bug — misleading login error message:** `AuthService.login()` correctly rejects unverified accounts (`BadRequestException`, HTTP 400, "Email chưa được xác thực..."), but `LoginPage.tsx` was showing a **hardcoded generic "Sai email hoặc mật khẩu"** message regardless of the real backend error. Effect: register → try to log in before verifying (very natural) → looks like a permanent wrong-password loop, even with correct credentials. Root-caused via curl + real headless-Chrome testing (not guesswork) — verified accounts log in fine, survive reload, and navigate protected/admin routes with zero issues; the bug was purely the misleading error text plus unverified-by-default state.

**TEMPORARY WORKAROUND CURRENTLY SHIPPED ON `main`:** commit `aaca986` comments out the `isEmailVerified` check entirely in `AuthService.login()` (~line 122-124) so ANY account, verified or not, can log in. This was an explicit, intentional local-dev unblock — **it is a live security regression if left as-is**: anyone can register with an email they don't own and log in immediately without ever proving ownership. Proper fix, not yet done:
1. `LoginPage.tsx` / `RegisterPage.tsx`: surface the real backend error message (`err.response?.data?.message`) instead of the hardcoded string.
2. Add a "resend verification email" affordance for the unverified-email case.
3. Re-enable the `isEmailVerified` check in `AuthService.login()` and commit that as its own change.

**Dev mail:** verification/reset emails are caught by Mailpit, never sent for real — check `http://localhost:8025`, not a real inbox. Verify-email link format: `{frontend-url}/verify-email?token=...`.

**Developer's own TODOs** (from `backend/note.txt`, not yet actioned):
- Catch-all `@ExceptionHandler(Exception.class)` turns "route not found" into 500 instead of 404 — needs a `NoResourceFoundException` handler.
- Soft delete only sets `deleted_at`; no purge job (hard delete) or restore endpoint yet.
- Deleting an ADMIN user should return 403, currently returns 400 (`BadRequestException`).
- Investigate why soft-delete relations are eager-loading instead of lazy.

## Project 10 — spring-boot-blueprint (deep context)

**Purpose:** unlike 01-09 (one topic each), this is meant as a reusable production-style REST API template — full auth/user CRUD, security, validation, error handling, caching, testing pyramid, observability, CI/CD. Package root `com.maaitlunghau.__spring_boot_blueprint`. Spring Boot **4.1.0** (newer major than projects 01-09) — note the renamed starters this pulls in: `spring-boot-starter-webmvc` (not `-web`), and *separate* test starters per concern (`spring-boot-starter-data-jpa-test`, `-security-test`, `-validation-test`, `-webmvc-test`) instead of one `spring-boot-starter-test`.

**Architecture — module-per-feature, not layer-per-project:** `module/auth/` and `module/user/` each contain their own full stack (`controller/v1/service/repository/entity/dto/{request,response}/mapper`). Cross-module concerns live at top level: `common/` (`ApiResponse` envelope, `BaseEntity` — id/createdAt/updatedAt/version, all entities extend it), `config/`, `security/`, `exception/`, `aspect/` (`LoggingAspect`), `scheduler/` (`CleanupScheduledTask`), `util/`. This is a deliberate deviation from `.claude/rules/architecture.md`'s plain layered structure — intentional for this project, not a violation.

**What's implemented as of `7ca1fc2` (Phase 1 through Phase 4 of the roadmap):**
- `module/user`: `User` entity (extends `BaseEntity`) + `Role` enum, `UserRepository` + `UserSpecifications` (dynamic filter/search via JPA `Specification`), `UserService`/`UserServiceImpl`, `UserController` with CRUD + partial profile update via `PATCH` (`UpdateProfileRequest`), role update DTO.
- `module/auth`: `AuthService`/`AuthServiceImpl` (`register()`, `login()`, `refreshToken()`), `AuthController` exposes `POST /api/v1/auth/{register,login,refresh}`, wrapped in the `ApiResponse<T>` envelope. `AuthService` was split into interface + `service/impl/AuthServiceImpl` this round, matching the project's own stated module convention (`RefreshTokenService`/`RefreshTokenServiceImpl` got the same treatment).
- `security/`: `JwtService` (access tokens now carry an `sid` claim = session id) and `UserDetailsServiceImpl`.
- **Phase 3 (JWT enforcement, resolved earlier):** `SecurityConfig` is `STATELESS`, `JwtAuthenticationFilter` wired via `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)`, `CustomAuthenticationEntryPoint`/`CustomAccessDeniedHandler` registered, `CorsConfig` has a real `CorsConfigurationSource` bean. `UserController` CRUD is actually protected now.
- **Phase 4 (refresh tokens, just implemented):** refresh tokens are stored in **MySQL via JPA** (`module/auth/entity/RefreshToken` + `RefreshTokenRepository`), *not* Redis — a deliberate design choice made this round (see `docs/plans/10-spring-boot-blueprint.md` §1/§8 for the full "why", not duplicated here). Rotation uses `@Lock(PESSIMISTIC_WRITE)` for real atomicity (no Redis-style grace window needed). Each token has both an idle TTL (`expiresAt`, resets on rotate) and an absolute TTL (`absoluteExpiresAt`, fixed from first login) — `RevokeReason` enum (`LOGOUT`/`REUSE_DETECTED`/`ROTATED`) records why a token died. `CleanupScheduledTask` purges expired/long-revoked rows (`@EnableScheduling` added to `Application.java`). Landed as 14 small commits, `d7394d1`..`7ca1fc2`.
- **Review-and-fix cycle already happened for Phase 4** — first implementation pass had real bugs (raw token stored unhashed instead of its SHA-256 hash → every first `/refresh` after login would fail; idle/absolute TTL arguments swapped; `LocalDateTime now` was a stale singleton-bean field computed once at startup instead of per-call; `AuthServiceImpl`/`AuthController` were wired to concrete `*Impl` classes instead of the interfaces, which would have broken the `@WebMvcTest`/mock-based tests Phase 7 calls for). All confirmed fixed and the module now compiles clean (`./mvnw compile`) — but **nobody has run `spring-boot:run` and exercised the login→refresh→rotate flow against a live MySQL yet**. Treat "compiles" as verified, "actually works end-to-end" as not yet verified.

**Different auth transport than project 09:** login/refresh return the access token as **JSON body** (`AuthResponse{accessToken, refreshToken, expiresIn}`), not an httpOnly cookie. Don't assume project 09's cookie/CSRF design carries over here; it doesn't.

**Still scaffolding-only / explicitly empty per the project's own README TODO:** `Dockerfile`, `docker-compose.yml`, `.env.example`, `.github/workflows/ci.yml`, `.github/workflows/cd.yml` are all 0-byte placeholder files. `RedisConfig` exists but the Redis dependency itself hasn't been added yet (would fail to start if Redis features were actually exercised). No Flyway migrations — `ddl-auto: update` only. No `application-dev.yml`/`application-prod.yml` split.

**Roadmap doc:** [`docs/plans/10-spring-boot-blueprint.md`](../../docs/plans/10-spring-boot-blueprint.md) (~3200 lines) tracks phased implementation in detail — Phase 4 section was substantially redesigned in place (`32443b8`) before being implemented, so the doc reflects the MySQL-backed design, not the original Redis-backed draft. Check it for the authoritative next-step order (Phase 5 onward: `@PreAuthorize` on existing user CRUD, then Phase 6 logout/blacklist/rate-limit/sessions) before assuming what's next.

## How to tell if this file is stale

```bash
git log --oneline <last-synced-hash-above>..HEAD
```

If that's non-empty, treat any section above touching those changed files as possibly outdated — patch it rather than trusting it blindly. See `.claude/skills/resume/SKILL.md` for the full sync procedure.
