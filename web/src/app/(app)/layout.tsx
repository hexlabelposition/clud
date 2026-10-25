import { AppHeader } from "./app-header";
import { SessionGuard } from "./session";

// Screens for signed-in users. The guard renders a page only after the
// browser confirms the session, so pages are not part of the prerendered
// shell: each page here sets `instant = false` to skip instant-navigation
// validation, which would otherwise report a dropped segment.
export default function AppLayout({ children }: LayoutProps<"/">) {
  return (
    <SessionGuard>
      <AppHeader />
      <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-6 px-4 py-8">
        {children}
      </main>
    </SessionGuard>
  );
}
