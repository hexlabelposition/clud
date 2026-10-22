#!/usr/bin/env bash
set -euo pipefail

# Run from web/ after pnpm install. Match the image to the locked package version.
playwright_version=$(node -p "require('@playwright/test/package.json').version")
image="mcr.microsoft.com/playwright:v${playwright_version}-noble"

# "pull" only downloads the image, so the test step's timeout covers just the tests.
if [[ "${1:-}" == "pull" ]]; then
  docker pull "$image"
  exit 0
fi

# The image already has browsers and OS dependencies, avoiding apt mirror delays.
# Host networking reaches either the dev server or the production Caddy instance.
# The container user has no home directory, so Next.js telemetry is turned off.
docker run --rm --init --ipc=host --network=host \
  --user "$(id -u):$(id -g)" \
  --env CI=true \
  --env NEXT_TELEMETRY_DISABLED=1 \
  --env PLAYWRIGHT_BASE_URL \
  --volume "$PWD:/workspace" \
  --workdir /workspace \
  "$image" \
  node node_modules/@playwright/test/cli.js test --reporter=github,html
