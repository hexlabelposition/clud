# Architecture

This document describes how Clud is built and why. Product goals and scope live in [README.md](README.md); planned work lives in [BACKLOG.md](BACKLOG.md).

Key decisions are summarized in the [decision log](#decision-log) at the end.

---

## 1. Overview

```text
                    Browser            CLI / scripts
                       │                    │
                       └─────────┬──────────┘
                                 │  HTTPS, one origin
                              ┌──┴───┐
                              │ Caddy│  reverse proxy, automatic TLS
                              └──┬───┘
                 /api/*          │           everything else
             ┌───────────────────┴───────────────────┐
             │                                       │
      ┌──────┴───────┐                        ┌──────┴──────┐
      │ Clud Server  │                        │  Clud Web   │
      │ Spring Boot  │                        │  Next.js    │
      └──┬────────┬──┘                        └─────────────┘
         │        │
  ┌──────┴───┐ ┌──┴────────────────────┐
  │PostgreSQL│ │ Storage backend       │
  │ metadata │ │ local FS / MinIO / S3 │
  └──────────┘ └───────────────────────┘
```

- **Server** is the core. It owns all business logic, authentication, authorization and the public API.
- **Web** is a UI. It contains no business logic and talks to the Server only through the public API, exactly like any third-party client.
- **CLI** is a standalone API client that works with any compatible Clud Server.
- **PostgreSQL** stores metadata. File contents never go into the database.
- **Storage backend** stores file contents behind a provider abstraction.

---

## 2. Monorepository

Clud keeps Web, Server and CLI in one repository to:

- keep the whole product in one place;
- change the API and its clients in a single pull request;
- maintain one issue tracker and release history.

The applications do **not** share source code just because they live together. Each one uses the native tooling of its ecosystem:

```text
web/     package.json, pnpm
server/  Gradle
cli/     go.mod, Go toolchain
```

The only shared artifact is the API contract, `api/openapi.yaml` (see [§11](#11-api)).

Root-level tooling only orchestrates common operations (`make dev`, `make test`, `make build`, `make openapi`). No JavaScript monorepo framework is introduced unless it brings a concrete benefit to the whole project.

---

## 3. Request routing

Web and API are served from **one origin**. Caddy routes requests by path:

```text
https://cloud.example.com/api/*   →  server:8080
https://cloud.example.com/*       →  web:3000
```

Why one origin:

- the browser calls the API directly, so large uploads and downloads do not pass through Next.js;
- cookie-based sessions work without CORS;
- self-hosters get automatic HTTPS from Caddy with a single config.

Alternatives considered: Next.js as a backend-for-frontend (extra hop for multi-gigabyte transfers, second auth layer) and separate domains with CORS (tokens in the browser, more configuration).

The development setup uses the same topology: `docker-compose.dev.yml` runs PostgreSQL and Caddy, while the Server and Web run on the host.

---

## 4. Web

Stack: Next.js, React, TypeScript, Tailwind CSS, Base UI, Zod, Playwright.

Responsibilities: first-run setup, login and registration, file browser, uploads and downloads, folder management, sharing, trash, account settings, personal access tokens, administration.

Rules:

- no business logic and no authorization decisions — the Server is the only source of truth;
- data is fetched from `/api/v1` on the client using a typed client generated from `api/openapi.yaml` (`openapi-typescript` + `openapi-fetch`);
- uploads are sent as raw request bodies so the Server can stream them; upload progress is reported from the browser.

---

## 5. Server

Stack: Java 25, Spring Boot, Spring Web, Spring Security, Spring Session JDBC, Spring Data JPA (Hibernate), Bean Validation, Spring Modulith, Flyway, springdoc-openapi, Testcontainers.

### 5.1 Modular monolith

The Server is one deployable application split into modules with explicit boundaries. Boundaries are enforced by **Spring Modulith**: a test verifies that modules only use each other's public API and that there are no dependency cycles.

```text
server/src/main/java/.../clud/server
├── common       shared kernel: IDs, errors, pagination
├── users        users, roles, quota limits
├── auth         sessions, personal access tokens, invites, first-run setup
├── files        node tree (files and folders), uploads, downloads, usage
├── storage      StorageProvider, stored objects, garbage collection
├── sharing      shares, public links, access grants
└── admin        instance settings, administrative APIs
```

Rules:

- each module owns its tables; other modules never query them directly;
- a module exposes a small public API (its top-level package); everything else lives in `internal` subpackages;
- cross-module side effects that do not need to be in the same transaction use Spring Modulith application events;
- dependencies point in one direction. Where a lower module needs data from a higher one, it defines an interface and the higher module implements it (dependency inversion).

Example: `files` must check whether a user may access a node, and access may come from a share. `files` defines an `AccessGrantLookup` interface; `sharing` implements it. `sharing` depends on `files`, but `files` does not depend on `sharing`.

The original separate `folders` and `permissions` modules are folded in: folders are nodes in `files`, and access decisions are made in `files` using grants supplied by `sharing`.

### 5.2 Background jobs

Implemented with Spring `@Scheduled`:

- deleting released and abandoned stored objects ([§9](#9-consistency-between-database-and-storage));
- purging expired trash, upload sessions, invites and public links.

Clud runs as a single Server instance. If multiple instances are ever needed, jobs get a distributed lock (e.g. ShedLock).

---

## 6. Authentication

### 6.1 Instance and registration

A Clud instance is **multi-user**.

- On a fresh instance, the Web app shows a first-run screen; the first account created becomes the **admin**.
- `GET /api/v1/setup` reports `setupNeeded`; `POST /api/v1/setup` accepts `username`, `password` and optional `email`, creates the admin and returns `201` without starting a session. Any existing account closes setup (`409`), including for concurrent requests; the check and insert are serialized by a PostgreSQL table lock in one transaction.
- Until setup completes, whoever reaches a fresh instance first becomes the admin. Complete setup before exposing the instance publicly; if this proves insufficient, setup can require a one-time token printed to the Server log or set via an environment variable.
- Registration mode is configurable: `invite` (default), `open` or `closed`.
- Admins create invites; an invite is a single-use token with an expiry.

### 6.2 Web: cookie sessions

- Login creates a server-side session stored in PostgreSQL (Spring Session JDBC), so sessions survive restarts.
- Session cookie: `HttpOnly`, `SameSite=Lax`, and `Secure` when `CLUD_PUBLIC_URL` uses `https`. Browsers drop `Secure` cookies received over plain HTTP (except on `localhost`), so an `http://` instance, e.g. LAN only, sends the cookie without `Secure`.
- `POST /api/v1/auth/login` accepts JSON `username` and `password`, returns `204`, rotates the session ID and issues the `SESSION` cookie. Username matching ignores case. Invalid credentials and disabled accounts return the same `401` Problem Details response; account status is checked only after the password matches, so a disabled account is not revealed by a faster response.
- The session stores the user's UUID as the principal name and their role, with credentials erased before persistence. Sessions expire after 30 minutes of inactivity. Flyway owns the JDBC session tables.
- `GET /api/v1/me` returns the authenticated user's current database profile: `id`, `username`, `email`, `role`, `quotaBytes` and `createdAt`. Email and quota can be `null`; a null quota means unlimited storage and zero allows no storage. Password hashes are never returned. Anonymous or expired sessions receive `401` Problem Details. So do sessions whose account is disabled or no longer exists, and the endpoint also invalidates such a session and clears its cookie, so clients can rely on `/me` to detect a lost login.
- `POST /api/v1/auth/logout` returns `204`, invalidates the JDBC session and clears its cookie. It also accepts anonymous sessions so clients can safely repeat logout, but like any mutation it needs a valid CSRF token: once the session has expired, fetch a fresh token first or expect `403`.
- Existing sessions are not revoked when an account changes: a disabled account or a changed role keeps working until the session expires, which an active client can postpone indefinitely. Change password (M1-10) and disabling a user must delete the user's other sessions; the principal name is the user UUID, so `FindByIndexNameSessionRepository` can find them.
- State-changing requests authenticated by cookie require a CSRF token.
- `GET /api/v1/auth/csrf` returns `headerName` and `token`, creating an anonymous session if necessary. Keep its cookie and send the token in the indicated header on login and logout. Fetch a fresh token after login or logout; both clear the previous token.
- Every `GET /api/v1/auth/csrf` without a cookie stores an anonymous session row for 30 minutes, so an unauthenticated client can create many rows. Cover the endpoint with rate limiting (M1-09); if that is not enough, switch to `CookieCsrfTokenRepository`, which keeps the token out of the database.
- `POST /api/v1/setup` is exempt from CSRF: it neither authenticates by cookie nor starts a session, and accepts only `application/json`, which a cross-site form cannot send. It stays exempt now that CSRF tokens exist: a token would protect no cookie authority and would make setup create an anonymous session first.
- Passwords are hashed with Spring Security's `DelegatingPasswordEncoder`, configured by `PasswordEncoderFactories` in `auth`. New hashes use bcrypt and retain the `{bcrypt}` prefix in `users.password_hash`, so the algorithm can be upgraded later without a migration.
- bcrypt rejects passwords longer than 72 bytes of UTF-8 (about 36 Cyrillic letters), so every endpoint that sets a password validates this limit and returns a validation error instead of failing. Setup and login each check it in their request records; extract a shared constraint once change password (M1-10) needs it too.
- Login attempts are rate-limited.

### 6.3 CLI and integrations: personal access tokens

- Format: `clud_pat_<random>`. Only a hash is stored; the token is shown once.
- Sent as `Authorization: Bearer <token>`; Bearer requests are exempt from CSRF.
- Each token has a name, optional expiry and `last_used_at`, and can be revoked.
- `clud login <url>` asks for username and password, and the Server issues a PAT named after the device. A browser-based device flow may replace this later.

### 6.4 Later

OIDC / SSO as an optional login method.

---

## 7. Authorization

Authorization is decided **only** on the Server. Clients are never trusted to enforce access rules.

A user may access a node if:

1. they own it, or
2. a share grants them access to the node **or any of its ancestors** (grants are inherited down the tree).

Permissions:

| Permission | Allows |
|-----------|--------|
| `READ`  | list, view metadata, download |
| `WRITE` | everything in `READ`, plus upload, rename, move within the shared subtree, delete |

Sharing a node and changing its shares is reserved for the owner.

Public links grant `READ` to anyone holding the link (and its password, if set) until it expires or is revoked.

---

## 8. Data model

### 8.1 Nodes and stored objects

Files and folders are **nodes** in one table. File contents are **stored objects** in a separate table. This keeps the separation between the logical file and its physical bytes:

- renaming or moving a file only changes metadata;
- replacing file contents points the node to a new object;
- file versions can be added later without changing the tree.

Each user has one root folder node, created at registration.

### 8.2 Tables (sketch)

```text
users
  id, username, email, password_hash, role (USER | ADMIN),
  quota_bytes, disabled, created_at

nodes                                          -- owned by files
  id, owner_id, parent_id (null = root), type (FILE | FOLDER),
  name, stored_object_id (null for folders), size, mime_type, sha256,
  version, created_at, updated_at,
  trashed_at, trash_root_id                    -- see §8.4

stored_objects                                 -- owned by storage
  id, storage_key, size, sha256,
  state (PENDING | COMMITTED | RELEASED), created_at, updated_at

storage_usage                                  -- owned by files
  user_id, used_bytes

shares                                         -- owned by sharing
  id, node_id, created_by, kind (USER | PUBLIC_LINK),
  grantee_id, permission (READ | WRITE),
  token_hash, password_hash, expires_at, created_at

personal_access_tokens                         -- owned by auth
  id, user_id, name, token_hash, last_used_at, expires_at, created_at

invites                                        -- owned by auth
  id, token_hash, created_by, expires_at, used_by, used_at

upload_sessions, upload_parts                  -- resumable uploads, §10.3
spring_session, spring_session_attributes      -- Spring Session JDBC
```

All IDs are **UUIDv7**, generated in the application.

Usernames consist of ASCII letters, digits, `.`, `_` and `-` (at most 64 characters) and are unique case-insensitively, with the original case preserved. Email is optional and stored trimmed and lowercased.
For `users.quota_bytes`, `NULL` means unlimited storage and `0` means no storage is allowed.

The schema is managed by **Flyway** migrations.

### 8.3 Tree rules

- Names are unique within a folder **case-insensitively**, while the original case is preserved: `Photo.jpg` and `photo.jpg` cannot coexist. Enforced by a unique index on `(parent_id, lower(name)) WHERE trashed_at IS NULL`.
- Names cannot be empty, `.` or `..`, or contain `/` or control characters.
- Subtree operations (move, trash, permission inheritance) use recursive CTEs.
- Moving a folder into its own descendant is rejected.

### 8.4 Trash

- Trashing a node sets `trashed_at` on it and on all its descendants, and sets `trash_root_id` to the node that was trashed. The trash view lists only trash roots; restoring a root restores everything with its `trash_root_id`.
- If the original name is taken when restoring, the Server returns `409 Conflict`.
- Trashed files count toward the quota.
- Trash is purged automatically after a configurable number of days, and can be emptied manually.

---

## 9. Storage

### 9.1 Provider abstraction

The rest of the Server works with `StorageProvider`, never with a specific backend. All operations are streaming — file contents are never loaded fully into memory.

```java
public interface StorageProvider {
    void put(String key, InputStream content, long size); // size = -1 if unknown
    InputStream get(String key, ByteRange range);          // range may be null
    void delete(String key);
    boolean exists(String key);
    ObjectMetadata metadata(String key);
}
```

Implementations:

```text
             StorageProvider
                  │
        ┌─────────┴─────────┐
        │                   │
 LocalStorageProvider   S3StorageProvider (AWS SDK v2)
                            │
                     ┌──────┴──────┐
                   MinIO          S3
```

Both implementations run the same contract test suite.

Storage keys are random and never reused: `objects/<first two chars>/<uuid>`. The prefix keeps local directories small.

### 9.2 Consistency between database and storage

Bytes and metadata cannot be written in one transaction, so Clud follows these rules:

**Upload**

1. `storage` creates a `stored_objects` row in state `PENDING` and writes the bytes under a new key.
2. In one database transaction, `files` creates or updates the node and `storage` marks the object `COMMITTED`.

If the Server crashes between the steps, the object stays `PENDING`; a background job deletes `PENDING` objects older than a threshold.

**Delete or replace**

1. In one transaction, the node is removed (or points to a new object), and the old object is marked `RELEASED`.
2. A background job deletes `RELEASED` objects from the backend and then their rows.

**Result:** the database never references missing bytes, and orphaned bytes are always cleaned up.

---

## 10. Uploads and downloads

All bytes flow through the Server as streams. Presigned URLs directly to S3 may be added later as an optimization for the S3 provider only.

### 10.1 Simple upload

```http
POST /api/v1/folders/{folderId}/files?name=report.pdf
Content-Type: application/pdf
Content-Length: 1048576

<raw bytes>
```

- The raw body is streamed to storage — no `multipart/form-data` buffering.
- SHA-256 is computed while streaming.
- The declared `Content-Length` is checked against the maximum upload size and the remaining quota before accepting the body; the actual byte count is enforced while streaming.
- Quota usage is updated atomically with a conditional update (`used_bytes + size <= quota`).
- The MIME type is detected on the Server (e.g. Apache Tika core); the client-provided type is not trusted.

Replacing a file's contents uses `PUT /api/v1/files/{id}/content` with `If-Match`.

### 10.2 Download

```http
GET /api/v1/files/{id}/content
```

- Supports `Range` requests (`206 Partial Content`) for resumed downloads and media seeking.
- Returns `ETag` based on the node version and checksum.

### 10.3 Resumable upload (later)

A Clud-specific chunked API, modeled after S3 multipart upload:

```text
POST   /api/v1/uploads                       create session (target folder, name, size)
PUT    /api/v1/uploads/{id}/parts/{n}        upload part n
GET    /api/v1/uploads/{id}                  list received parts (to resume)
POST   /api/v1/uploads/{id}/complete         assemble and commit
DELETE /api/v1/uploads/{id}                  abort
```

The S3 provider maps it to native multipart upload; the local provider stores parts as temporary files. Expired sessions are cleaned up by a background job. The tus protocol was considered; a native API maps more directly onto S3 and stays fully described in the OpenAPI contract.

### 10.4 Serving content safely

User files must never execute in the Clud origin.

- Default: `Content-Disposition: attachment`.
- Inline preview only for an allowlist: raster images (not SVG), PDF, audio/video, plain text.
- Always: `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox` on file responses.

---

## 11. API

The API is a first-class product surface used by Web, CLI, integrations and scripts.

### 11.1 Conventions

| Topic | Convention |
|-------|-----------|
| Style | REST, JSON |
| Versioning | path prefix `/api/v1` |
| Errors | RFC 9457 Problem Details (`application/problem+json`) |
| Pagination | cursor-based: `?limit=&cursor=`, response has `nextCursor` |
| IDs | UUIDv7 |
| Addressing | by ID (`/api/v1/nodes/{id}`); paths are resolved with `GET /api/v1/nodes/resolve?path=/photos/cat.jpg` |
| Concurrency | nodes expose `version` and `ETag`; rename, move and content replace require `If-Match`, otherwise `412 Precondition Failed` |
| Auth | session cookie (Web) or `Authorization: Bearer` PAT |

ID-based addressing keeps references stable across renames and moves. The CLI works with paths and resolves them through the API.

### 11.2 Contract and clients

The API is **code-first**:

1. springdoc-openapi generates the specification from the Server code.
2. A Gradle task writes it to `api/openapi.yaml`, which is committed.
3. CI regenerates the file and fails if it differs from the committed version.
4. Clients are generated from the committed file:
   - Web: `openapi-typescript` + `openapi-fetch`;
   - CLI: `oapi-codegen`.

A breaking API change therefore shows up in one pull request together with the Server and client changes.

---

## 12. CLI

Stack: Go, cobra, a client generated by `oapi-codegen`.

- Works with any compatible Clud Server; the server URL is part of the configuration.
- Configuration and token are stored in the user's config directory (`os.UserConfigDir()/clud`).
- Commands use paths (`clud ls photos/2026`) and resolve them through the API.
- Supports `--json` output for scripting and meaningful exit codes.
- Released as static binaries for Linux, macOS and Windows (GoReleaser).

---

## 13. Deployment

Docker is the primary deployment mechanism.

```text
docker-compose.yml
├── caddy      reverse proxy, TLS
├── web        Next.js production build
├── server     Spring Boot
├── postgres   with a persistent volume
└── minio      optional profile, when S3-compatible storage is wanted
```

Configuration is passed through environment variables, for example:

```text
CLUD_PUBLIC_URL=https://cloud.example.com
CLUD_REGISTRATION_MODE=invite
CLUD_STORAGE_TYPE=local          # local | s3
CLUD_STORAGE_LOCAL_PATH=/data
CLUD_MAX_UPLOAD_SIZE=10GB
CLUD_TRASH_RETENTION_DAYS=30
```

The application only needs to know its public URL. How the instance is exposed — LAN only, VPS, public IP, VPN, tunnel, custom domain — is a deployment concern.

Kubernetes and Helm configuration (a `deploy/` directory) is introduced only when there is a real need.

---

## 14. Testing

| Part | Tools |
|------|-------|
| Server | JUnit, Spring Boot tests, Testcontainers (PostgreSQL, MinIO), Spring Modulith verification |
| Web | component tests, Playwright end-to-end tests against a running stack |
| CLI | Go tests against a test Server |
| Storage | one contract test suite run against every provider |
| Authorization | a matrix of users × permissions × operations |

---

## 15. Architecture evolution

Clud stays a modular monolith while that is sufficient. Possible future services:

```text
Identity · Files · Sharing · Storage
```

A module is extracted only for a concrete reason: independent scaling or deployment, failure or resource isolation, or a substantially different workload. Microservices are not a goal by themselves. Clear module boundaries (§5.1) keep extraction possible without designing for it upfront.

---

## Decision log

| # | Decision | Chosen | Main reason |
|---|----------|--------|-------------|
| 1 | Web ↔ API routing | One origin via Caddy | Direct streaming, no CORS, automatic TLS |
| 2 | Authentication | Cookie sessions (Web) + PATs (CLI, API) | Simple revocation, nothing secret in the browser |
| 3 | Byte transfer | Streamed through the Server | Works with every provider; auth, quota and checksum in one place |
| 4 | Instance model | Multi-user, first user is admin, invite registration | Required for user-to-user sharing |
| 5 | Module boundaries | Spring Modulith | Verified boundaries without distributed complexity |
| 6 | License | AGPL-3.0 | Protects the open-source project in a hosted-service model |
| 7 | Tree model | One `nodes` table + `stored_objects` | Uniform sharing, trash and moves; versions possible later |
| 8 | Deletion | Trash with retention (after MVP) | Recoverable mistakes |
| 9 | DB/storage consistency | Write bytes first, commit metadata; GC by object state | No dangling references, automatic cleanup |
| 10 | Resumable uploads | Own chunked API (after MVP) | Maps to S3 multipart, documented in OpenAPI |
| 11 | API contract | Code-first, `api/openapi.yaml` committed, generated clients | One PR shows API and client changes |
| 12 | Addressing | By ID, paths resolved by the API | Stable references across renames |
