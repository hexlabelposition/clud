"use client";

import { Button } from "@base-ui/react/button";
import { Field } from "@base-ui/react/field";
import { useSyncExternalStore } from "react";
import type { ComponentProps, ReactNode } from "react";

type TextFieldProps = {
  name: string;
  label: string;
  description?: ReactNode;
  validate?: ComponentProps<typeof Field.Root>["validate"];
} & Pick<
  ComponentProps<"input">,
  "type" | "autoComplete" | "required" | "maxLength" | "defaultValue"
>;

export function TextField({
  name,
  label,
  description,
  validate,
  type = "text",
  ...control
}: TextFieldProps) {
  return (
    <Field.Root name={name} validate={validate} className="flex flex-col gap-1">
      <Field.Label className="text-sm font-medium">{label}</Field.Label>
      <Field.Control
        type={type}
        // Usernames, passwords and tokens are entered exactly as typed.
        autoCapitalize="none"
        autoCorrect="off"
        spellCheck={false}
        className="h-9 rounded-md border border-zinc-300 bg-transparent px-3 text-sm outline-none focus-visible:border-zinc-500 focus-visible:ring-2 focus-visible:ring-zinc-500/30 data-invalid:border-red-600 dark:border-zinc-700 dark:data-invalid:border-red-400"
        {...control}
      />
      {description && (
        <Field.Description className="text-xs text-zinc-600 dark:text-zinc-400">
          {description}
        </Field.Description>
      )}
      <Field.Error className="text-xs text-red-700 dark:text-red-400" />
    </Field.Root>
  );
}

const subscribeToNothing = () => () => {};

/**
 * Whether React has hydrated the page: false in the server HTML and during
 * hydration, true afterwards.
 */
function useHydrated() {
  return useSyncExternalStore(
    subscribeToNothing,
    () => true,
    () => false,
  );
}

/**
 * Stays disabled until hydration. Before that the browser would submit the
 * form natively as a GET request, putting passwords into the URL, history and
 * access logs; a form whose only submit button is disabled cannot be
 * submitted, not even with Enter.
 */
export function SubmitButton({
  pending,
  children,
}: {
  pending: boolean;
  children: ReactNode;
}) {
  const hydrated = useHydrated();
  return (
    <Button
      type="submit"
      disabled={pending || !hydrated}
      // Only the native disabled attribute blocks submission before
      // hydration; afterwards keep focus on the button while pending.
      focusableWhenDisabled={hydrated}
      className="h-9 rounded-md bg-zinc-900 px-4 text-sm font-medium text-white hover:bg-zinc-700 focus-visible:ring-2 focus-visible:ring-zinc-500/50 focus-visible:outline-none data-disabled:opacity-60 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
    >
      {children}
    </Button>
  );
}

/** A form-level error, announced to screen readers when it appears. */
export function FormAlert({ children }: { children: ReactNode }) {
  if (!children) {
    return null;
  }
  return (
    <p
      role="alert"
      className="rounded-md border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-800 dark:border-red-900 dark:bg-red-950 dark:text-red-200"
    >
      {children}
    </p>
  );
}

/** A form-level success message. */
export function FormNotice({ children }: { children: ReactNode }) {
  if (!children) {
    return null;
  }
  return (
    <p
      role="status"
      className="rounded-md border border-emerald-300 bg-emerald-50 px-3 py-2 text-sm text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-200"
    >
      {children}
    </p>
  );
}
