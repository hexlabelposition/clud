# Backlog

Milestones and tasks for Clud. Architecture and decisions behind these tasks are in [ARCHITECTURE.md](ARCHITECTURE.md).

## Conventions

- Tasks have IDs (`M2-05`); reference them in commits and pull requests.
- `[ ]` open, `[x]` done.
- A milestone is finished when its **Done when** criteria are met, not when every checkbox is ticked by habit.
- When the number of tasks or contributors grows, tasks move to GitHub Issues and this file becomes `ROADMAP.md`.

## Overview

| Milestone | Goal | Release |
|-----------|------|---------|
| M0 | Foundation | |
| M1 | Accounts | |
| M2 | Files core | |
| M3 | CLI and personal access tokens | **v0.1** |
| M4 | Sharing | v0.2 |
| M5 | Trash | v0.3 |
| M6 | S3/MinIO and resumable uploads | v0.4 |
| M7 | Administration UI | v0.5 |
| Later | Ideas that passed the product boundary check | |

---

## M0 — Foundation

Goal: an empty but complete skeleton that builds, tests and deploys.

- [x] **M0-01** Repository skeleton: `web/`, `server/`, `cli/`, `api/`, `LICENSE` (AGPL-3.0), `.editorconfig`, `.gitignore`
- [x] **M0-02** Server: Spring Boot, Java 25, Gradle; Spring Modulith with a module verification test
- [x] **M0-03** Server: PostgreSQL, Flyway, base integration test with Testcontainers
- [x] **M0-04** Server: configuration via `CLUD_*` environment variables, `GET /api/v1/health`
- [x] **M0-05** Server: global error handling with RFC 9457 Problem Details
- [x] **M0-06** Web: Next.js, TypeScript, Tailwind CSS, Base UI, lint and format, Playwright setup
- [x] **M0-07** CLI: Go module, cobra, `clud version`
- [x] **M0-08** API contract: springdoc generates `api/openapi.yaml` via a Gradle task
- [x] **M0-09** Client generation: `openapi-typescript` + `openapi-fetch` in Web, `oapi-codegen` in CLI
- [x] **M0-10** Dockerfiles for Server and Web
- [x] **M0-11** `Caddyfile`: `/api/*` → server, everything else → web
- [x] **M0-12** `docker-compose.yml` (caddy, web, server, postgres) and `docker-compose.dev.yml` (postgres, caddy → host)
- [x] **M0-13** `.env.example` with all supported variables
- [x] **M0-14** `Makefile`: `dev`, `test`, `build`, `lint`, `openapi`
- [x] **M0-15** CI (GitHub Actions): build and test all three apps; fail if `api/openapi.yaml` is out of date

**Done when:** `docker compose up -d` on a clean machine serves a placeholder Web page at `CLUD_PUBLIC_URL` that shows the Server health status fetched through the generated client, and CI is green.

---

## M1 — Accounts

Goal: a multi-user instance with an admin and invite-based registration.

- [x] **M1-01** `users` module: user entity, roles `USER` / `ADMIN`, quota limit field
- [ ] **M1-02** Password hashing with `DelegatingPasswordEncoder`
- [ ] **M1-03** First-run setup: API reports whether setup is needed; first account becomes admin
- [ ] **M1-04** Login and logout with Spring Session JDBC; cookie `HttpOnly`, `Secure`, `SameSite=Lax`
- [ ] **M1-05** CSRF protection for cookie-authenticated requests
- [ ] **M1-06** `GET /api/v1/me`
- [ ] **M1-07** Registration modes `invite` / `open` / `closed` (`CLUD_REGISTRATION_MODE`)
- [ ] **M1-08** Invites: admin creates an invite via API; registration with invite token; expiry and single use
- [ ] **M1-09** Login rate limiting
- [ ] **M1-10** Change password
- [ ] **M1-11** Web: first-run setup, login, register with invite, change password, route guard
- [ ] **M1-12** Tests: auth flows, CSRF, registration modes

**Done when:** on a fresh instance the admin is created through the setup screen, invites a second user, the second user registers and logs in, and sessions survive a Server restart.

---

## M2 — Files core

Goal: store, organize and retrieve files through the Web app.

Storage:

- [ ] **M2-01** `storage` module: `StorageProvider` interface, streaming operations, `ByteRange`
- [ ] **M2-02** `LocalStorageProvider` with key layout `objects/<xx>/<uuid>`
- [ ] **M2-03** `stored_objects` with states `PENDING` / `COMMITTED` / `RELEASED`
- [ ] **M2-04** Provider contract test suite
- [ ] **M2-05** Background jobs: delete stale `PENDING` and all `RELEASED` objects

Tree:

- [ ] **M2-06** `nodes` schema, UUIDv7 IDs, root folder per user created at registration
- [ ] **M2-07** Name validation and case-insensitive uniqueness within a folder
- [ ] **M2-08** Create folder
- [ ] **M2-09** List folder children with cursor pagination and sorting
- [ ] **M2-10** Get node; `GET /api/v1/nodes/resolve?path=`
- [ ] **M2-11** Rename and move with `If-Match`; reject moving a folder into its descendant
- [ ] **M2-12** Delete file or folder subtree (hard delete until M5), releasing stored objects

Transfer:

- [ ] **M2-13** Streaming upload of a raw request body; SHA-256 while streaming; max upload size
- [ ] **M2-14** Quota: check `Content-Length` before accepting, enforce while streaming, atomic usage update
- [ ] **M2-15** Server-side MIME type detection
- [ ] **M2-16** Replace file contents with `If-Match`
- [ ] **M2-17** Download with `Range` support and `ETag`
- [ ] **M2-18** Safe content serving: `attachment` by default, inline allowlist, `nosniff`, CSP `sandbox`

Access:

- [ ] **M2-19** Owner-only access checks in `files`, behind the `AccessGrantLookup` extension point (implemented by `sharing` in M4)

Web:

- [ ] **M2-20** File browser: breadcrumbs, list, sorting, empty states
- [ ] **M2-21** Create folder, rename, move, delete
- [ ] **M2-22** Upload with progress, multiple files, drag and drop
- [ ] **M2-23** Download; inline preview for allowlisted types
- [ ] **M2-24** Storage usage indicator

Tests:

- [ ] **M2-25** Integration tests: tree rules, concurrency (`412`), quota, Range, crash between storage write and commit
- [ ] **M2-26** Playwright happy path: log in → create folder → upload → rename → move → download → delete

**Done when:** a user can manage folders and upload, download, rename, move and delete files through the Web app; quota is enforced; an interrupted upload leaves no dangling metadata and its bytes are cleaned up.

---

## M3 — CLI and personal access tokens → v0.1

Goal: the API is proven by a second client, and the first release is published.

- [ ] **M3-01** Personal access tokens: create (shown once), list, revoke, expiry, `last_used_at`; stored as hashes
- [ ] **M3-02** Bearer authentication for PATs, exempt from CSRF
- [ ] **M3-03** Web: personal access tokens page
- [ ] **M3-04** CLI config in `os.UserConfigDir()/clud`: server URL and token
- [ ] **M3-05** `clud login <url>` (password → device-named PAT), `clud logout` (revokes the token), `clud whoami`
- [ ] **M3-06** `clud ls`, `clud mkdir`
- [ ] **M3-07** `clud upload` and `clud download` with progress
- [ ] **M3-08** `clud mv`, `clud rm`
- [ ] **M3-09** `--json` output and meaningful exit codes
- [ ] **M3-10** CLI tests against a test Server
- [ ] **M3-11** Release pipeline: GoReleaser binaries, Docker images to GHCR, version tags
- [ ] **M3-12** `CHANGELOG.md`; README quick start verified on a clean VPS

**Done when:** v0.1.0 is tagged, Docker images and CLI binaries are published, and the README quick start works on a clean VPS end to end, including `clud login` and an upload from the CLI.

---

## M4 — Sharing → v0.2

Goal: share files and folders with users and through public links.

- [ ] **M4-01** `sharing` module: `shares` schema, `READ` / `WRITE` permissions
- [ ] **M4-02** Implement `AccessGrantLookup` with inheritance down the tree
- [ ] **M4-03** User-to-user shares: create, list, change permission, remove (owner only)
- [ ] **M4-04** "Shared with me" listing
- [ ] **M4-05** Public links: create, optional expiry and password, revoke; tokens stored as hashes
- [ ] **M4-06** Public API for links: metadata, folder listing, download
- [ ] **M4-07** Web: share dialog, "Shared with me", public link page `/s/{token}`
- [ ] **M4-08** CLI: `clud share` (user and link)
- [ ] **M4-09** Authorization test matrix: users × permissions × operations, including inherited grants

**Done when:** a folder shared with `WRITE` lets the other user upload into it, a `READ` share does not, and a password-protected public link with expiry works without an account and stops working after revocation or expiry.

---

## M5 — Trash → v0.3

- [ ] **M5-01** `trashed_at` and `trash_root_id`; trash a subtree instead of deleting
- [ ] **M5-02** List trash (trash roots only)
- [ ] **M5-03** Restore; `409 Conflict` if the name is taken
- [ ] **M5-04** Delete permanently and empty trash
- [ ] **M5-05** Automatic purge after `CLUD_TRASH_RETENTION_DAYS`
- [ ] **M5-06** Trashed files count toward quota
- [ ] **M5-07** Web: trash view with restore and delete
- [ ] **M5-08** CLI: `clud trash ls`, `clud trash restore`, `clud trash empty`

**Done when:** deleting a folder moves it to trash, restoring brings back the whole subtree, and expired trash is purged with its bytes removed from storage.

---

## M6 — S3/MinIO and resumable uploads → v0.4

- [ ] **M6-01** `S3StorageProvider` (AWS SDK v2), configured via `CLUD_STORAGE_*`
- [ ] **M6-02** MinIO as an optional Docker Compose profile
- [ ] **M6-03** Run the provider contract suite against MinIO with Testcontainers
- [ ] **M6-04** Upload sessions API: create, upload part, list parts, complete, abort
- [ ] **M6-05** S3 implementation via native multipart upload; local implementation via temporary parts
- [ ] **M6-06** Expired upload session cleanup
- [ ] **M6-07** Web: chunked upload for large files, resume after page reload or network loss
- [ ] **M6-08** CLI: chunked upload with resume

**Done when:** the same instance works with local storage or MinIO by configuration only, and a multi-gigabyte upload interrupted halfway resumes from the last uploaded part in both Web and CLI.

---

## M7 — Administration UI → v0.5

- [ ] **M7-01** Admin: list users, disable and enable users
- [ ] **M7-02** Admin: set user quotas
- [ ] **M7-03** Admin: create, list and revoke invites
- [ ] **M7-04** Instance settings stored in the database (registration mode, trash retention), overriding environment defaults
- [ ] **M7-05** Storage usage overview per user and per instance

**Done when:** an admin can run the instance day to day without using the API directly or editing environment variables.

---

## Later

Not scheduled. Each idea must pass the product boundary check in the README before it is planned.

- Search by file name
- Image thumbnails and previews
- OIDC / SSO login
- Browser-based device login flow for the CLI
- Presigned URLs for the S3 provider
- File versions
- Change feed API (foundation for a future sync client)
- Data migration between storage providers
- Backup and restore guide for self-hosters
- Clud Cloud (managed hosting)
