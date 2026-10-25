// Mirrors the Server's request constraints so a form can point at the field
// to fix: its 400 responses do not say which field was invalid. The Server
// still validates everything.

const encoder = new TextEncoder();

// bcrypt rejects passwords longer than 72 bytes of UTF-8.
const PASSWORD_MAX_BYTES = 72;

const USERNAME_MAX_LENGTH = 64;

const USERNAME_PATTERN = /^[A-Za-z0-9._-]+$/;

export const EMAIL_MAX_LENGTH = 254;

export const INVITE_TOKEN_MAX_LENGTH = 128;

export function validateUsername(value: unknown): string | null {
  const username = String(value ?? "");
  if (!username) {
    return "Enter a username.";
  }
  if (username.length > USERNAME_MAX_LENGTH) {
    return `Use at most ${USERNAME_MAX_LENGTH} characters.`;
  }
  if (!USERNAME_PATTERN.test(username)) {
    return "Use only Latin letters, digits, dots, hyphens and underscores.";
  }
  return null;
}

/** A password to sign in with or to confirm the current one. */
export function validatePassword(value: unknown): string | null {
  const password = String(value ?? "");
  if (!password) {
    return "Enter your password.";
  }
  return tooLong(password);
}

/** A password being set: it must not consist of whitespace only. */
export function validateNewPassword(value: unknown): string | null {
  const password = String(value ?? "");
  if (!password.trim()) {
    return "Enter a password that is not only spaces.";
  }
  return tooLong(password);
}

export function validateConfirmation(
  value: unknown,
  password: unknown,
): string | null {
  return value === password ? null : "Passwords do not match.";
}

function tooLong(password: string): string | null {
  return encoder.encode(password).length > PASSWORD_MAX_BYTES
    ? `Use at most ${PASSWORD_MAX_BYTES} bytes: about 72 Latin or 36 Cyrillic letters.`
    : null;
}
