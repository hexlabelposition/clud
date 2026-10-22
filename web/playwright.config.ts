import { defineConfig, devices } from "@playwright/test";

// Against a running stack (e.g. through Caddy) set PLAYWRIGHT_BASE_URL;
// otherwise the dev server is started on port 3000.
const baseURL = process.env.PLAYWRIGHT_BASE_URL ?? "http://localhost:3000";

export default defineConfig({
  testDir: "./e2e",
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? "github" : "list",
  use: {
    baseURL,
    trace: "on-first-retry",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: process.env.PLAYWRIGHT_BASE_URL
    ? undefined
    : {
        // The local CLI also works in the browser image without a package manager.
        command: "node node_modules/next/dist/bin/next dev",
        url: baseURL,
        reuseExistingServer: !process.env.CI,
      },
});
