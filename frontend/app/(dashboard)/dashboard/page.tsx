"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { apiRequest } from "@/lib/api/client";
import { DataView } from "@/components/data-view";
import { DashboardStatsCards } from "@/components/dashboard-stats-cards";

export default function DashboardPage() {
  const stats = useQuery({
    queryKey: ["dashboard.stats"],
    queryFn: () => apiRequest<unknown>("dashboard.stats", "GET"),
  });
  const activity = useQuery({
    queryKey: ["dashboard.activity"],
    queryFn: () =>
      apiRequest<unknown>("dashboard.activity", "GET", { limit: 20 }),
  });
  return (
    <div className="space-y-6">
      <header className="flex flex-col justify-between gap-5 rounded-xl bg-white p-6 shadow-sm lg:flex-row lg:items-center">
        <div>
          <div className="flex items-center gap-3">
            <span className="h-7 w-2.5 rounded-full bg-[#000c0b]" />
            <h1 className="text-2xl font-bold">لوحة التحكم</h1>
          </div>
          <p className="mt-2 max-w-2xl text-sm text-[#414847]">
            المشاريع والملفات والحزم والنشاط الأخير.
          </p>
        </div>
        <div className="flex items-center gap-3 rounded-lg bg-[#002623] px-5 py-3 text-white">
          <span className="h-2.5 w-2.5 rounded-full bg-[#b7ede0]" />
          <div>
            <p className="text-xs text-[#d8c595]">حالة البيانات</p>
            <p className="text-sm font-bold text-[#f5e1af]">{stats.isLoading ? "جارٍ التحميل" : stats.isError ? "تعذر التحديث" : "محدّثة"}</p>
          </div>
        </div>
      </header>
      <section className="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-[#eae8dd] p-4">
        <p className="text-sm font-semibold">روابط سريعة</p>
        <div className="flex flex-wrap gap-2">
          <Link
            href="/projects"
            className="rounded-md bg-[#000c0b] px-4 py-2 text-sm font-medium text-white"
          >
            إدارة المشاريع
          </Link>
          <Link
            href="/operations/packages.receive"
            className="rounded-md bg-white px-4 py-2 text-sm font-medium text-[#002623] shadow-sm"
          >
            استقبال حزمة
          </Link>
          <Link
            href="/audit-logs"
            className="rounded-md bg-white px-4 py-2 text-sm font-medium text-[#1b1c15] shadow-sm"
          >
            سجل التدقيق
          </Link>
        </div>
      </section>
      {stats.isLoading && (
        <section
          aria-label="جارٍ تحميل مؤشرات الأداء"
          className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4"
        >
          {Array.from({ length: 8 }).map((_, index) => (
            <div
              key={index}
              className="h-36 animate-pulse rounded-lg bg-white"
            />
          ))}
        </section>
      )}
      {stats.isError && (
        <p
          role="alert"
          className="rounded-lg border border-red-200 bg-[#ffdad6] p-4 text-sm text-[#93000a]"
        >
          تعذر تحميل مؤشرات لوحة التحكم.
        </p>
      )}
      {stats.data !== undefined && <DashboardStatsCards data={stats.data} />}
      <section className="overflow-hidden rounded-xl bg-white shadow-sm">
        <div className="flex items-center justify-between border-b-2 border-[#eae8dd] p-6 pb-4">
          <div>
            <h2 className="font-bold">النشاط الأخير</h2>
          </div>
        </div>
        {activity.isLoading && (
          <p className="p-6 text-sm text-[#717977]">جارٍ التحميل…</p>
        )}
        {activity.isError && (
          <p role="alert" className="p-6 text-sm text-[#93000a]">
            تعذر تحميل النشاط الأخير.
          </p>
        )}
        {activity.data !== undefined && <DataView data={activity.data} />}
      </section>
    </div>
  );
}
