import { HealthStatus } from "@/components/health-status";

// Screens for visitors without a session: first-run setup, login, registration.
export default function AuthLayout({ children }: LayoutProps<"/">) {
  return (
    <main className="flex flex-1 flex-col items-center justify-center gap-6 px-4 py-12">
      <p className="text-2xl font-semibold tracking-tight">Clud</p>
      <div className="w-full max-w-sm rounded-xl border border-zinc-200 p-6 shadow-sm dark:border-zinc-800">
        {children}
      </div>
      <HealthStatus />
    </main>
  );
}
