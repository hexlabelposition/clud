"use client";

import { HealthStatus } from "@/components/health-status";

import { useCurrentUser } from "./session";

export function Welcome() {
  const user = useCurrentUser();
  return (
    <>
      <h1 className="text-2xl font-semibold tracking-tight">
        Welcome, {user.username}
      </h1>
      <p className="text-sm text-zinc-600 dark:text-zinc-400">
        Your files will appear here.
      </p>
      <HealthStatus />
    </>
  );
}
