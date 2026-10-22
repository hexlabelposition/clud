import { HealthStatus } from "./health-status";

export default function Home() {
  return (
    <main className="flex flex-1 flex-col items-center justify-center gap-2">
      <h1 className="text-3xl font-semibold tracking-tight">Clud</h1>
      <HealthStatus />
    </main>
  );
}
