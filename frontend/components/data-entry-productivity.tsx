"use client";
import {useState} from "react";
import Link from "next/link";
import {FolderKanban,UsersRound,ArrowLeft,RefreshCw,ClipboardCheck,CheckCircle2} from "lucide-react";
import {useRemote} from "@/components/hooks";
import {qs} from "@/lib/api";
import {PageHeader,Panel,Loading,Empty,Alert,Pagination,Status} from "@/components/ui";
import {dateLabel} from "@/lib/types";
import type {PageData} from "@/lib/types";

type EntryUser={
 userId:string;username:string;fullName:string;status:string;
 totalProjects:number;draftsAndCorrections:number;underReview:number;
 approved:number;archived:number;other:number
};
type Summary={totalProjects:number;dataEntryUsers:number;users:EntryUser[]};
type ProjectRow={
 id:string;name:string;projectNumber:string;year:number;status:string;
 versionNo:number;firstCreatedAt:string;latestVersionAt:string
};

export function DataEntryProductivity(){
 const current=useRemote<{roles:string[]}>("/api/v1/auth/me");
 const isAdmin=current.data?.roles?.includes("system_admin")===true;
 const isDataEntry=current.data?.roles?.includes("central_data_entry")===true;
 const summary=useRemote<Summary>(isAdmin?"/api/v1/admin/data-entry-productivity":null);
 const [selected,setSelected]=useState<EntryUser|null>(null);
 const [page,setPage]=useState(1);
 const projects=useRemote<PageData<ProjectRow>>(
  isAdmin&&selected?"/api/v1/admin/data-entry-productivity/"+selected.userId+"/projects"+qs({page,size:20}):null
 );
 const ownSummary=useRemote<EntryUser>(!isAdmin&&isDataEntry?"/api/v1/data-entry-productivity/me":null);
 const ownProjects=useRemote<PageData<ProjectRow>>(
  !isAdmin&&isDataEntry?"/api/v1/data-entry-productivity/me/projects"+qs({page,size:20}):null
 );
 if(current.loading)return <Loading/>;
 if(current.error)return <Alert message={current.error}/>;
 if(!isAdmin&&!isDataEntry)return <Alert message="هذا التقرير متاح لمدير النظام ومدخل البيانات فقط"/>;
 if(!isAdmin)return <>
  <PageHeader eyebrow="تقاريري" title="مشاريعي المدخلة"
   description="يعرض المشاريع التي أنشأتها، ويحتسب كل مشروع مرة واحدة حتى عند إضافة إصدارات لاحقة."
   action={<button type="button" className="btn btn-outline" onClick={()=>{ownSummary.reload();ownProjects.reload()}}><RefreshCw size={16}/> تحديث</button>}/>
  {ownSummary.error&&<Alert message={ownSummary.error}/>}
  <div className="stat-grid">
   <div className="stat-card"><div className="stat-icon"><FolderKanban size={22}/></div><div><span>إجمالي المشاريع التي أدخلتها</span><strong>{ownSummary.loading?"—":(ownSummary.data?.totalProjects??0).toLocaleString("ar")}</strong></div></div>
   <div className="stat-card"><div className="stat-icon"><ClipboardCheck size={22}/></div><div><span>قيد المراجعة</span><strong>{ownSummary.loading?"—":(ownSummary.data?.underReview??0).toLocaleString("ar")}</strong></div></div>
   <div className="stat-card"><div className="stat-icon"><CheckCircle2 size={22}/></div><div><span>معتمد أو مؤرشف</span><strong>{ownSummary.loading?"—":((ownSummary.data?.approved??0)+(ownSummary.data?.archived??0)).toLocaleString("ar")}</strong></div></div>
  </div>
  <Panel title="قائمة مشاريعي" subtitle="قد لا تظهر المشاريع التي تتجاوز مستوى السرية المصرح لك به.">
   {ownProjects.error&&<Alert message={ownProjects.error}/>}
   {ownProjects.loading?<Loading/>:!ownProjects.data?.data?.length?<Empty title="لا توجد مشاريع مسجلة باسمك"/>:
    <div className="table-scroller"><table className="data-table"><thead><tr><th>المشروع</th><th>الرقم</th><th>السنة</th><th>الحالة</th><th>الإصدار</th><th>تاريخ الإدخال</th></tr></thead><tbody>
     {ownProjects.data.data.map(p=><tr key={p.id}><td><Link className="table-link" href={"/projects/"+p.id}>{p.name}</Link></td><td>{p.projectNumber}</td><td>{p.year}</td><td><Status value={p.status}/></td><td>{p.versionNo}</td><td>{dateLabel(p.firstCreatedAt)}</td></tr>)}
    </tbody></table></div>}
   <Pagination page={page} totalPages={ownProjects.data?.totalPages??0} onChange={setPage}/>
  </Panel>
  <p className="hint">تُنسب الإصدارات إلى منشئ الإصدار الأول. المشاريع المرحّلة أو المحذوفة منطقيًا لا تدخل في العدد.</p>
 </>;
 return <>
  <PageHeader eyebrow="تقارير الإدارة" title="إنتاجية مدخلي البيانات"
   description="عدد المشاريع اليدوية المنسوبة إلى منشئ المشروع الأصلي، دون مضاعفة العدد عند إنشاء إصدارات جديدة. المشاريع المرحّلة والمستبعدة لا تُحتسب."
   action={<button type="button" className="btn btn-outline" onClick={()=>{summary.reload();projects.reload()}}><RefreshCw size={16}/> تحديث</button>}/>
  {summary.error&&<Alert message={summary.error}/>}
  <div className="stat-grid">
   <div className="stat-card"><div className="stat-icon"><FolderKanban size={22}/></div><div><span>المشاريع المسجلة بواسطة مدخلي البيانات</span><strong>{summary.loading?"—":(summary.data?.totalProjects??0).toLocaleString("ar")}</strong></div></div>
   <div className="stat-card"><div className="stat-icon"><UsersRound size={22}/></div><div><span>مدخلو البيانات الحاليون</span><strong>{summary.loading?"—":(summary.data?.dataEntryUsers??0).toLocaleString("ar")}</strong></div></div>
  </div>
  <Panel title="المشاريع حسب مدخل البيانات" subtitle="يشمل أصحاب دور إدخال البيانات الحاليين حتى إذا كان الحساب معطّلًا أو لا يملك مشاريع. لا تظهر المشاريع التي تتجاوز تصريح السرية الخاص بك.">
   {summary.loading?<Loading/>:!summary.data?.users.length?<Empty title="لا توجد حسابات تحمل دور مدخل البيانات"/>:
    <div className="table-scroller"><table className="data-table"><thead><tr>
     <th>مدخل البيانات</th><th>الإجمالي</th><th>مسودات وتصحيحات</th><th>قيد المراجعة</th><th>معتمد</th><th>مؤرشف</th><th>أخرى</th><th>إجراء</th>
    </tr></thead><tbody>{summary.data.users.map(u=><tr key={u.userId}>
     <td><strong>{u.fullName}</strong><span className="table-sub">@{u.username} · {u.status==="DISABLED"?"معطّل":u.status==="LOCKED"?"مقفل":"نشط"}</span></td>
     <td><strong>{u.totalProjects.toLocaleString("ar")}</strong></td>
     <td>{u.draftsAndCorrections.toLocaleString("ar")}</td>
     <td>{u.underReview.toLocaleString("ar")}</td>
     <td>{u.approved.toLocaleString("ar")}</td>
     <td>{u.archived.toLocaleString("ar")}</td>
     <td>{u.other.toLocaleString("ar")}</td>
     <td><button type="button" className="btn btn-quiet btn-sm" onClick={()=>{setSelected(u);setPage(1)}}>عرض المشاريع <ArrowLeft size={14}/></button></td>
    </tr>)}</tbody></table></div>}
  </Panel>
  {selected&&<div className="section-gap"><Panel title={"مشاريع: "+selected.fullName}
   subtitle="الروابط تعرض الإصدار الحالي؛ تاريخ الإسناد هو تاريخ إنشاء الإصدار الأول"
   action={<button className="btn btn-outline btn-sm" type="button" onClick={()=>setSelected(null)}>إغلاق</button>}>
   {projects.error&&<Alert message={projects.error}/>}
   {projects.loading?<Loading/>:!projects.data?.data?.length?<Empty title="لا توجد مشاريع ضمن نطاق صلاحياتك"/>:
    <div className="table-scroller"><table className="data-table"><thead><tr><th>المشروع</th><th>الرقم</th><th>السنة</th><th>الحالة الحالية</th><th>تاريخ الإنشاء الأصلي</th></tr></thead><tbody>
     {projects.data.data.map(p=><tr key={p.id}>
      <td><Link className="table-link" href={"/projects/"+p.id}>{p.name}</Link><span className="table-sub">الإصدار {p.versionNo}</span></td>
      <td>{p.projectNumber}</td><td>{p.year}</td><td><Status value={p.status}/></td><td>{dateLabel(p.firstCreatedAt)}</td>
     </tr>)}
    </tbody></table></div>}
   <Pagination page={page} totalPages={projects.data?.totalPages??0} onChange={setPage}/>
  </Panel></div>}
  <p className="hint">ملاحظة: تُنسب المشاريع لمن أنشأ الإصدار الأول، وليس لمن عدّل إصدارًا لاحقًا. الحسابات المحذوفة أو التي سُحب منها دور إدخال البيانات لا تظهر في هذا التقرير التاريخي.</p>
 </>;
}
