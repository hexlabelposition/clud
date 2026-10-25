import { api } from "./api/client";
import { failureMessage, unreachableMessage } from "./api/problems";
import type { components } from "./api/schema";

export type CurrentUser = components["schemas"]["MeResponse"];

/**
 * Fetches a fresh CSRF token for the current session. Login and logout clear
 * the previous token, so request one right before every mutation instead of
 * keeping it around.
 */
export async function csrfHeader(): Promise<{ "X-CSRF-TOKEN": string }> {
  const { data, response } = await api.GET("/api/v1/auth/csrf");
  if (!data) {
    throw new CsrfError(response);
  }
  return { "X-CSRF-TOKEN": data.token };
}

/** The CSRF token request failed, e.g. because of its rate limit. */
export class CsrfError extends Error {
  constructor(readonly response: Response) {
    super(`CSRF token request failed with ${response.status}`);
  }
}

/** A message for an exception thrown while calling the API. */
export function thrownMessage(error: unknown): string {
  return error instanceof CsrfError
    ? failureMessage(error.response)
    : unreachableMessage;
}

/** Starts a session; the browser stores the SESSION cookie. */
export async function signIn(username: string, password: string) {
  return api.POST("/api/v1/auth/login", {
    params: { header: await csrfHeader() },
    body: { username, password },
  });
}

/**
 * Signs in after an account was created. Reports only success: on failure
 * the caller sends the user to the login screen, where they can retry.
 */
export async function signInCreatedAccount(
  username: string,
  password: string,
): Promise<boolean> {
  try {
    const { response } = await signIn(username, password);
    return response.ok;
  } catch {
    return false;
  }
}

export async function signOut() {
  return api.POST("/api/v1/auth/logout", {
    params: { header: await csrfHeader() },
  });
}

/**
 * Navigates with a full page load. With Cache Components, Next.js keeps
 * visited screens mounted but hidden (React Activity), including typed form
 * values and the signed-in user. Crossing a session boundary must drop that
 * state, so the next person at this browser cannot bring it back.
 */
export function reloadTo(path: string) {
  window.location.replace(path);
}

/**
 * Where to send someone without a session: the first-run screen on a fresh
 * instance, otherwise the login screen, returning to `next` afterwards.
 */
export async function anonymousDestination(next: string): Promise<string> {
  try {
    const { data } = await api.GET("/api/v1/setup");
    if (data?.setupNeeded) {
      return "/setup";
    }
  } catch {
    // The login screen reports an unreachable server itself.
  }
  const target = safeNext(next);
  return target === "/"
    ? "/login"
    : `/login?next=${encodeURIComponent(target)}`;
}

/**
 * Accepts only paths within this origin, so a crafted login link cannot
 * redirect to another site after signing in.
 */
export function safeNext(value: string | null | undefined): string {
  if (
    !value ||
    !value.startsWith("/") ||
    value.startsWith("//") ||
    value.startsWith("/\\")
  ) {
    return "/";
  }
  return value;
}
