import { AppShell } from "@/components/app-shell";
import { ApiError, serverRequest } from "@/lib/api/server";
import { redirect } from "next/navigation";
import { connection } from "next/server";

export default async function DashboardLayout({ children }: { children: React.ReactNode }) {
  // Docker provides the backend URL at runtime, not during next build.
  // Never prerender an authentication decision into the production image.
  await connection();
  try {
    await serverRequest("/api/v1/auth/me");
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) redirect("/login");
    // Preserve Next.js control-flow exceptions and actual service failures.
    throw error;
  }
  return <AppShell>{children}</AppShell>;
}
