import { expect, test } from "@playwright/test";

import { MockServer, user } from "./mock-server";

test("home page greets the signed-in user", async ({ page }) => {
  test.skip(!!process.env.PLAYWRIGHT_BASE_URL, "Uses a mocked API");
  await MockServer.start(page, { user: user("alice") });

  await page.goto("/");

  await expect(page).toHaveTitle("Clud");
  await expect(
    page.getByRole("heading", { name: "Welcome, alice" }),
  ).toBeVisible();
});

test("shows server health", async ({ page }) => {
  // Without PLAYWRIGHT_BASE_URL there is no Server behind the dev server.
  if (!process.env.PLAYWRIGHT_BASE_URL) {
    await MockServer.start(page);
  }

  // Without a session this is the login screen, or setup on a fresh instance.
  await page.goto("/login");

  await expect(page.getByText("Server is up")).toBeVisible();
});
