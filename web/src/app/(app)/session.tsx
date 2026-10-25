"use client";

import { usePathname } from "next/navigation";
import { createContext, use, useEffect, useState } from "react";
import type { ReactNode } from "react";

import { api } from "@/lib/api/client";
import { failureMessage, unreachableMessage } from "@/lib/api/problems";
import { anonymousDestination, reloadTo, type CurrentUser } from "@/lib/auth";

const SessionContext = createContext<CurrentUser | null>(null);

/** The signed-in user; available below `SessionGuard`. */
export function useCurrentUser(): CurrentUser {
  const user = use(SessionContext);
  if (!user) {
    throw new Error("useCurrentUser must be used within a SessionGuard");
  }
  return user;
}

/**
 * Renders its children only for a signed-in user and sends everyone else to
 * the first-run or login screen. This is navigation, not protection: the
 * Server authorizes every API request itself.
 *
 * The session is checked again on each navigation, so an expired or revoked
 * session leads to the login screen on the next click rather than at the
 * first failing request.
 */
export function SessionGuard({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    api
      .GET("/api/v1/me")
      .then(async ({ data, response }) => {
        if (!current) {
          return;
        }
        if (data) {
          setUser(data);
          setFailure(null);
        } else if (response.status === 401) {
          const next = pathname + window.location.search;
          reloadTo(await anonymousDestination(next));
        } else {
          setFailure(failureMessage(response));
        }
      })
      .catch(() => {
        if (current) {
          setFailure(unreachableMessage);
        }
      });
    return () => {
      current = false;
    };
  }, [pathname]);

  if (!user) {
    return (
      <main className="flex flex-1 items-center justify-center px-4">
        {failure ? (
          <p role="alert" className="text-sm text-red-700 dark:text-red-400">
            {failure}
          </p>
        ) : (
          <p role="status" className="text-sm text-zinc-600 dark:text-zinc-400">
            Loading…
          </p>
        )}
      </main>
    );
  }
  return <SessionContext value={user}>{children}</SessionContext>;
}
