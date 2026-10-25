// Problem Details types the Server returns for errors a form must tell apart.
export const problemTypes = {
  rateLimitExceeded: "urn:clud:problem:rate-limit-exceeded",
  registrationClosed: "urn:clud:problem:registration-closed",
  invitationRequired: "urn:clud:problem:invitation-required",
  invalidInvitation: "urn:clud:problem:invalid-invitation",
} as const;

export const unreachableMessage =
  "Cannot reach the server. Check your connection and try again.";

export function problemType(error: unknown): string | undefined {
  if (typeof error === "object" && error !== null && "type" in error) {
    return typeof error.type === "string" ? error.type : undefined;
  }
  return undefined;
}

/** A message for failures a form has no specific handling for. */
export function failureMessage(response: Response): string {
  if (response.status === 429) {
    const seconds = Number(response.headers.get("Retry-After"));
    return Number.isInteger(seconds) && seconds > 0
      ? `Too many attempts. Try again in ${seconds} s.`
      : "Too many attempts. Try again later.";
  }
  if (response.status >= 500) {
    return "The server ran into a problem. Try again later.";
  }
  return "Something went wrong. Try again.";
}
