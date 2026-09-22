"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { messages, type Locale } from "@/lib/i18n/messages";

const navigation = [
  ["/dashboard", "dashboard", "▦"],
  ["/projects", "projects", "▣"],
  ["/organizations", "organizations", "⌂"],
  ["/users", "users", "♙"],
  ["/roles", "roles", "◈"],
  ["/packages", "packages", "□"],
  ["/exports", "exports", "⇩"],
  ["/migrations", "migrations", "↔"],
  ["/audit-logs", "audit", "☷"],
  ["/settings", "settings", "⚙"],
] as const;

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [locale, setLocale] = useState<Locale>("ar");
  const [collapsed, setCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
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
    await fetch("/api/auth/logout", { method: "POST" });
    router.replace("/login");
    router.refresh();
  };
  return (
    <div
      dir={locale === "ar" ? "rtl" : "ltr"}
      className="min-h-screen bg-[#f0eee3] text-[#1b1c15]"
    >
      {mobileOpen && <button aria-label="إغلاق القائمة" className="fixed inset-0 z-20 bg-black/40 lg:hidden" onClick={() => setMobileOpen(false)} />}
      <aside className={`fixed inset-y-0 right-0 z-30 flex w-72 flex-col border-l border-[#bcaa7c]/30 bg-[#002623] text-white shadow-xl transition-all duration-200 ${mobileOpen ? "translate-x-0" : "translate-x-full lg:translate-x-0"} ${collapsed ? "lg:w-20" : "lg:w-72"}`}>
        <div className={`flex h-16 items-center gap-3 border-b border-white/10 bg-black/20 ${collapsed ? "lg:justify-center lg:px-2" : "px-6"}`}>
          <span className="flex h-8 w-8 items-center justify-center rounded-full border border-[#f5e1af] text-[#f5e1af]">
            ▣
          </span>
          <div className={collapsed ? "lg:hidden" : ""}>
            <p className="font-bold text-[#f5e1af]">{t.app}</p>
            <p className="text-[11px] text-[#a9cec9]">
              المنظومة الوطنية الموحدة
            </p>
          </div>
        </div>
        <p className={`px-6 py-4 text-[11px] font-bold tracking-wide text-[#d8c595] ${collapsed ? "lg:hidden" : ""}`}>
          سجل الأعمال والوثائق
        </p>
        <nav className={`flex-1 space-y-1 ${collapsed ? "lg:px-2" : "px-3"}`}>
          {navigation.map(([href, label, icon]) => (
            <Link
              key={href}
              href={href}
              title={collapsed ? t[label] : undefined}
              onClick={() => setMobileOpen(false)}
              className={`flex items-center gap-3 rounded-md py-2.5 text-sm transition ${collapsed ? "lg:justify-center lg:px-2" : "px-4"} ${pathname === href ? "border-r-4 border-[#f5e1af] bg-[#33675d] font-semibold text-white" : "text-white/70 hover:bg-[#33675d]/45 hover:text-white"}`}
            >
              <span className="text-[#d8c595]">{icon}</span>
              <span className={collapsed ? "lg:hidden" : ""}>{t[label]}</span>
            </Link>
          ))}
        </nav>
        <div className="border-t border-white/10 p-3 text-xs text-[#a9cec9]">
          <button onClick={logout} title="تسجيل الخروج" className={`flex w-full items-center gap-3 rounded-md px-3 py-2 text-red-100 transition hover:bg-red-900/30 ${collapsed ? "lg:justify-center" : ""}`}><span aria-hidden="true">⇥</span><span className={collapsed ? "lg:hidden" : ""}>تسجيل الخروج</span></button>
        </div>
      </aside>
      <div className={`transition-all duration-200 ${collapsed ? "lg:pr-20" : "lg:pr-72"}`}>
        <header className="sticky top-0 z-10 flex h-16 items-center justify-between border-b border-[#e5e3d8] bg-[#fff] px-5 shadow-sm lg:px-6">
          <div className="flex items-center gap-3 text-sm text-[#414847]">
            <button onClick={toggleSidebar} aria-label={collapsed ? "فتح القائمة الجانبية" : "طي القائمة الجانبية"} aria-expanded={!collapsed} className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md border border-[#e5e3d8] bg-[#f6f4e9] text-[#002623] transition hover:bg-[#eae8dd]"><svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 6h16M4 12h16M4 18h16" /></svg></button>
            <span>الرئيسية</span>
            <span className="text-[#c1c8c6]">/</span>
            <strong className="text-[#1b1c15]">النظام المركزي للأرشفة</strong>
            <span className="hidden rounded-md border border-[#bcaa7c]/50 bg-[#f6f4e9] px-2 py-1 text-xs text-[#4b3e1a] sm:block">
              نظام مشفر
            </span>
          </div>
          <button
            onClick={switchLanguage}
            className="rounded-md border border-[#e5e3d8] bg-[#f6f4e9] px-3 py-1.5 text-sm text-[#414847]"
          >
            {locale === "ar" ? "English" : "العربية"}
          </button>
        </header>
        <main className="min-h-[calc(100vh-4rem)] p-4 lg:p-6">{children}</main>
      </div>
    </div>
  );
}
