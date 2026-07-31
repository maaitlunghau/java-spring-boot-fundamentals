Last synced commit: 9df8d03e80e3e11b0ffa967ed9ea60809b233927 (2026-07-31, branch feature/spring-boot-blueprint)

# Project State — java-spring-ecosystem-fundamentals

## Repo purpose

Personal Vietnamese-language Spring Boot learning mono-repo (full conventions in root `.claude/CLAUDE.md` and `.claude/rules/*.md`). 9 sequential sub-projects under `projects/01`–`09`, each a self-contained Spring Boot app (own `pom.xml`) teaching one topic, increasing in difficulty. Java 21, Spring Boot latest stable, Maven wrapper, MySQL 8 via Docker by default (H2 allowed for quick/no-setup tests). The `.claude/rules/*.md` files are prescriptive (layered architecture, constructor injection only, DTOs as Java records, specific testing stack) — several sub-projects don't fully comply yet, see Known Issues below.

## Commit message convention (hard rule)

Every commit message: `type(scope): subject`, single line, all lowercase, ≤60 characters, no body/footer, never a `Co-Authored-By` trailer. Mechanically enforced by `.husky/commit-msg` — see `.claude/skills/writing-commit-messages/SKILL.md` for full rules. This is stricter than generic conventional-commits guidance (e.g. the superpowers `writing-commit-messages` skill's 72-char default) — this repo's local skill overrides it.

## Branch / repo state

- Default branch: `main`. `feature/user-management` (all of project 09's work) was merged via PR #15.
- Project 09's email-verification bypass is committed to `main`: `aaca986 fix(user-management): comment out email verification check`. This is a **known temporary hack currently shipped**, not just a local uncommitted workaround — see Project 09 section.
- Project 10's work (Phases 1–6 of the roadmap) was merged to `main` via PR #20 (`c7a4b41 Merge pull request #20 from maaitlunghau/feature/spring-boot-blueprint`) on 2026-07-31. `feature/spring-boot-blueprint` is still the branch being worked on for further phases (7+) — it and `main` are in sync as of this merge, but confirm with `git log --oneline origin/main..HEAD` before assuming so in a later session.
- CI/CD actually verified working end-to-end this round: opening the PR triggered `Blueprint CI` (pull_request event), merging triggered both `Blueprint CI` and `Blueprint CD` (push-to-main events) — all 3 runs completed with `conclusion: success` (checked via `api.github.com/repos/.../actions/runs`, not just assumed from workflow file contents). The CD run pushed a Docker image to `ghcr.io/maaitlunghau/spring-boot-blueprint` (visible under the GitHub account's Packages tab, likely private-visibility by default — not discoverable by anonymous API calls).

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

**What's implemented as of `9df8d03` (Phase 1 through Phase 6 of the roadmap, all merged to `main`):**
- `module/user`: `User` entity (extends `BaseEntity`) + `Role` enum, `UserRepository` + `UserSpecifications` (dynamic filter/search via JPA `Specification`), `UserService`/`UserServiceImpl`, `UserController` with full CRUD + `PATCH .../profile` + `PUT .../role` + `GET /me`, all gated by `@PreAuthorize` (Phase 5). Last-ADMIN protection (can't demote/delete the only remaining ADMIN) is in `UserServiceImpl`.
- `module/auth`: `AuthService`/`AuthServiceImpl` (`register`, `login`, `refreshToken`, `logout`) + `RefreshTokenService`/`RefreshTokenServiceImpl` + `TokenBlacklistService`/`TokenBlacklistServiceImpl` — all split interface+impl per the project's own module convention. `AuthController` exposes `POST /api/v1/auth/{register,login,refresh,logout}`; `SessionController` exposes `GET/DELETE /api/v1/auth/sessions{,/​{id}}`.
- **Phase 3 (JWT enforcement):** `SecurityConfig` STATELESS, `JwtAuthenticationFilter`, `CustomAuthenticationEntryPoint`/`CustomAccessDeniedHandler`, real `CorsConfig` bean.
- **Phase 4 (refresh tokens in MySQL, not Redis):** `RefreshToken` entity + `RefreshTokenRepository` with `@Lock(PESSIMISTIC_WRITE)` rotation (no Redis-style grace window needed), idle TTL (`expiresAt`) + absolute TTL (`absoluteExpiresAt`), `RevokeReason` enum (`LOGOUT`/`REUSE_DETECTED`/`ROTATED`), `CleanupScheduledTask` purge job. Full design rationale in `docs/plans/10-spring-boot-blueprint.md` §1/§8, not duplicated here.
- **Phase 5 (authorization):** `@PreAuthorize` on all `UserController` endpoints. Two real bugs found+fixed this round: `SecurityConfig` was missing `@EnableMethodSecurity` (made every `@PreAuthorize` a silent no-op — any authenticated user could hit any endpoint, including self-promoting to ADMIN); `updateProfile()`'s rule was `hasRole('ADMIN')` only, missing the `#id == authentication.principal.id or` ownership clause from the plan (normal users had no way to edit their own profile).
- **Phase 6 (logout/blacklist/rate-limit/sessions):** `TokenBlacklistService` (Redis, access-token jti blacklist checked in `JwtAuthenticationFilter`), `AuthService.logout()` (blacklists current access token + revokes the matching refresh-token session via the `sid` JWT claim — deliberately does NOT take the refresh token directly, see conversation for the reasoning), `SessionController` (list/revoke other sessions), `RateLimitFilter` (Redis-backed, extended this round from login-only to also cover `/register` and `/refresh`, keyed per-path+IP). `SecurityConfig`'s `authorizeHttpRequests` explicitly lists `register/login/refresh` instead of a `/api/v1/auth/**` wildcard — the plan's intentionally-seeded wildcard-permitAll trap was correctly avoided.
- **Critical bug found+fixed during a full Phase 1–6 re-review:** `RefreshTokenServiceImpl.rotate()`, `.revokeSession()`, `.revokeAllSessions()` were missing their own `@Transactional` and were silently inheriting the class-level `@Transactional(readOnly = true)`. Under Spring+Hibernate this sets FlushMode to MANUAL, so the entity mutations in those 3 methods (dirty-checked, no explicit `.save()`) would never have actually flushed to MySQL — rotation/reuse-detection and logout-session-revoke would have been complete no-ops at runtime despite compiling fine and looking correct on read. Fixed by adding `@Transactional` to all three (matching `issue()`'s existing correct pattern). This is exactly the class of bug that only surfaces by running the app for real, not from reading code or from Mockito-based unit tests — worth remembering as a review habit for any future `@Transactional(readOnly=true)`-class method added to a write-capable service.
- **Docker/CI/CD added and verified working, not just scaffolded:** `Dockerfile` (multi-stage, non-root user), `docker-compose.yml` (MySQL 8 + Redis 7 + phpMyAdmin, healthchecks), `.env.example` filled in. GitHub Actions workflows were found misplaced at `projects/10-spring-boot-blueprint/.github/workflows/{ci,cd}.yml` — **GitHub only ever reads `.github/workflows/` at the repo root**, so those never actually ran; moved to root as `.github/workflows/blueprint-{ci,cd}.yml` with `paths:` filters scoped to this sub-project (mono-repo — don't let it fire on unrelated sub-project changes). CI spins up MySQL+Redis as GitHub Actions `services:` (needed because `@SpringBootTest` loads a full context that needs real connections) and runs `mvn verify`; CD builds/pushes a Docker image to `ghcr.io/maaitlunghau/spring-boot-blueprint` on push to `main`, auth'd via the automatic `GITHUB_TOKEN` (no secrets setup required). **Confirmed actually working** via the GitHub Actions API (not just "looks right on paper") after the PR #20 merge — all 3 runs (2× CI, 1× CD) came back `conclusion: success`.

**Different auth transport than project 09:** login/refresh return the access token as **JSON body** (`AuthResponse{accessToken, refreshToken, expiresIn}`), not an httpOnly cookie. Don't assume project 09's cookie/CSRF design carries over here; it doesn't.

**Still not done:** no Flyway migrations (`ddl-auto: update` only), no `application-dev.yml`/`application-prod.yml` split, no tests beyond the `ApplicationTests` `contextLoads()` stub (Phase 7, next up) — everything else in the Phase 8 checklist (`OpenApiConfig`, `LoggingAspect`, `CookieUtils`, `DateUtils`) is still an empty stub, intentionally deferred.

**Roadmap doc:** [`docs/plans/10-spring-boot-blueprint.md`](../../docs/plans/10-spring-boot-blueprint.md) (~3200 lines) tracks phased implementation in detail. Phases 1–6 are now implemented; Phase 7 (test pyramid: unit/slice/integration) is next, then Phase 8 (Flyway, OpenAPI, remaining Docker/CI polish already partly done this round).

## How to tell if this file is stale

```bash
git log --oneline <last-synced-hash-above>..HEAD
```

If that's non-empty, treat any section above touching those changed files as possibly outdated — patch it rather than trusting it blindly. See `.claude/skills/resume/SKILL.md` for the full sync procedure.
