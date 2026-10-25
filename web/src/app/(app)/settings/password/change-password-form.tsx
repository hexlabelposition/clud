"use client";

import { Form } from "@base-ui/react/form";
import { usePathname } from "next/navigation";
import { useState } from "react";

import {
  FormAlert,
  FormNotice,
  SubmitButton,
  TextField,
} from "@/components/form";
import { api } from "@/lib/api/client";
import { failureMessage } from "@/lib/api/problems";
import {
  anonymousDestination,
  csrfHeader,
  reloadTo,
  thrownMessage,
} from "@/lib/auth";
import {
  validateConfirmation,
  validateNewPassword,
  validatePassword,
} from "@/lib/validation";

type Values = {
  currentPassword: string;
  newPassword: string;
  confirmation: string;
};

export function ChangePasswordForm() {
  const pathname = usePathname();
  // A new key remounts the form, clearing the fields after a change.
  const [formKey, setFormKey] = useState(0);
  const [pending, setPending] = useState(false);
  const [alert, setAlert] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});

  async function submit({ currentPassword, newPassword }: Values) {
    setPending(true);
    setAlert(null);
    setNotice(null);
    setErrors({});
    try {
      // The Server keeps the CSRF token and rotates the session cookie, which
      // the browser stores from the response.
      const { response } = await api.POST("/api/v1/auth/password", {
        params: { header: await csrfHeader() },
        body: { currentPassword, newPassword },
      });
      if (response.ok) {
        setFormKey((key) => key + 1);
        setNotice("Password changed. Other devices were signed out.");
      } else if (response.status === 400) {
        // The fields are validated here, so the Server rejected the password.
        setErrors({ currentPassword: "Current password is incorrect." });
      } else if (response.status === 401) {
        reloadTo(await anonymousDestination(pathname));
      } else if (response.status === 409) {
        setAlert(
          "The password was just changed elsewhere. Enter the new current password.",
        );
      } else {
        setAlert(failureMessage(response));
      }
    } catch (error) {
      setAlert(thrownMessage(error));
    } finally {
      setPending(false);
    }
  }

  return (
    <section className="flex w-full max-w-sm flex-col gap-4">
      <h1 className="text-2xl font-semibold tracking-tight">Change password</h1>
      <FormNotice>{notice}</FormNotice>
      <Form<Values>
        key={formKey}
        errors={errors}
        onFormSubmit={submit}
        className="flex flex-col gap-4"
      >
        <FormAlert>{alert}</FormAlert>
        <TextField
          name="currentPassword"
          label="Current password"
          type="password"
          autoComplete="current-password"
          required
          validate={validatePassword}
        />
        <TextField
          name="newPassword"
          label="New password"
          type="password"
          autoComplete="new-password"
          required
          validate={validateNewPassword}
        />
        <TextField
          name="confirmation"
          label="Confirm new password"
          type="password"
          autoComplete="new-password"
          required
          validate={(value, values) =>
            validateConfirmation(value, values.newPassword)
          }
        />
        <SubmitButton pending={pending}>Change password</SubmitButton>
      </Form>
    </section>
  );
}
