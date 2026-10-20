"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import { apiRequest } from "@/lib/api/client";
import { canDeleteProjectResponse } from "@/lib/api/permissions";

export function ProjectDeleteAction({ id, status }: { id: string; status: unknown }) {
  const [confirmed, setConfirmed] = useState(false);
  const router = useRouter();
  const queryClient = useQueryClient();
  const user = useQuery({ queryKey: ["auth.me"], queryFn: () => apiRequest<unknown>("auth.me", "GET") });
  const mutation = useMutation({
    mutationFn: () => apiRequest<{ meta?: { message?: string } }>("projects.delete", "DELETE", {}, undefined, { id }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["projects.list"] });
      await queryClient.invalidateQueries({ queryKey: ["projects.search"] });
      queryClient.removeQueries({ queryKey: ["projects.get", id] });
      router.replace("/projects");
    },
  });

  if (status !== "DRAFT" || !user.data || canDeleteProjectResponse(user.data) !== true) return null;

  if (mutation.isSuccess) return <section role="status" className="rounded-xl bg-[#b4ebdd] p-5 text-sm font-semibold text-[#184f46]">{mutation.data?.meta?.message ?? "تم حذف المشروع."} <Link href="/projects" className="underline">العودة إلى المشاريع</Link></section>;

  return <section className="rounded-xl border border-[#f3b9b2] bg-white p-5 shadow-sm">
    <h2 className="font-bold text-[#93000a]">حذف المشروع</h2>
    <p className="mt-2 text-sm text-[#414847]">الحذف نهائي ومتاح للمشروع المسودة فقط. إذا كان عليه ملفات أو أعضاء، سيرفض الخادم الحذف ويعرض السبب.</p>
    <label className="mt-4 flex items-center gap-2 text-sm text-[#93000a]">
      <input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} />
      أؤكد أنني أريد حذف هذا المشروع نهائياً
    </label>
    {mutation.isError && <p role="alert" className="mt-3 rounded-lg bg-[#ffdad6] p-3 text-sm text-[#93000a]">{mutation.error.message}</p>}
    <button type="button" disabled={!confirmed || mutation.isPending} onClick={() => mutation.mutate()} className="mt-4 inline-flex items-center gap-2 rounded-lg bg-[#93000a] px-4 py-2 text-sm font-semibold text-white disabled:opacity-50">
      <Trash2 size={16} aria-hidden="true" />{mutation.isPending ? "جارٍ الحذف…" : "حذف المشروع"}
    </button>
  </section>;
}
