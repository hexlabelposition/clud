import type { Metadata } from "next";

import { ChangePasswordForm } from "./change-password-form";

export const metadata: Metadata = { title: "Change password" };

export const instant = false;

export default function ChangePasswordPage() {
  return <ChangePasswordForm />;
}
