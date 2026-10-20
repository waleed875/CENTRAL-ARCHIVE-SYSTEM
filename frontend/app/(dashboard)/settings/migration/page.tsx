"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, Save } from "lucide-react";
import { ApiRequestError, apiRequest } from "@/lib/api/client";
import { migrationSettingsSchema } from "@/lib/api/contracts";

function readSettings(value: unknown) {
  const data = value && typeof value === "object" && "data" in value ? value.data : value;
  return migrationSettingsSchema.parse(data);
}

export default function MigrationSettingsPage() {
  const queryClient = useQueryClient();
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const settings = useQuery({ queryKey: ["migrationSettings.get"], queryFn: async () => readSettings(await apiRequest<unknown>("migrationSettings.get", "GET")) });
  const update = useMutation({
    mutationFn: (body: { migrationEnabled: boolean }) => apiRequest<unknown>("migrationSettings.update", "PUT", {}, body),
    onSuccess: async () => {
      setEnabled(null);
      await queryClient.invalidateQueries({ queryKey: ["migrationSettings.get"] });
    },
  });
  const save = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (enabled === null) return;
    update.mutate({ migrationEnabled: enabled });
  };
  const forbidden = settings.error instanceof ApiRequestError && settings.error.status === 403;
  return <section className="mx-auto max-w-3xl space-y-5">
    <header className="rounded-xl bg-white p-6 shadow-sm"><Link href="/settings" className="inline-flex items-center gap-2 text-sm text-[#33675d]"><ArrowRight size={16} aria-hidden="true" />الإعدادات</Link><h1 className="mt-3 text-2xl font-bold">إعدادات الترحيل</h1></header>
    {settings.isLoading && <p className="rounded-xl bg-white p-6 text-sm">جارٍ التحميل…</p>}
    {settings.isError && <p role="alert" className="rounded-xl bg-[#ffdad6] p-5 text-sm text-[#93000a]">{forbidden ? "لا تملك صلاحية عرض إعدادات الترحيل." : "تعذر تحميل إعدادات الترحيل."}</p>}
    {settings.data && <form onSubmit={save} className="space-y-5 rounded-xl bg-white p-6 shadow-sm">
      <label className="flex items-center justify-between gap-4 rounded-lg border border-[#e5e3d8] p-4 text-sm font-semibold"><span>السماح بتشغيل الترحيل</span><input type="checkbox" className="h-5 w-5 accent-[#002623]" checked={enabled ?? settings.data.migrationEnabled} onChange={(event) => setEnabled(event.target.checked)} /></label>
      <div className="flex flex-wrap gap-4 text-xs text-[#717977]"><span>{settings.data.environmentDefault ? "تُستخدم إعدادات البيئة" : "إعدادات محفوظة"}</span>{settings.data.updatedAt && <span>آخر تحديث: {new Intl.DateTimeFormat("ar-SA", { dateStyle: "medium", timeStyle: "short" }).format(new Date(settings.data.updatedAt))}</span>}{settings.data.updatedBy && <span>بواسطة: {settings.data.updatedBy}</span>}</div>
      {update.isError && <p role="alert" className="rounded-lg bg-[#ffdad6] p-3 text-sm text-[#93000a]">{update.error instanceof ApiRequestError && update.error.status === 403 ? "لا تملك صلاحية تعديل إعدادات الترحيل." : update.error.message}</p>}
      {update.isSuccess && <p role="status" className="text-sm text-[#184f46]">تم حفظ الإعدادات.</p>}
      <button type="submit" disabled={update.isPending || enabled === null} className="inline-flex items-center gap-2 rounded-lg bg-[#002623] px-5 py-2.5 text-sm font-semibold text-white disabled:opacity-50"><Save size={16} aria-hidden="true" />{update.isPending ? "جارٍ الحفظ…" : "حفظ التغييرات"}</button>
    </form>}
  </section>;
}
