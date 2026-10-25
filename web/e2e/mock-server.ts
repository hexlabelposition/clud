import type { Page, Request } from "@playwright/test";

import type { components } from "../src/lib/api/schema";

type Me = components["schemas"]["MeResponse"];
type Mode = components["schemas"]["RegistrationStatus"]["mode"];

export const CSRF_TOKEN = "csrf-token";

export function user(username: string, role: Me["role"] = "USER"): Me {
  return {
    id: "01920000-0000-7000-8000-000000000001",
    username,
    email: null,
    role,
    quotaBytes: null,
    createdAt: "2026-10-25T12:00:00Z",
  };
}

export type Reply = {
  status: number;
  json?: unknown;
  headers?: Record<string, string>;
};

export type Call = {
  headers: Record<string, string>;
  body: unknown;
};

/**
 * Stands in for the Server when the dev server runs without one. Answers the
 * session, setup, registration-mode, CSRF and health endpoints from mutable
 * state; tests add handlers for the operation under test, keyed by
 * "METHOD /path", and inspect the recorded calls.
 */
export class MockServer {
  user: Me | null = null;
  setupNeeded = false;
  mode: Mode = "INVITE";
  readonly handlers: Record<string, (request: Request) => Reply> = {};
  readonly calls: Record<string, Call[]> = {};

  static async start(page: Page, state: Partial<MockServer> = {}) {
    const server = Object.assign(new MockServer(), state);
    await page.route("**/api/v1/**", async (route) => {
      const request = route.request();
      const key = `${request.method()} ${new URL(request.url()).pathname}`;
      (server.calls[key] ??= []).push({
        headers: request.headers(),
        body: request.postData() ? request.postDataJSON() : null,
      });
      const reply = server.handlers[key]?.(request) ?? server.reply(key);
      await route.fulfill({
        status: reply.status,
        headers: reply.headers,
        contentType:
          reply.status >= 400 ? "application/problem+json" : "application/json",
        body: reply.json === undefined ? "" : JSON.stringify(reply.json),
      });
    });
    return server;
  }

  /** Calls recorded for "METHOD /path". */
  callsTo(key: string): Call[] {
    return this.calls[key] ?? [];
  }

  private reply(key: string): Reply {
    switch (key) {
      case "GET /api/v1/health":
        return { status: 200, json: { status: "UP" } };
      case "GET /api/v1/me":
        return this.user ? { status: 200, json: this.user } : problem(401);
      case "GET /api/v1/setup":
        return { status: 200, json: { setupNeeded: this.setupNeeded } };
      case "GET /api/v1/auth/registration":
        return { status: 200, json: { mode: this.mode } };
      case "GET /api/v1/auth/csrf":
        return {
          status: 200,
          json: { headerName: "X-CSRF-TOKEN", token: CSRF_TOKEN },
        };
      case "POST /api/v1/auth/logout":
        this.user = null;
        return { status: 204 };
      default:
        return problem(404);
    }
  }
}

export function problem(status: number, type?: string): Reply {
  return { status, json: { status, ...(type && { type }) } };
}
