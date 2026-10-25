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

Authentication screens (M1-11):

- `/setup`, `/login` and `/register` serve visitors without a session; all other screens live in the `(app)` route group behind `SessionGuard`.
- The route guard runs in the browser: it calls `GET /api/v1/me` on every navigation within the group. On `401` it sends the visitor to `/setup` while `GET /api/v1/setup` reports `setupNeeded`, otherwise to `/login?next=<path>`. Login follows `next` only for paths on the same origin. The guard only steers navigation; the Server authorizes every request.
- Guarded pages render only after the session check, so they are not part of the prerendered shell; each page in `(app)` sets `export const instant = false` to skip instant-navigation validation.
- Setup and registration do not start a session, so the Web app signs in with the new credentials right away, falling back to the login screen if that fails.
- With Cache Components, Next.js keeps visited screens mounted but hidden (React `<Activity>`), including typed form values and the signed-in user. Every move across a session boundary (sign in, sign out, a lost session) therefore uses a full page load (`reloadTo` in `src/lib/auth.ts`), so the next person at the browser cannot bring that state back. Within a session, screens that show sensitive data must reset it themselves, see the Next.js guide "Preserving UI state".
- Before hydration a browser would submit a form natively as `GET`, putting passwords into the URL, history and access logs. `SubmitButton` therefore renders with the native `disabled` attribute until hydration; every form must submit through it.
- Mutations fetch a fresh CSRF token right before the request, so login and logout clearing the token never leave a stale one behind. Each token costs one request against the CSRF rate limit (60 per minute by default). Screens that send many mutations, e.g. uploading many files in M2, must reuse the session's token and fetch a new one only after login, logout or a `403` without a problem type.
- Invitation links have the form `/register#invite=<token>`. The fragment never reaches Caddy, the Web server or their logs; the register screen fills in the token and removes it from the address bar. The invite screen in M7-03 must build links this way.
- The Server's `400` responses do not name the invalid field, so `src/lib/validation.ts` mirrors its constraints (username pattern and length, non-blank passwords of at most 72 UTF-8 bytes, email length) to show errors next to the field. Keep it in step with the request records.

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
- `CLUD_REGISTRATION_MODE` controls registration: `invite` (default) requires an invitation, `open` allows anyone to register after first-run setup, and `closed` forbids new registrations. Unknown values fail Server startup. The first-run administrator setup remains available in every mode.
- `GET /api/v1/auth/registration` returns `mode` (`INVITE`, `OPEN` or `CLOSED`) to anonymous clients, so the Web app decides whether to offer registration without trying it.
- `POST /api/v1/auth/register` accepts JSON `username`, `password`, optional `email` and optional `inviteToken`. In `open` mode it creates a `USER` with unlimited quota and returns `201` without authenticating or changing an existing session. Send a CSRF token from `GET /api/v1/auth/csrf`. In `closed` mode it returns `403` with type `urn:clud:problem:registration-closed` before the body is read. Otherwise invalid details return `400`; registration without an invitation in `invite` returns `403` with type `urn:clud:problem:invitation-required`; an invalid, expired or already used token returns `403` with type `urn:clud:problem:invalid-invitation`; incomplete setup and a case-insensitive username conflict return `409`. A CSRF failure is also `403` but without these registration problem types, so clients retry only that one with a fresh token. Setup and registration serialize account creation with the same database table lock, so registration cannot claim the first account and concurrent duplicate names return `409`. In `invite` mode a valid token is required; in `open` mode registration without a token still works, but any supplied token is validated and consumed. Closed mode refuses redemption even for a valid invitation.
- `open` mode lets anyone create any number of accounts, each with unlimited storage. Login, registration and CSRF token issuance are rate-limited (§6.2); before `open` suits a public instance it needs a default quota for registered users (e.g. `CLUD_DEFAULT_QUOTA`), applied by registration and invite redemption but not by setup.
- `POST /api/v1/admin/invites` accepts JSON `{}` or a future `expiresAt` (UTC timestamp). An enabled administrator creates a single-use invitation in any registration mode; the current role is checked in the database rather than trusting a stale session role. The response is `201` with `id` (UUIDv7), `token` and `expiresAt`, and `Cache-Control: no-store`. Expiry defaults to seven days and may be at most 90 days ahead, which bounds how long a leaked token stays usable. A valid CSRF token is required. Anonymous or unavailable accounts receive `401`, ordinary users receive `403`, and invalid request data receives `400`.
- Invite tokens contain 32 cryptographically random bytes with a `clud_inv_` prefix. Only the SHA-256 hash is stored; the plaintext is returned only on creation. The password is hashed before the registration transaction starts, so bcrypt holds neither a pooled connection nor the invite lock; malformed tokens are rejected before hashing, while well-formed unknown tokens still cost a hash within the registration rate limit. Registration then locks the invite row, verifies expiry and single use, creates the user and records `used_by` / `used_at` in one transaction. Expiry is checked again after account creation, so hashing or waiting for a database lock cannot extend an invitation. A failed registration leaves it available; concurrent redemptions create at most one account. Deleting the creator or the redeeming user deletes the invite (`ON DELETE CASCADE`); a deleted invite's token is simply unknown. Time-dependent rules read the shared `Clock` bean so tests can control time.

### 6.2 Web: cookie sessions

- Login creates a server-side session stored in PostgreSQL (Spring Session JDBC), so sessions survive restarts.
- Session cookie: `HttpOnly`, `SameSite=Lax`, and `Secure` when `CLUD_PUBLIC_URL` uses `https`. Browsers drop `Secure` cookies received over plain HTTP (except on `localhost`), so an `http://` instance, e.g. LAN only, sends the cookie without `Secure`.
- `POST /api/v1/auth/login` accepts JSON `username` and `password`, returns `204`, rotates the session ID and issues the `SESSION` cookie. Username matching ignores case. Invalid credentials and disabled accounts return the same `401` Problem Details response; account status is checked only after the password matches, so a disabled account is not revealed by a faster response.
- The session stores the user's UUID as the principal name and their role, with credentials erased before persistence. Sessions expire after 30 minutes of inactivity. Flyway owns the JDBC session tables.
- `GET /api/v1/me` returns the authenticated user's current database profile: `id`, `username`, `email`, `role`, `quotaBytes` and `createdAt`. Email and quota can be `null`; a null quota means unlimited storage and zero allows no storage. Password hashes are never returned. Anonymous or expired sessions receive `401` Problem Details. So do sessions whose account is disabled or no longer exists, and the endpoint also invalidates such a session and clears its cookie, so clients can rely on `/me` to detect a lost login.
- `POST /api/v1/auth/logout` returns `204`, invalidates the JDBC session and clears its cookie. It also accepts anonymous sessions so clients can safely repeat logout, but like any mutation it needs a valid CSRF token: once the session has expired, fetch a fresh token first or expect `403`.
- `POST /api/v1/auth/password` accepts JSON `currentPassword` and `newPassword` for an authenticated, enabled account and requires CSRF. The new password follows setup and registration validation (non-blank, at most 72 UTF-8 bytes); the current password is checked without trimming. Success returns `204` and deletes the user's other JDBC sessions. The current session stays logged in but its ID is rotated like at login, so clients must keep the new `SESSION` cookie; its attributes, including the CSRF token, carry over. The hash update and session deletion commit in one transaction; password hashing runs beforehand. A compare-and-set on the verified hash prevents concurrent changes from reusing an outdated password. Invalid input or an incorrect current password returns `400`; an account that is disabled or deleted, also while the change is in progress, returns `401`; CSRF failures return `403`; a concurrent password change returns `409`.
- Change password needs a session: it reads the current session ID to keep it. PAT authentication (M3-02) must either refuse this endpoint for Bearer requests or revoke all sessions when no session exists. M3-01 must decide whether a password change also revokes the user's PATs.
- Sessions are not yet revoked when an account is disabled or its role changes: they keep working until expiry, which an active client can postpone indefinitely. Disabling a user (M7-01) must revoke their sessions; the principal name is the user UUID.
- State-changing requests authenticated by cookie require a CSRF token.
- `GET /api/v1/auth/csrf` returns `headerName` and `token`, creating an anonymous session if necessary. Keep its cookie and send the token in the indicated header on login and logout. Fetch a fresh token after login or logout; both clear the previous token.
- Every `GET /api/v1/auth/csrf` without a cookie stores an anonymous session row for 30 minutes. Token issuance, including `HEAD`, shares the CSRF endpoint rate limit and is refused before creating another session when exhausted. If limiting issuance proves insufficient, switch to `CookieCsrfTokenRepository`, which keeps the token out of the database.
- `POST /api/v1/setup` is exempt from CSRF: it neither authenticates by cookie nor starts a session, and accepts only `application/json`, which a cross-site form cannot send. It stays exempt now that CSRF tokens exist: a token would protect no cookie authority and would make setup create an anonymous session first.
- Passwords are hashed with Spring Security's `DelegatingPasswordEncoder`, configured by `PasswordEncoderFactories` in `auth`. New hashes use bcrypt and retain the `{bcrypt}` prefix in `users.password_hash`, so the algorithm can be upgraded later without a migration.
- bcrypt rejects passwords longer than 72 bytes of UTF-8 (about 36 Cyrillic letters), so every endpoint that sets a password validates this limit and returns a validation error instead of failing. Request records mark password fields with `@BcryptPassword` in `auth`; change password uses it too.
- Rate limits apply per client and endpoint in a fixed window starting with the first admitted request: by default 10 `POST /api/v1/auth/login`, 5 `POST /api/v1/auth/register`, 5 `POST /api/v1/auth/password` and 60 `GET` / `HEAD /api/v1/auth/csrf` requests per 60 seconds. All requests count, including successful, malformed and CSRF-invalid requests. A security filter runs before CSRF validation and body parsing, so excess requests perform no password work and create no sessions or accounts. The change password limit bounds online guessing of the current password with a stolen session cookie. The response is `429` Problem Details with type `urn:clud:problem:rate-limit-exceeded` and a positive integer `Retry-After` in seconds, rounded up. Refusals do not extend the window; successes do not reset it. Logout, setup, health and registration-mode discovery keep their existing behavior.
- Configure these limits with `CLUD_AUTH_RATE_LIMIT_LOGIN_REQUESTS`, `CLUD_AUTH_RATE_LIMIT_REGISTER_REQUESTS`, `CLUD_AUTH_RATE_LIMIT_PASSWORD_REQUESTS`, `CLUD_AUTH_RATE_LIMIT_CSRF_REQUESTS` and `CLUD_AUTH_RATE_LIMIT_WINDOW_SECONDS`. Limits must be positive; the window is at most 86400 seconds. Counters are atomic within one Server process, are held in memory and reset on restart; replicas have independent budgets. Each endpoint retains at most `CLUD_AUTH_RATE_LIMIT_MAX_BUCKETS` client windows (default 10000, maximum 1000000), so flooding the CSRF endpoint cannot block new logins. Expired entries are swept at most once per second. When an endpoint's table is full, its new clients receive `429` with `Retry-After: 1`; active counters are preserved rather than evicted. An attacker with more distinct clients than the table holds per window, e.g. an IPv6 /48 split into /64s, can still deny that endpoint to new clients until windows expire; raise the maximum (roughly 150 bytes per window) if that becomes a concern.
- Client identity normally uses the connection peer IP. `CLUD_AUTH_RATE_LIMIT_TRUSTED_PROXIES` accepts comma-separated literal IPs/CIDRs; only those peers may supply `X-Forwarded-For`, and only a single literal address is accepted. Chains, duplicate headers and malformed values fall back to the peer; `Forwarded` and `X-Real-IP` are ignored. A client is its IPv4 address or its IPv6 /64 prefix, because one IPv6 host usually controls a whole /64 and could otherwise rotate addresses; IPv4-mapped IPv6 addresses count as IPv4. The bundled Caddy overwrites `X-Forwarded-For` with its connection peer IP. A Server running on the host trusts no proxy by default; for development through Caddy, configure its actual source IP. Production Compose trusts private IPv4 ranges by default because the Server port is not published; override this list for a different network, or set it empty to trust none. An additional proxy or tunnel in front of Caddy requires an explicit trusted-client-address configuration at the edge; the default then limits by that upstream peer. Clients behind the same NAT share their IP budget.
- If the host accepts IPv6 while Docker's IPv6 support is off, `docker-proxy` forwards those connections and Caddy sees the Docker gateway address for all of them, so every IPv6 client shares one budget. Enable IPv6 in Docker or publish Caddy over IPv4 only.
- Login, registration and change password must stay browser requests through Caddy. If the Web app called them from its own server (server actions, route handlers, SSR), every user would share the Web container's budget, and since that container sits in the trusted private network, clients could choose their `X-Forwarded-For`. The Web app currently makes no API requests from its server at all; the route guard calls `GET /api/v1/me` from the browser (§4).
- Limits are per client, not per account: an attacker with many addresses gets the login budget on each. Do not add account lockout, which would let anyone lock out the admin; if per-account protection is needed, count failures per username and add growing delays or require a proof such as a CAPTCHA after repeated failures.

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

Each user has one root folder node, created at registration. Accounts are created by setup and by registration (and invite redemption), so `users` publishes an account-created event that `files` handles; `users` must not depend on `files`.

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
