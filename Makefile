# Common tasks for the whole repository. Run `make help` for the list.

# Settings for the Server and Docker Compose; see .env.example.
-include .env
export

DEV_COMPOSE := docker compose -f docker-compose.dev.yml

.PHONY: help dev dev-services dev-server dev-web stop \
	test test-server test-web test-cli \
	build build-server build-web build-cli \
	lint lint-web lint-cli openapi

help: ## Show this help
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F ':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

dev: dev-services ## Start PostgreSQL and Caddy, then the Server and Web on the host
	$(MAKE) -j2 dev-server dev-web

dev-services: ## Start PostgreSQL and Caddy in Docker
	$(DEV_COMPOSE) up -d

dev-server: ## Run the Server on the host
	cd server && ./gradlew bootRun

dev-web: ## Run the Web dev server on the host
	cd web && pnpm dev

stop: ## Stop PostgreSQL and Caddy
	$(DEV_COMPOSE) down

test: test-server test-web test-cli ## Run all tests

test-server: ## Run Server tests (needs Docker)
	cd server && ./gradlew test

test-web: ## Run Web end-to-end tests
	cd web && pnpm test:e2e

test-cli: ## Run CLI tests
	cd cli && go test ./...

build: build-server build-web build-cli ## Build all apps

build-server: ## Build the Server jar
	cd server && ./gradlew bootJar

build-web: ## Build the Web app
	cd web && pnpm build

build-cli: ## Build the clud binary into cli/bin
	cd cli && go build -o bin/clud ./cmd/clud

lint: lint-web lint-cli ## Run all linters

lint-web: ## Lint, type-check and check formatting of the Web app
	cd web && pnpm lint && pnpm typecheck && pnpm format:check

lint-cli: ## Vet the CLI and check formatting
	cd cli && go vet ./...
	@cd cli && test -z "$$(gofmt -l .)" || { gofmt -l .; echo "Run gofmt -w . in cli/"; exit 1; }

openapi: ## Regenerate api/openapi.yaml and the Web and CLI clients
	cd server && ./gradlew openapi
	cd web && pnpm generate:api
	cd cli && go generate ./...
