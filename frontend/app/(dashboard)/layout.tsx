import { AppShell } from "@/components/app-shell";
import { serverRequest } from "@/lib/api/server";
import { redirect } from "next/navigation";

export default async function DashboardLayout({ children }: { children: React.ReactNode }) {
  let authenticated = false;
  try {
    await serverRequest("/api/v1/auth/me");
    authenticated = true;
  } catch {
    authenticated = false;
  }
  if (!authenticated) redirect("/login");
  return <AppShell>{children}</AppShell>;
}
