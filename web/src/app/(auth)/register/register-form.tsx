"use client";

import { Form } from "@base-ui/react/form";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import { FormAlert, SubmitButton, TextField } from "@/components/form";
import { api } from "@/lib/api/client";
import {
  failureMessage,
  problemType,
  problemTypes,
  unreachableMessage,
} from "@/lib/api/problems";
import type { components } from "@/lib/api/schema";
import {
  csrfHeader,
  reloadTo,
  signInCreatedAccount,
  thrownMessage,
} from "@/lib/auth";
import {
  EMAIL_MAX_LENGTH,
  INVITE_TOKEN_MAX_LENGTH,
  validateConfirmation,
  validateNewPassword,
  validateUsername,
} from "@/lib/validation";

type Mode = components["schemas"]["RegistrationStatus"]["mode"];

type Values = {
  username: string;
  email: string;
  password: string;
  confirmation: string;
  inviteToken?: string;
};

/**
 * Takes the token from an invitation link `/register#invite=<token>`. The
 * fragment never reaches a server or its logs, and is removed from the address
 * bar and history once read.
 */
function takeInviteToken(): string {
  const token =
    new URLSearchParams(window.location.hash.slice(1)).get("invite") ?? "";
  if (window.location.hash) {
    window.history.replaceState(
      window.history.state,
      "",
      window.location.pathname + window.location.search,
    );
  }
  return token;
}

export function RegisterForm() {
  const router = useRouter();
  const [mode, setMode] = useState<Mode | null>(null);
  const [inviteToken, setInviteToken] = useState("");
  const [pending, setPending] = useState(false);
  const [alert, setAlert] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  // Taking the token clears the fragment, so do it once even when the effect
  // runs twice (React Strict Mode in development).
  const linkToken = useRef<string | null>(null);

  useEffect(() => {
    linkToken.current ??= takeInviteToken();
    api
      .GET("/api/v1/auth/registration")
      .then(({ data, response }) => {
        if (!data) {
          setAlert(failureMessage(response));
          return;
        }
        setInviteToken(linkToken.current ?? "");
        setMode(data.mode);
      })
      .catch(() => setAlert(unreachableMessage));
  }, []);

  async function submit({ username, email, password, inviteToken }: Values) {
    setPending(true);
    setAlert(null);
    setErrors({});
    try {
      const { error, response } = await api.POST("/api/v1/auth/register", {
        params: { header: await csrfHeader() },
        body: {
          username,
          password,
          email: email || undefined,
          inviteToken: inviteToken || undefined,
        },
      });
      if (response.ok) {
        // Registration does not start a session; sign in right away.
        const signedIn = await signInCreatedAccount(username, password);
        reloadTo(signedIn ? "/" : "/login");
        return;
      }
      await handleFailure(response, problemType(error));
    } catch (error) {
      setAlert(thrownMessage(error));
    } finally {
      setPending(false);
    }
  }

  async function handleFailure(response: Response, type: string | undefined) {
    switch (type) {
      case problemTypes.registrationClosed:
        setMode("CLOSED");
        return;
      case problemTypes.invitationRequired:
        setMode("INVITE");
        setErrors({ inviteToken: "Registration requires an invitation." });
        return;
      case problemTypes.invalidInvitation:
        setErrors({
          inviteToken: "This invitation is invalid, expired or already used.",
        });
        return;
    }
    if (response.status === 403) {
      setAlert("Your session expired. Submit the form again.");
    } else if (response.status === 409) {
      // Either the username is taken or the instance has no administrator yet.
      const { data } = await api.GET("/api/v1/setup");
      if (data?.setupNeeded) {
        router.replace("/setup");
      } else {
        setErrors({ username: "This username is already taken." });
      }
    } else {
      setAlert(failureMessage(response));
    }
  }

  if (mode === "CLOSED") {
    return (
      <div className="flex flex-col gap-4">
        <h1 className="text-lg font-semibold">Create account</h1>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          Registration is closed on this instance. Ask an administrator for an
          account.
        </p>
        <SignInLink />
      </div>
    );
  }

  return (
    <Form<Values>
      errors={errors}
      onFormSubmit={submit}
      className="flex flex-col gap-4"
    >
      <h1 className="text-lg font-semibold">Create account</h1>
      <FormAlert>{alert}</FormAlert>
      {mode === null ? (
        !alert && (
          <p role="status" className="text-sm text-zinc-600">
            Loading…
          </p>
        )
      ) : (
        <>
          {(mode === "INVITE" || inviteToken) && (
            <TextField
              name="inviteToken"
              label="Invitation token"
              type="password"
              autoComplete="off"
              required={mode === "INVITE"}
              maxLength={INVITE_TOKEN_MAX_LENGTH}
              defaultValue={inviteToken}
              description="Filled in automatically when you open an invitation link."
            />
          )}
          <TextField
            name="username"
            label="Username"
            autoComplete="username"
            required
            validate={validateUsername}
          />
          <TextField
            name="email"
            label="Email (optional)"
            type="email"
            autoComplete="email"
            maxLength={EMAIL_MAX_LENGTH}
          />
          <TextField
            name="password"
            label="Password"
            type="password"
            autoComplete="new-password"
            required
            validate={validateNewPassword}
          />
          <TextField
            name="confirmation"
            label="Confirm password"
            type="password"
            autoComplete="new-password"
            required
            validate={(value, values) =>
              validateConfirmation(value, values.password)
            }
          />
          <SubmitButton pending={pending}>Create account</SubmitButton>
        </>
      )}
      <SignInLink />
    </Form>
  );
}

function SignInLink() {
  return (
    <p className="text-sm text-zinc-600 dark:text-zinc-400">
      Already have an account?{" "}
      <Link href="/login" className="font-medium underline">
        Sign in
      </Link>
    </p>
  );
}
