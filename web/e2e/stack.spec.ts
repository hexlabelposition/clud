import { expect, test } from "@playwright/test";

// The first-run journey of milestone M1 against a real Server: it needs a
// running stack with a fresh database and the default invite registration.
test.skip(!process.env.PLAYWRIGHT_BASE_URL, "Needs a running stack");

test("admin sets up, invites a user who registers and changes the password", async ({
  page,
}) => {
  const status = await (await page.request.get("/api/v1/setup")).json();
  test.skip(!status.setupNeeded, "Needs a fresh instance");

  await page.goto("/");
  await expect(page).toHaveURL("/setup");
  await page.getByLabel("Username").fill("admin");
  await page.getByLabel("Password", { exact: true }).fill("admin-password");
  await page.getByLabel("Confirm password").fill("admin-password");
  await page.getByRole("button", { name: "Create administrator" }).click();
  await expect(
    page.getByRole("heading", { name: "Welcome, admin" }),
  ).toBeVisible();

  // The Web app has no invite screen yet (M7-03); use the API with the
  // administrator's session.
  const csrf = await (await page.request.get("/api/v1/auth/csrf")).json();
  const invite = await page.request.post("/api/v1/admin/invites", {
    headers: { [csrf.headerName]: csrf.token },
    data: {},
  });
  expect(invite.status()).toBe(201);
  const { token } = await invite.json();

  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL("/login");

  await page.goto(`/register#invite=${token}`);
  await page.getByLabel("Username").fill("bob");
  await page.getByLabel("Password", { exact: true }).fill("bob-password");
  await page.getByLabel("Confirm password").fill("bob-password");
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(
    page.getByRole("heading", { name: "Welcome, bob" }),
  ).toBeVisible();

  await page.getByRole("link", { name: "Change password" }).click();
  await page.getByLabel("Current password").fill("bob-password");
  await page
    .getByLabel("New password", { exact: true })
    .fill("bob-new-password");
  await page.getByLabel("Confirm new password").fill("bob-new-password");
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(page.getByRole("status")).toHaveText(
    "Password changed. Other devices were signed out.",
  );

  // The rotated session cookie keeps working.
  await page.getByRole("link", { name: "Clud" }).click();
  await expect(
    page.getByRole("heading", { name: "Welcome, bob" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Sign out" }).click();

  await page.getByLabel("Username").fill("bob");
  await page.getByLabel("Password").fill("bob-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Incorrect username or password.")).toBeVisible();
  await page.getByLabel("Password").fill("bob-new-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(
    page.getByRole("heading", { name: "Welcome, bob" }),
  ).toBeVisible();
});
