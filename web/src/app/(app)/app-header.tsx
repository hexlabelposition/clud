"use client";

import { Button } from "@base-ui/react/button";
import Link from "next/link";
import { useState } from "react";

import { failureMessage } from "@/lib/api/problems";
import { reloadTo, signOut, thrownMessage } from "@/lib/auth";

import { useCurrentUser } from "./session";

export function AppHeader() {
  const user = useCurrentUser();
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  async function handleSignOut() {
    setPending(true);
    setFailure(null);
    try {
      const { response } = await signOut();
      if (response.ok) {
        reloadTo("/login");
        return;
      }
      setFailure(failureMessage(response));
    } catch (error) {
      setFailure(thrownMessage(error));
    } finally {
      setPending(false);
    }
  }

  return (
    <header className="border-b border-zinc-200 dark:border-zinc-800">
      <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
        <Link href="/" className="text-lg font-semibold tracking-tight">
          Clud
        </Link>
        <nav className="flex gap-4 text-sm">
          <Link href="/settings/password" className="hover:underline">
            Change password
          </Link>
        </nav>
        <div className="ml-auto flex items-center gap-3 text-sm">
          {failure && (
            <span role="alert" className="text-red-700 dark:text-red-400">
              {failure}
            </span>
          )}
          <span className="text-zinc-600 dark:text-zinc-400">
            {user.username}
            {user.role === "ADMIN" && " (administrator)"}
          </span>
          <Button
            onClick={handleSignOut}
            disabled={pending}
            focusableWhenDisabled
            className="rounded-md border border-zinc-300 px-3 py-1 hover:bg-zinc-100 focus-visible:ring-2 focus-visible:ring-zinc-500/50 focus-visible:outline-none data-disabled:opacity-60 dark:border-zinc-700 dark:hover:bg-zinc-800"
          >
            Sign out
          </Button>
        </div>
      </div>
    </header>
  );
}
