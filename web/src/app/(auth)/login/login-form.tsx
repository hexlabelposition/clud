"use client";

import { Form } from "@base-ui/react/form";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { FormAlert, SubmitButton, TextField } from "@/components/form";
import { api } from "@/lib/api/client";
import { failureMessage, unreachableMessage } from "@/lib/api/problems";
import { reloadTo, safeNext, signIn, thrownMessage } from "@/lib/auth";
import { validatePassword, validateUsername } from "@/lib/validation";

type Values = { username: string; password: string };

// Read on demand: the page stays static, without a Suspense boundary for
// useSearchParams.
function nextPath() {
  return safeNext(new URLSearchParams(window.location.search).get("next"));
}

export function LoginForm() {
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [alert, setAlert] = useState<string | null>(null);
  const [canRegister, setCanRegister] = useState(false);

  useEffect(() => {
    api
      .GET("/api/v1/me")
      .then(({ data }) => {
        if (data) {
          router.replace(nextPath());
        }
      })
      .catch(() => setAlert(unreachableMessage));
    api
      .GET("/api/v1/setup")
      .then(({ data }) => {
        if (data?.setupNeeded) {
          router.replace("/setup");
        }
      })
      .catch(() => {});
    api
      .GET("/api/v1/auth/registration")
      .then(({ data }) => setCanRegister(!!data && data.mode !== "CLOSED"))
      .catch(() => {});
  }, [router]);

  async function submit({ username, password }: Values) {
    setPending(true);
    setAlert(null);
    try {
      const { response } = await signIn(username, password);
      if (response.ok) {
        reloadTo(nextPath());
        return;
      }
      setAlert(
        response.status === 401
          ? "Incorrect username or password."
          : failureMessage(response),
      );
    } catch (error) {
      setAlert(thrownMessage(error));
    } finally {
      setPending(false);
    }
  }

  return (
    <Form<Values> onFormSubmit={submit} className="flex flex-col gap-4">
      <h1 className="text-lg font-semibold">Sign in</h1>
      <FormAlert>{alert}</FormAlert>
      <TextField
        name="username"
        label="Username"
        autoComplete="username"
        required
        validate={validateUsername}
      />
      <TextField
        name="password"
        label="Password"
        type="password"
        autoComplete="current-password"
        required
        validate={validatePassword}
      />
      <SubmitButton pending={pending}>Sign in</SubmitButton>
      {canRegister && (
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          No account?{" "}
          <Link href="/register" className="font-medium underline">
            Create one
          </Link>
        </p>
      )}
    </Form>
  );
}
