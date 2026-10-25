"use client";

import { useEffect, useState } from "react";

import { api } from "@/lib/api/client";

type Status = "loading" | "up" | "down" | "unreachable";

const labels: Record<Status, string> = {
  loading: "Checking server…",
  up: "Server is up",
  down: "Server is down",
  unreachable: "Server is unreachable",
};

export function HealthStatus() {
  const [status, setStatus] = useState<Status>("loading");

  useEffect(() => {
    api
      .GET("/api/v1/health")
      .then(({ data }) => setStatus(data?.status === "UP" ? "up" : "down"))
      .catch(() => setStatus("unreachable"));
  }, []);

  return (
    <p role="status" className="text-sm text-zinc-600 dark:text-zinc-400">
      {labels[status]}
    </p>
  );
}
