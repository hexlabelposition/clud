import { expect, test } from "@playwright/test";

test("home page loads", async ({ page }) => {
  await page.goto("/");

  await expect(page).toHaveTitle("Clud");
  await expect(page.getByRole("heading", { name: "Clud" })).toBeVisible();
});

test("shows server health", async ({ page }) => {
  // Without PLAYWRIGHT_BASE_URL there is no Server behind the dev server.
  if (!process.env.PLAYWRIGHT_BASE_URL) {
    await page.route("/api/v1/health", (route) =>
      route.fulfill({ json: { status: "UP" } }),
    );
  }

  await page.goto("/");

  await expect(page.getByRole("status")).toHaveText("Server is up");
});
