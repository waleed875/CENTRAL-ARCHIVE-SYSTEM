"use client";

import { useCallback, useMemo, useState, type FormEvent } from "react";
import {
  ArrowLeft, ArrowRight, CheckCircle2, Download, FileArchive,
  FolderKanban, LockKeyhole, RefreshCw, Search, ShieldCheck, FileSpreadsheet, FileText, Files
} from "lucide-react";
import { api, jsonBody, qs } from "@/lib/api";
import { useRemote } from "@/components/hooks";
import { Alert, Empty, Loading, PageHeader, Panel, Status, Pagination } from "@/components/ui";
import type { ExportPackage, PageData, Project, UserInfo } from "@/lib/types";
import { dateLabel, sizeLabel, statusName } from "@/lib/types";

type ExportPreview = {
  selectedProjects: number;
  projectFamilies: number;
  versions: number;
  files: number;
  estimatedBytes: number;
  format: "PKG" | "ZIP" | "XLSX" | "PDF";
  versionScope: "ALL";
  integrityProtected: boolean;
  families: Array<{
    projectUid: string;
    name: string;
    versions: number;
    files: number;
    estimatedBytes: number;
  }>;
};
type Step = 1 | 2 | 3 | 4;

type ExportFormat = "PKG" | "ZIP" | "XLSX" | "PDF";
const FORMAT_CHOICES: Array<{ value: ExportFormat; name: string; description: string; icon: typeof FileArchive }> = [
  { value: "PKG", name: "حزمة أرشيف PKG", description: "بيانات وملفات جميع الإصدارات؛ قابلة للاستيراد", icon: FileArchive },
  { value: "ZIP", name: "مجلد ملفات ZIP", description: "الملفات الأصلية بمجلد لكل إصدار وفهرس CSV", icon: Files },
  { value: "XLSX", name: "جدول Excel", description: "جرد المشاريع والإصدارات والملفات، دون محتويات الملفات", icon: FileSpreadsheet },
  { value: "PDF", name: "تقرير PDF", description: "تقرير قابل للطباعة عن بيانات المشاريع والإصدارات", icon: FileText }
];

/** Stream package bytes with the browser download manager, not JS Blob memory. */
function savePackage(id: string, filename: string) {
  const link = document.createElement("a");
  link.href = "/api/backend/api/v1/export/packages/" + encodeURIComponent(id) + "/download";
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
}

/** Selection is stored independently of pagination and search results. */
export function VersionedExports() {
  const [step, setStep] = useState<Step>(1);
  const [format, setFormat] = useState<ExportFormat>("PKG");
  const [encrypt, setEncrypt] = useState(false);
  const [passphrase, setPassphrase] = useState("");
  const [confirmPassphrase, setConfirmPassphrase] = useState("");
  const [selected, setSelected] = useState<Record<string, Project>>({});
  const [query, setQuery] = useState("");
  const [term, setTerm] = useState("");
  const [page, setPage] = useState(1);
  const [preview, setPreview] = useState<ExportPreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const current = useRemote<UserInfo>("/api/v1/auth/me");
  const canExport = !!current.data &&
    (current.data.roles.includes("system_admin") ||
     current.data.roles.includes("export_officer"));
  const projects = useRemote<PageData<Project>>("/api/v1/projects/search" +
    qs({ isLatestVersion: "true", page, size: 15, searchTerm: term }));
  const exports = useRemote<ExportPackage[]>("/api/v1/export/packages");

  const selectedIds = useMemo(() => Object.keys(selected), [selected]);
  const selectedCount = selectedIds.length;
  const includesFiles = format === "PKG" || format === "ZIP";
  const options = {
    format, versionScope: "ALL",
    contents: { data: true, files: includesFiles },
    protection: { enabled: true, integrity: "SHA256",
      encryptionMode: encrypt && format === "PKG" ? "PASSPHRASE" : "NONE",
      ...(encrypt && format === "PKG" ? { passphrase } : {}) }
  };
  function validateFormat() {
    if (format === "PKG" && encrypt && (passphrase.length < 12 || passphrase.length > 256)) {
      throw new Error("كلمة مرور الحزمة المشفرة يجب أن تتكون من 12 إلى 256 حرفًا");
    }
    if (format === "PKG" && encrypt && passphrase !== confirmPassphrase) {
      throw new Error("كلمتا مرور التشفير غير متطابقتين");
    }
  }
  function changeFormat(next: ExportFormat) {
    setFormat(next);
    setEncrypt(false);
    setPassphrase("");
    setConfirmPassphrase("");
    setPreview(null);
    setError("");
  }
  const allOnPage = projects.data?.data?.length
    ? projects.data.data.every(project => !!selected[project.id])
    : false;

  function toggle(id: string, checked: boolean, project?: Project) {
    setSelected(previous => {
      const next = { ...previous };
      if (checked && project) next[id] = project;
      else delete next[id];
      return next;
    });
    setPreview(null);
    setError("");
  }

  function togglePage(checked: boolean) {
    setSelected(previous => {
      const next = { ...previous };
      for (const project of projects.data?.data ?? []) {
        if (checked) next[project.id] = project;
        else delete next[project.id];
      }
      return next;
    });
    setPreview(null);
    setError("");
  }

  async function requestPreview() {
    setBusy(true);
    setError("");
    try {
      if (!selectedCount) throw new Error("اختر مشروعًا واحدًا على الأقل");
      validateFormat();
      const result = await api<ExportPreview>("/api/v1/export/packages/preview", {
        method: "POST",
        ...jsonBody({ projectIds: selectedIds, ...options })
      });
      setPreview(result);
      setStep(3);
    } catch (e) {
      setError(e instanceof Error ? e.message : "تعذر معاينة الحزمة");
    } finally {
      setBusy(false);
    }
  }

  async function createExport() {
    setBusy(true);
    setError("");
    setSuccess("");
    try {
      if (!preview) throw new Error("يجب معاينة الحزمة قبل إنشائها");
      validateFormat();
      const pkg = await api<ExportPackage>("/api/v1/export/packages", {
        method: "POST",
        ...jsonBody({ projectIds: selectedIds, ...options })
      });
      setStep(4);
      setPassphrase("");
      setConfirmPassphrase("");
      exports.reload();
      setSuccess("تم إنشاء الحزمة وحفظها في سجل التصدير. يمكنك تنزيلها الآن.");
      if (pkg.id && pkg.status === "EXPORTED") {
        savePackage(pkg.id, pkg.packageName);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "تعذر إنشاء حزمة التصدير");
    } finally {
      setBusy(false);
    }
  }

  function restart() {
    setStep(1); setSelected({}); setPreview(null); setError(""); setSuccess("");
    setPassphrase(""); setConfirmPassphrase(""); setEncrypt(false); setFormat("PKG");
  }

  return <>
    <PageHeader
      eyebrow="تبادل السجلات • جميع الإصدارات"
      title="تصدير المشاريع"
      description="اختر المشاريع والصيغة المناسبة: أرشيف PKG، ملفات ZIP، جداول Excel أو تقرير PDF، مع حماية الصلاحيات."
      action={canExport && <button className="btn btn-primary" onClick={restart}>
        <FileArchive size={17}/> تصدير جديد
      </button>}
    />
    <div className="export-wizard-steps" aria-label="مراحل تصدير المشاريع">
      {["اختيار المشاريع", "الإعدادات", "معاينة الحزمة", "التنزيل"].map((label, index) =>
        <div key={label} className={"export-wizard-step" +
          (step === index + 1 ? " is-current" : step > index + 1 ? " is-complete" : "")}>
          <span>{step > index + 1 ? <CheckCircle2 size={17}/> : index + 1}</span>
          <strong>{label}</strong>
        </div>
      )}
    </div>
    {error && <Alert message={error} onClose={() => setError("")}/>}
    {success && <Alert message={success} kind="success" onClose={() => setSuccess("")}/>}

    {step === 1 && <Panel title="اختر المشاريع" subtitle="اختر أي عدد من نتائج البحث؛ سيُضاف جميع إصدارات كل مشروع تلقائيًا.">
      <form className="toolbar" onSubmit={(e: FormEvent) => {
        e.preventDefault(); setPage(1); setTerm(query);
      }}>
        <input value={query} onChange={e => setQuery(e.target.value)}
          placeholder="ابحث عن مشروع بالاسم أو الرقم" aria-label="البحث في المشاريع"/>
        <button className="btn btn-outline" type="submit"><Search size={16}/> بحث</button>
        <strong className="export-selected-count">{selectedCount} مشروع محدد</strong>
      </form>
      {projects.error && <Alert message={projects.error}/>}
      {projects.loading ? <Loading/> : !projects.data?.data?.length ?
        <Empty title="لم يُعثر على مشاريع" description="غيّر عبارة البحث أو تحقق من صلاحيات الحساب"/> :
        <div className="table-scroller"><table className="data-table">
          <thead><tr><th><input aria-label="اختيار كل نتائج الصفحة" type="checkbox"
            checked={allOnPage} onChange={e => togglePage(e.target.checked)}/></th>
            <th>المشروع</th><th>السنة</th><th>الحالة</th><th>الإصدارات</th></tr></thead>
          <tbody>{projects.data.data.map(project =>
            <tr key={project.id}>
              <td><input type="checkbox" aria-label={"اختيار " + project.name}
                checked={!!selected[project.id]}
                onChange={e => toggle(project.id, e.target.checked, project)}/></td>
              <td><strong className="table-link">{project.name}</strong>
                <span className="table-sub">{project.projectNumber}</span></td>
              <td>{project.year}</td><td><Status value={project.status}/></td>
              <td><span className="chip">آخر إصدار: {project.versionNo}</span></td>
            </tr>
          )}</tbody>
        </table></div>}
      <Pagination page={page} totalPages={projects.data?.totalPages ?? 0} onChange={setPage}/>
      {selectedCount > 0 && <div className="export-selection">
        <strong>المشاريع المختارة</strong>
        <div className="chips">{Object.values(selected).map(project =>
          <button className="chip" key={project.id} type="button"
            title="إلغاء اختيار المشروع"
            onClick={() => toggle(project.id, false)}>{project.name} ×</button>
        )}</div>
      </div>}
      <div className="form-actions export-actions">
        <button className="btn btn-gold" disabled={!canExport || !selectedCount}
          onClick={() => setStep(2)}>متابعة إلى الإعدادات <ArrowLeft size={16}/></button>
      </div>
    </Panel>}

    {step === 2 && <Panel title="اختيار نوع التصدير"
      subtitle="اختر إحدى الصيغ التالية. يشمل التصدير جميع إصدارات المشاريع المحددة.">
      <div className="export-format-grid" role="radiogroup" aria-label="نوع التصدير">
        {FORMAT_CHOICES.map(choice => {
          const Icon = choice.icon;
          return <label key={choice.value} className={"export-format-card" +
            (format === choice.value ? " selected" : "")}>
            <input type="radio" name="export-format" value={choice.value}
              checked={format === choice.value} onChange={() => changeFormat(choice.value)}/>
            <Icon size={25}/>
            <span><strong>{choice.name}</strong><small>{choice.description}</small></span>
          </label>;
        })}
      </div>
      <div className="export-options">
        <div><FolderKanban size={22}/><div><strong>المحتويات: {includesFiles ? "البيانات والملفات الأصلية" : "البيانات والفهرس فقط"}</strong>
          <small>{includesFiles ? "ملفات كل إصدار" : "لا يتم تنزيل الملفات الأصلية بهذه الصيغة"}</small></div></div>
        <div><RefreshCw size={22}/><div><strong>جميع الإصدارات التاريخية</strong>
          <small>أحدث إصدار فقط: لا</small></div></div>
        <div><ShieldCheck size={22}/><div><strong>صلاحيات المستخدم وفحص SHA-256</strong>
          <small>مفروضان على جميع الصيغ دون إمكانية تعطيلهما</small></div></div>
      </div>
      {format === "PKG" && <div className="export-encryption">
        <label className="checkbox-row">
          <input type="checkbox" checked={encrypt} onChange={e => {
            setEncrypt(e.target.checked); setPassphrase(""); setConfirmPassphrase(""); setPreview(null);
          }}/>
          <span><strong>تشفير حزمة الأرشيف بكلمة مرور (AES-256-GCM)</strong>
            <small className="table-sub">سيُنشأ ملف NXPkg قابل للاستيراد مع إدخال كلمة المرور عند الاستلام.</small></span>
        </label>
        {encrypt && <div className="form-grid">
          <label className="field"><span>كلمة مرور التشفير</span>
            <input type="password" value={passphrase} autoComplete="new-password" minLength={12}
              maxLength={256} onChange={e => {setPassphrase(e.target.value);setPreview(null)}}/>
          </label>
          <label className="field"><span>تأكيد كلمة المرور</span>
            <input type="password" value={confirmPassphrase} autoComplete="new-password"
              onChange={e => {setConfirmPassphrase(e.target.value);setPreview(null)}}/>
          </label>
        </div>}
      </div>}
      <div className="review-note">يُتحقق من مستوى سرية جميع المشاريع وملفاتها قبل إنشاء التصدير.
        حزمة PKG غير المشفرة وملفات ZIP/Excel/PDF قابلة للقراءة عند استلامها؛ استخدم قنوات نقل آمنة.
        توقيع الحزمة المشفرة يتحقق من سلامتها، ولا يمثّل هوية جهة مصدّرة موثّقة.</div>
      <div className="form-actions export-actions">
        <button className="btn btn-quiet" onClick={() => setStep(1)}>
          <ArrowRight size={16}/> السابق</button>
        <button className="btn btn-gold" disabled={busy || !canExport}
          onClick={requestPreview}>{busy ? "جارٍ فحص المشاريع..." : "معاينة الحزمة"}
          <ArrowLeft size={16}/></button>
      </div>
    </Panel>}

    {step === 3 && preview && <Panel title="معاينة الحزمة قبل التنفيذ"
      subtitle="البيانات والحجم تقريبيان؛ سيعيد الخادم التحقق أثناء إنشاء الحزمة.">
      <div className="export-preview-stats">
        {[
          ["المشاريع", preview.projectFamilies],
          ["الإصدارات", preview.versions],
          ["الملفات", preview.files],
          ["الحجم المتوقع", sizeLabel(preview.estimatedBytes)]
        ].map(([label, value]) =>
          <div key={String(label)}><span>{label}</span><strong>{value}</strong></div>)}
      </div>
      <div className="table-scroller"><table className="data-table">
        <thead><tr><th>المشروع</th><th>الإصدارات</th><th>الملفات</th><th>الحجم المتوقع</th></tr></thead>
        <tbody>{preview.families.map(family =>
          <tr key={family.projectUid}><td><strong>{family.name}</strong></td>
            <td>{family.versions}</td><td>{family.files}</td>
            <td>{sizeLabel(family.estimatedBytes)}</td></tr>)}</tbody>
      </table></div>
      <div className="form-actions export-actions">
        <button className="btn btn-quiet" onClick={() => {setStep(1);setPreview(null)}}>
          <ArrowRight size={16}/> تعديل الاختيار</button>
        <button className="btn btn-gold" disabled={busy || !canExport}
          onClick={createExport}>
          {busy ? "جارٍ إنشاء ملف التصدير..." : "إنشاء وتنزيل " + format}
          <Download size={17}/>
        </button>
      </div>
    </Panel>}

    {step === 4 && <Panel title="اكتمل إنشاء الحزمة">
      <div className="export-done"><CheckCircle2 size={44}/>
        <strong>حُفظ ملف التصدير في السجل</strong>
        <p>يمكن تنزيل الحزمة مرة أخرى من الجدول أدناه. راجع الملف عند الاستيراد
          للتحقق من سلامة جميع الإصدارات.</p>
        <button className="btn btn-outline" onClick={restart}>تصدير مشاريع أخرى</button>
      </div>
    </Panel>}

    <div className="section-gap">
      <Panel title="سجل حزم التصدير" subtitle="الحزم المكتملة والمحفوظة على الخادم"
        action={<button className="btn btn-quiet btn-sm" onClick={exports.reload}>
          <RefreshCw size={15}/> تحديث</button>}>
        {exports.error && <Alert message={exports.error}/>}
        {exports.loading ? <Loading/> : !exports.data?.length ?
          <Empty title="لا توجد حزم تصدير"/> :
          <div className="table-scroller"><table className="data-table">
            <thead><tr><th>ملف التصدير</th><th>الإصدارات المصدرة</th>
              <th>الملفات</th><th>التاريخ</th><th>الحالة</th><th>التنزيل</th></tr></thead>
            <tbody>{exports.data.map(pkg =>
              <tr key={pkg.id}><td><strong>{pkg.packageName}</strong></td>
                <td>{pkg.projectsCount}</td><td>{pkg.filesCount}</td>
                <td>{dateLabel(pkg.createdAt)}</td><td><Status value={pkg.status}/></td>
                <td><button className="btn btn-outline btn-sm" disabled={!canExport || pkg.status !== "EXPORTED"}
                  onClick={() => savePackage(pkg.id, pkg.packageName)}>
                  <Download size={15}/> تنزيل</button></td>
              </tr>
            )}</tbody>
          </table></div>}
      </Panel>
    </div>
  </>;
}
