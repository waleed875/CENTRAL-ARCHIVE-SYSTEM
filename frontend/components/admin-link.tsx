"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { apiRequest } from "@/lib/api/client";
import { isSystemAdminResponse } from "@/lib/api/permissions";

export function useSystemAdminAccess(enabled = true) {
  const currentUser = useQuery({ queryKey: ["auth.me"], queryFn: () => apiRequest<unknown>("auth.me", "GET"), enabled });
  return currentUser.data ? isSystemAdminResponse(currentUser.data) : null;
}

export function AdminLink({ href, className, children }: { href: string; className?: string; children: React.ReactNode }) {
  const adminAccess = useSystemAdminAccess();
  if (adminAccess === false) return null;
  return <Link href={href} className={className}>{children}</Link>;
}
