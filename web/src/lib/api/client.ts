import createClient from "openapi-fetch";

import type { paths } from "./schema";

// Web and API share one origin (Caddy routes /api/* to the Server), and the
// paths in the specification already include /api/v1.
export const api = createClient<paths>();
