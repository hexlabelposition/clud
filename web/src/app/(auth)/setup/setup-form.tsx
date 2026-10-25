"use client";

import { Form } from "@base-ui/react/form";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { FormAlert, SubmitButton, TextField } from "@/components/form";
import { api } from "@/lib/api/client";
import { failureMessage, unreachableMessage } from "@/lib/api/problems";
import { reloadTo, signInCreatedAccount, thrownMessage } from "@/lib/auth";
import {
  EMAIL_MAX_LENGTH,
  validateConfirmation,
  validateNewPassword,
  validateUsername,
} from "@/lib/validation";

type Values = {
  username: string;
  email: string;
  password: string;
  confirmation: string;
};

export function SetupForm() {
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [alert, setAlert] = useState<string | null>(null);

  // Setup is available only until the first account exists.
  useEffect(() => {
    api
      .GET("/api/v1/setup")
      .then(({ data }) => {
        if (data && !data.setupNeeded) {
          router.replace("/login");
        }
      })
      .catch(() => setAlert(unreachableMessage));
  }, [router]);

  async function submit({ username, email, password }: Values) {
    setPending(true);
    setAlert(null);
    try {
      const { response } = await api.POST("/api/v1/setup", {
        body: { username, password, email: email || undefined },
      });
      if (response.status === 409) {
        router.replace("/login");
        return;
      }
      if (!response.ok) {
        setAlert(failureMessage(response));
        return;
      }
      // Setup does not start a session; sign in with the new credentials.
      const signedIn = await signInCreatedAccount(username, password);
      reloadTo(signedIn ? "/" : "/login");
    } catch (error) {
      setAlert(thrownMessage(error));
    } finally {
      setPending(false);
    }
  }

  return (
    <Form<Values> onFormSubmit={submit} className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h1 className="text-lg font-semibold">Create the administrator</h1>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          This is a new Clud instance. The first account manages it.
        </p>
      </div>
      <FormAlert>{alert}</FormAlert>
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
      <SubmitButton pending={pending}>Create administrator</SubmitButton>
    </Form>
  );
}
