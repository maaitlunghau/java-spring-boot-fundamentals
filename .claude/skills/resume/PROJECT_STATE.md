Last synced commit: aaca986d7cf63f6cc26eb3748d1b9459d4cd4fe0 (2026-07-28, branch main)

# Project State — java-spring-ecosystem-fundamentals

## Repo purpose

Personal Vietnamese-language Spring Boot learning mono-repo (full conventions in root `.claude/CLAUDE.md` and `.claude/rules/*.md`). 9 sequential sub-projects under `projects/01`–`09`, each a self-contained Spring Boot app (own `pom.xml`) teaching one topic, increasing in difficulty. Java 21, Spring Boot latest stable, Maven wrapper, MySQL 8 via Docker by default (H2 allowed for quick/no-setup tests). The `.claude/rules/*.md` files are prescriptive (layered architecture, constructor injection only, DTOs as Java records, specific testing stack) — several sub-projects don't fully comply yet, see Known Issues below.

## Branch / repo state

- Default branch: `main`. `feature/user-management` (all of project 09's work) was merged via PR #15.
- Project 09's email-verification bypass is committed to `main`: `aaca986 fix(user-management): comment out email verification check`. This is a **known temporary hack currently shipped**, not just a local uncommitted workaround — see Project 09 section.

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
| 09-fullstack-user-management | Full backend+frontend, most mature/active project | See dedicated section below. |

**Cross-cutting gap:** projects 01–08 have zero real tests — only the Spring Boot–generated `contextLoads()` stub — despite `tech-defaults.md` mandating JUnit5/Mockito/`@DataJpaTest`/`@WebMvcTest`. Project 09's backend is the same; its frontend has **no test framework installed at all** (no Vitest/RTL, zero `*.test.*` files).

**Docs currency:** root `README.md` documents only projects 01–08 — **project 09 is entirely missing from it**, despite having its own `docs/guides/09-fullstack-user-management.md` and `docs/plans/09-fullstack-user-management.md`. Biggest doc gap in the repo.

Root `package.json` lists `lint-staged` as a devDependency; unconfirmed whether it's actually wired into the Husky pre-commit hook.

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

## How to tell if this file is stale

```bash
git log --oneline <last-synced-hash-above>..HEAD
```

If that's non-empty, treat any section above touching those changed files as possibly outdated — patch it rather than trusting it blindly. See `.claude/skills/resume/SKILL.md` for the full sync procedure.
