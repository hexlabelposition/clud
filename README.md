# Clud

Clud is a lightweight and straightforward cloud file storage platform.

It gives you a simple place to store, organize and share files through the web — and it is easy to self-host and developer-friendly through a public HTTP API and CLI.

> **Status:** early development. Nothing is released yet — see [BACKLOG.md](BACKLOG.md) for the plan. The first release (v0.1) will include the Web app, the Server and the CLI.

---

## Why Clud

Clud aims to be:

- **simple** — files and folders, not a productivity suite;
- **lightweight** — one server, one database, one storage backend;
- **easy to self-host** — `docker compose up -d` on a home server, VPS or NAS;
- **developer-friendly** — a documented, versioned REST API and a CLI are first-class parts of the product, not afterthoughts.

The same core product is meant to run both self-hosted and, in the future, as a managed service (Clud Cloud).

---

## Features

Planned for the first releases:

- accounts, with an admin and invite-based registration;
- folders: create, rename, move, delete;
- streaming uploads and downloads, including resumable downloads (HTTP Range);
- storage quotas;
- sharing: public links (with optional expiry and password) and user-to-user shares with read/write permissions;
- trash with restore and automatic cleanup;
- local filesystem storage, MinIO and S3-compatible storage;
- resumable uploads for large files;
- public REST API (`/api/v1`) with an OpenAPI specification;
- `clud` CLI that works with any compatible Clud server;
- personal access tokens for the CLI, scripts and integrations.

---

## Non-goals

Clud intentionally does **not** try to be:

- a desktop sync client (like Dropbox) — not in v1; the API is designed so one can be built later;
- an office suite: no document editing, calendars, contacts, notes or chat;
- end-to-end encrypted storage — encryption at rest is left to the disk or storage backend;
- a file versioning system — not in v1;
- a mobile app — the Web app is responsive instead;
- a full-text search engine for file contents — search by name is planned;
- a federated or deduplicating storage system.

Before adding a major capability, it should pass the product boundary check:

1. Does it improve storing files?
2. Does it improve accessing files?
3. Does it improve organizing files?
4. Does it improve sharing files?
5. Does it improve self-hosting?
6. Does it improve developer access through API or CLI?
7. Does the benefit justify the added complexity?

If not, the feature likely does not belong in Clud.

---

## Deployment models

**Self-hosted** — you run Clud on your own infrastructure (home server, VPS, NAS, company servers) and control the deployment, database, storage, backups, domain and networking.

**Clud Cloud** *(future)* — a managed instance of the same product: no servers to run, storage and database managed for you.

The Web app, API and CLI behave the same way in both models.

---

## Quick start

> Not available yet — this is the target workflow.

```bash
git clone https://github.com/<owner>/clud.git
cd clud
cp .env.example .env      # set CLUD_PUBLIC_URL and secrets
docker compose up -d
```

Open your public URL and create the admin account on the first-run screen.

### CLI

```bash
clud login https://cloud.example.com

clud ls
clud mkdir photos
clud upload photo.jpg photos/
clud download photos/photo.jpg
clud share photos/photo.jpg
```

---

## Repository layout

Clud is a monorepository of independent applications. Each one uses the native tooling of its ecosystem.

```text
clud/
├── web/                     # Next.js, React, TypeScript
├── server/                  # Java 25, Spring Boot, Gradle
├── cli/                     # Go
├── api/
│   └── openapi.yaml         # API contract, generated from the server
├── Caddyfile                # reverse proxy: /api → server, / → web
├── docker-compose.yml
├── docker-compose.dev.yml
├── Makefile                 # dev / test / build orchestration
├── README.md
├── ARCHITECTURE.md
├── BACKLOG.md
└── LICENSE
```

## Tech stack

| Part   | Stack |
|--------|-------|
| Web    | Next.js, React, TypeScript, Tailwind CSS, Base UI, Zod, openapi-fetch, Playwright |
| Server | Java 25, Spring Boot, Spring Web, Spring Security, Spring Session JDBC, Spring Data JPA, Spring Modulith, Flyway, springdoc-openapi, Testcontainers |
| CLI    | Go, cobra, oapi-codegen |
| Data   | PostgreSQL; local filesystem, MinIO or S3 for file contents |
| Deploy | Docker, Docker Compose, Caddy |

## Development

```bash
make dev       # start Postgres and Caddy, run server and web locally
make test      # run tests of all applications
make build     # build all applications
make openapi   # regenerate api/openapi.yaml and the Web/CLI clients
```

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — how Clud is built and why.
- [BACKLOG.md](BACKLOG.md) — milestones and tasks.

## License

Clud is licensed under the [GNU Affero General Public License v3.0](LICENSE).
