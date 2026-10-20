"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Archive, ArrowLeftRight, ClipboardCheck, Download, FolderKanban, Landmark, LayoutDashboard, ListChecks, LogOut, Menu, Package, ScrollText, Search, Settings2, UserRoundCog, UsersRound } from "lucide-react";
import { messages, type Locale } from "@/lib/i18n/messages";
import { apiRequest } from "@/lib/api/client";
import { hasAnyRoleResponse } from "@/lib/api/permissions";

const navigation = [
  ["/dashboard", "dashboard", LayoutDashboard],
  ["/projects", "projects", FolderKanban],
  ["/organizations", "organizations", Landmark],
  ["/users", "users", UsersRound],
  ["/roles", "roles", UserRoundCog],
  ["/packages", "packages", Package],
  ["/exports", "exports", Download],
  ["/migrations", "migrations", ArrowLeftRight],
  ["/operations", "operations", ListChecks],
  ["/queries", "queries", Search],
  ["/audit-logs", "audit", ScrollText],
  ["/settings", "settings", Settings2],
] as const;

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const queryClient = useQueryClient();
  const currentUser = useQuery({ queryKey: ["auth.me"], queryFn: () => apiRequest<unknown>("auth.me", "GET") });
  const canReviewProjects = currentUser.data ? hasAnyRoleResponse(currentUser.data, ["auditor", "system_admin"]) === true : false;
  const [locale, setLocale] = useState<Locale>("ar");
  const [collapsed, setCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [logoutError, setLogoutError] = useState("");
  const t = messages[locale];
  const switchLanguage = () => {
    const next = locale === "ar" ? "en" : "ar";
    setLocale(next);
    document.documentElement.lang = next;
    document.documentElement.dir = next === "ar" ? "rtl" : "ltr";
  };
  const toggleSidebar = () => {
    if (window.matchMedia("(min-width: 1024px)").matches) setCollapsed((value) => !value);
    else setMobileOpen((value) => !value);
  };
  const logout = async () => {
    setLogoutError("");
    try {
      const response = await fetch("/api/auth/logout", { method: "POST" });
      if (!response.ok) { setLogoutError("تعذر تسجيل الخروج. حاول مرة أخرى."); return; }
      queryClient.clear();
      router.replace("/login");
      router.refresh();
    } catch { setLogoutError("تعذر تسجيل الخروج. حاول مرة أخرى."); }
  };
  return (
    <div
      dir={locale === "ar" ? "rtl" : "ltr"}
      className="min-h-screen bg-[#f0eee3] text-[#1b1c15]"
    >
      {mobileOpen && <button aria-label="إغلاق القائمة" className="fixed inset-0 z-20 bg-black/40 lg:hidden" onClick={() => setMobileOpen(false)} />}
      <aside className={`fixed inset-y-0 right-0 z-30 flex w-72 flex-col border-l border-[#bcaa7c]/30 bg-[#002623] text-white shadow-xl transition-all duration-200 ${mobileOpen ? "translate-x-0" : "translate-x-full lg:translate-x-0"} ${collapsed ? "lg:w-20" : "lg:w-72"}`}>
        <div className={`flex h-16 items-center gap-3 border-b border-white/10 bg-black/20 ${collapsed ? "lg:justify-center lg:px-2" : "px-6"}`}>
          <span className="flex h-8 w-8 items-center justify-center rounded-full border border-[#f5e1af] text-[#f5e1af]"><Archive size={18} strokeWidth={1.8} aria-hidden="true" /></span>
          <div className={collapsed ? "lg:hidden" : ""}>
            <p className="font-bold text-[#f5e1af]">{t.app}</p>
            <p className="text-[11px] text-[#a9cec9]">
              إدارة المشاريع والملفات
            </p>
          </div>
        </div>
        <p className={`px-6 py-4 text-[11px] font-bold tracking-wide text-[#d8c595] ${collapsed ? "lg:hidden" : ""}`}>
          القائمة الرئيسية
        </p>
        <nav className={`min-h-0 flex-1 space-y-1 overflow-y-auto ${collapsed ? "lg:px-2" : "px-3"}`}>
          {navigation.map(([href, label, Icon]) => (
            <Link
              key={href}
              href={href}
              title={collapsed ? t[label] : undefined}
              onClick={() => setMobileOpen(false)}
              className={`flex items-center gap-3 rounded-md py-2.5 text-sm transition ${collapsed ? "lg:justify-center lg:px-2" : "px-4"} ${pathname === href ? "border-r-4 border-[#f5e1af] bg-[#33675d] font-semibold text-white" : "text-white/70 hover:bg-[#33675d]/45 hover:text-white"}`}
            >
              <Icon size={18} strokeWidth={1.8} className="shrink-0 text-[#d8c595]" aria-hidden="true" />
              <span className={collapsed ? "lg:hidden" : ""}>{t[label]}</span>
            </Link>
          ))}
          {canReviewProjects && <Link
            href="/projects/review"
            title={collapsed ? "مراجعة المشاريع" : undefined}
            onClick={() => setMobileOpen(false)}
            className={`flex items-center gap-3 rounded-md py-2.5 text-sm transition ${collapsed ? "lg:justify-center lg:px-2" : "px-4"} ${pathname === "/projects/review" ? "border-r-4 border-[#f5e1af] bg-[#33675d] font-semibold text-white" : "text-white/70 hover:bg-[#33675d]/45 hover:text-white"}`}
          >
            <ClipboardCheck size={18} strokeWidth={1.8} className="shrink-0 text-[#d8c595]" aria-hidden="true" />
            <span className={collapsed ? "lg:hidden" : ""}>مراجعة المشاريع</span>
          </Link>}
        </nav>
        <div className="border-t border-white/10 p-3 text-xs text-[#a9cec9]">
          <button onClick={logout} title="تسجيل الخروج" className={`flex w-full items-center gap-3 rounded-md px-3 py-2 text-red-100 transition hover:bg-red-900/30 ${collapsed ? "lg:justify-center" : ""}`}><LogOut size={18} strokeWidth={1.8} aria-hidden="true" /><span className={collapsed ? "lg:hidden" : ""}>تسجيل الخروج</span></button>
          {logoutError && <p role="alert" className="mt-2 px-3 text-red-200">{logoutError}</p>}
        </div>
      </aside>
      <div className={`transition-all duration-200 ${collapsed ? "lg:pr-20" : "lg:pr-72"}`}>
        <header className="sticky top-0 z-10 flex h-16 items-center justify-between border-b border-[#e5e3d8] bg-[#fff] px-5 shadow-sm lg:px-6">
          <div className="flex items-center gap-3 text-sm text-[#414847]">
            <button onClick={toggleSidebar} aria-label={collapsed ? "فتح القائمة الجانبية" : "طي القائمة الجانبية"} aria-expanded={!collapsed} className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md border border-[#e5e3d8] bg-[#f6f4e9] text-[#002623] transition hover:bg-[#eae8dd]"><Menu size={20} aria-hidden="true" /></button>
            <span>الرئيسية</span>
            <span className="text-[#c1c8c6]">/</span>
            <strong className="text-[#1b1c15]">النظام المركزي للأرشفة</strong>
          </div>
          {/* <button
            onClick={switchLanguage}
            className="rounded-md border border-[#e5e3d8] bg-[#f6f4e9] px-3 py-1.5 text-sm text-[#414847]"
          >
            {locale === "ar" ? "English" : "العربية"}
          </button> */}
        </header>
        <main className="min-h-[calc(100vh-4rem)] p-4 lg:p-6">{children}</main>
      </div>
    </div>
  );
}
