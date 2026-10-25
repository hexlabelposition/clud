import { expect, test } from "@playwright/test";

import { CSRF_TOKEN, MockServer, problem, user } from "./mock-server";

// These tests answer every API call themselves; stack.spec.ts covers the
// same flows against a real Server.
test.skip(!!process.env.PLAYWRIGHT_BASE_URL, "Uses a mocked API");

test("sends a visitor to login and back to the requested page", async ({
  page,
}) => {
  const server = await MockServer.start(page);
  server.handlers["POST /api/v1/auth/login"] = () => {
    server.user = user("alice");
    return { status: 204 };
  };

  await page.goto("/settings/password");
  await expect(page).toHaveURL("/login?next=%2Fsettings%2Fpassword");

  await page.getByLabel("Username").fill("alice");
  await page.getByLabel("Password").fill("  secret  ");
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page).toHaveURL("/settings/password");
  await expect(
    page.getByRole("heading", { name: "Change password" }),
  ).toBeVisible();
  const [login] = server.callsTo("POST /api/v1/auth/login");
  expect(login.headers["x-csrf-token"]).toBe(CSRF_TOKEN);
  // Passwords are sent exactly as typed.
  expect(login.body).toEqual({ username: "alice", password: "  secret  " });
});

test("ignores a login redirect to another site", async ({ page }) => {
  const server = await MockServer.start(page);
  server.handlers["POST /api/v1/auth/login"] = () => {
    server.user = user("alice");
    return { status: 204 };
  };

  await page.goto("/login?next=//example.com");
  await page.getByLabel("Username").fill("alice");
  await page.getByLabel("Password").fill("secret");
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page).toHaveURL("/");
});

test("reports wrong credentials and rate limiting", async ({ page }) => {
  const server = await MockServer.start(page);
  server.handlers["POST /api/v1/auth/login"] = () => problem(401);

  await page.goto("/login");
  await page.getByLabel("Username").fill("alice");
  await page.getByLabel("Password").fill("wrong");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Incorrect username or password.")).toBeVisible();

  server.handlers["POST /api/v1/auth/login"] = () => ({
    ...problem(429, "urn:clud:problem:rate-limit-exceeded"),
    headers: { "Retry-After": "42" },
  });
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(
    page.getByText("Too many attempts. Try again in 42 s."),
  ).toBeVisible();
});

test("offers registration unless it is closed", async ({ page }) => {
  const server = await MockServer.start(page, { mode: "OPEN" });
  await page.goto("/login");
  await expect(page.getByRole("link", { name: "Create one" })).toBeVisible();

  server.mode = "CLOSED";
  await page.goto("/register");
  await expect(page.getByText("Registration is closed")).toBeVisible();
  await expect(page.getByLabel("Username")).toHaveCount(0);
  await page.goto("/login");
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Create one" })).toHaveCount(0);
});

test("creates the administrator on a fresh instance", async ({ page }) => {
  const server = await MockServer.start(page, { setupNeeded: true });
  server.handlers["POST /api/v1/setup"] = () => {
    server.setupNeeded = false;
    return { status: 201 };
  };
  server.handlers["POST /api/v1/auth/login"] = () => {
    server.user = user("admin", "ADMIN");
    return { status: 204 };
  };

  await page.goto("/");
  await expect(page).toHaveURL("/setup");
  await page.getByLabel("Username").fill("admin");
  await page.getByLabel("Password", { exact: true }).fill("Пароль-123");
  await page.getByLabel("Confirm password").fill("Пароль-123");
  await page.getByRole("button", { name: "Create administrator" }).click();

  await expect(page).toHaveURL("/");
  await expect(
    page.getByRole("heading", { name: "Welcome, admin" }),
  ).toBeVisible();
  await expect(page.getByText("admin (administrator)")).toBeVisible();
  // An empty email is left out rather than sent as "".
  expect(server.callsTo("POST /api/v1/setup")[0].body).toEqual({
    username: "admin",
    password: "Пароль-123",
  });
});

test("validates fields before calling the Server", async ({ page }) => {
  const server = await MockServer.start(page, { setupNeeded: true });

  await page.goto("/setup");
  await page.getByLabel("Username").fill("not valid");
  await page.getByLabel("Password", { exact: true }).fill("я".repeat(37));
  await page.getByLabel("Confirm password").fill("something else");
  await page.getByRole("button", { name: "Create administrator" }).click();

  await expect(
    page.getByText(
      "Use only Latin letters, digits, dots, hyphens and underscores.",
    ),
  ).toBeVisible();
  await expect(page.getByText("Use at most 72 bytes")).toBeVisible();
  await expect(page.getByText("Passwords do not match.")).toBeVisible();

  await page.getByLabel("Password", { exact: true }).fill("   ");
  await expect(
    page.getByText("Enter a password that is not only spaces."),
  ).toBeVisible();
  expect(server.callsTo("POST /api/v1/setup")).toHaveLength(0);
});

test("registers with an invitation link", async ({ page }) => {
  const server = await MockServer.start(page);
  server.handlers["POST /api/v1/auth/register"] = () => ({ status: 201 });
  server.handlers["POST /api/v1/auth/login"] = () => {
    server.user = user("bob");
    return { status: 204 };
  };

  await page.goto("/register#invite=clud_inv_secret");
  await expect(page.getByLabel("Invitation token")).toHaveValue(
    "clud_inv_secret",
  );
  // The token does not stay in the address bar or history.
  await expect(page).toHaveURL("/register");

  await page.getByLabel("Username").fill("bob");
  await page.getByLabel("Email (optional)").fill("bob@example.com");
  await page.getByLabel("Password", { exact: true }).fill("bob-password");
  await page.getByLabel("Confirm password").fill("bob-password");
  await page.getByRole("button", { name: "Create account" }).click();

  await expect(
    page.getByRole("heading", { name: "Welcome, bob" }),
  ).toBeVisible();
  const [register] = server.callsTo("POST /api/v1/auth/register");
  expect(register.headers["x-csrf-token"]).toBe(CSRF_TOKEN);
  expect(register.body).toEqual({
    username: "bob",
    email: "bob@example.com",
    password: "bob-password",
    inviteToken: "clud_inv_secret",
  });
});

test("reports invalid invitations and taken usernames", async ({ page }) => {
  const server = await MockServer.start(page);
  server.handlers["POST /api/v1/auth/register"] = () =>
    problem(403, "urn:clud:problem:invalid-invitation");

  await page.goto("/register");
  await page.getByLabel("Invitation token").fill("clud_inv_used");
  await page.getByLabel("Username").fill("bob");
  await page.getByLabel("Password", { exact: true }).fill("bob-password");
  await page.getByLabel("Confirm password").fill("bob-password");
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(
    page.getByText("This invitation is invalid, expired or already used."),
  ).toBeVisible();

  // A field the Server rejected stays invalid until it is edited.
  server.handlers["POST /api/v1/auth/register"] = () => problem(409);
  await page.getByLabel("Invitation token").fill("clud_inv_fresh");
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByText("This username is already taken.")).toBeVisible();
});

test("changes the password", async ({ page }) => {
  const server = await MockServer.start(page, { user: user("alice") });
  server.handlers["POST /api/v1/auth/password"] = () => problem(400);

  await page.goto("/");
  await page.getByRole("link", { name: "Change password" }).click();
  await page.getByLabel("Current password").fill("wrong");
  await page.getByLabel("New password", { exact: true }).fill("new-password");
  await page.getByLabel("Confirm new password").fill("new-password");
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(page.getByText("Current password is incorrect.")).toBeVisible();

  server.handlers["POST /api/v1/auth/password"] = () => ({ status: 204 });
  await page.getByLabel("Current password").fill("old-password");
  await page.getByRole("button", { name: "Change password" }).click();

  await expect(page.getByRole("status")).toHaveText(
    "Password changed. Other devices were signed out.",
  );
  await expect(page.getByLabel("Current password")).toHaveValue("");
  const calls = server.callsTo("POST /api/v1/auth/password");
  expect(calls[1].headers["x-csrf-token"]).toBe(CSRF_TOKEN);
  expect(calls[1].body).toEqual({
    currentPassword: "old-password",
    newPassword: "new-password",
  });
});

test("returns to login when the session ends", async ({ page }) => {
  const server = await MockServer.start(page, { user: user("alice") });

  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "Welcome, alice" }),
  ).toBeVisible();

  // Another device changed the password and revoked this session.
  server.user = null;
  await page.getByRole("link", { name: "Change password" }).click();
  await expect(page).toHaveURL("/login?next=%2Fsettings%2Fpassword");
});

test("signs out and drops the state of visited screens", async ({ page }) => {
  const server = await MockServer.start(page, { user: user("alice") });

  await page.goto("/");
  // Next.js keeps visited screens mounted but hidden, typed values included.
  await page.getByRole("link", { name: "Change password" }).click();
  await page.getByLabel("Current password").fill("typed-but-not-sent");
  await page.getByRole("link", { name: "Clud" }).click();
  await page.getByRole("button", { name: "Sign out" }).click();

  await expect(page).toHaveURL("/login");
  await expect(page.locator('input[name="currentPassword"]')).toHaveCount(0);
  const [logout] = server.callsTo("POST /api/v1/auth/logout");
  expect(logout.headers["x-csrf-token"]).toBe(CSRF_TOKEN);
});

test.describe("before hydration", () => {
  test.use({ javaScriptEnabled: false });

  test("the login form cannot be submitted natively", async ({ page }) => {
    await MockServer.start(page);

    await page.goto("/login");
    await page.getByLabel("Username").fill("alice");
    await page.getByLabel("Password").fill("secret");
    await page.getByLabel("Password").press("Enter");

    // A native GET submission would put the password into the URL.
    await expect(page.getByRole("button", { name: "Sign in" })).toBeDisabled();
    expect(page.url()).not.toContain("secret");
  });
});
