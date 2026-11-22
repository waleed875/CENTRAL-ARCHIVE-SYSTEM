"use client";
import Link from"next/link";import {useMemo}from"react";import {useSearchParams}from"next/navigation";
import {FolderKanban,Files,Building2,ClipboardCheck,ArrowLeft,Plus,UploadCloud,Download,DatabaseZap,FileText,ShieldCheck,Activity,Clock3,CheckCircle2}from"lucide-react";
import {PageHeader,Panel,Status,Loading,Empty,Alert}from"@/components/ui";
import {useRemote}from"@/components/hooks";
import type{DashboardStats,Project,PageData,UserInfo}from"@/lib/types";
import {dateLabel}from"@/lib/types";
import {Organizations}from"@/components/organizations";
import {Projects}from"@/components/projects";
import {AdvancedFileSearch} from "@/components/advanced-file-search";
import {QuarantinedFiles} from "@/components/quarantined-files";
import {ImportExport,Migration}from"@/components/operations";
import {AdminSection}from"@/components/admin";
import {AccountSettings} from "@/components/account-settings";
function Dashboard(){
 const current=useRemote<UserInfo>("/api/v1/auth/me");
 const roles=current.data?.roles??[];
 const isAdmin=roles.includes("system_admin");
 const canCreateProject=isAdmin||roles.includes("central_data_entry");
 const canManageOrganizations=isAdmin;
 const canReview=isAdmin||roles.includes("auditor");
 const canImport=isAdmin||roles.includes("import_officer");
 const canExport=isAdmin||roles.includes("export_officer");
 const canMigrate=isAdmin;
 const stats=useRemote<DashboardStats>("/api/v1/dashboard/stats"),projects=useRemote<PageData<Project>>("/api/v1/projects?page=1&size=6");
 const activity=useRemote<Array<{action:string;description:string;createdAt:string;user?:{fullName?:string}}>>("/api/v1/dashboard/activity?limit=5");
 const recent=projects.data?.data??[];
 return <><PageHeader eyebrow="نظرة عامة" title="صباح الخير، مرحبًا بك في الأرشيف" description="كل ما تحتاجه لإدارة مشاريع التوثيق في مساحة عمل واحدة."/ >
 <div className="stat-grid">
 {[[Building2,"الجهات والمؤسسات",stats.data?.organizations?.total??0],[FolderKanban,"إجمالي المشاريع",stats.data?.projects?.total??0],[Files,"الملفات المحفوظة",stats.data?.files?.total??0],[ClipboardCheck,"بانتظار المراجعة",stats.data?.pendingReviews??0]].map(([Icon,label,value],i)=>{const IconComponent=Icon as typeof Building2;return <div className="stat-card" key={String(label)}><div className="stat-icon"><IconComponent size={24} strokeWidth={1.8}/></div><div><span>{String(label)}</span><strong>{stats.loading?"—":Number(value).toLocaleString("ar")}</strong></div></div>})}
 </div><div className="dashboard-grid"><section className="hero"><div className="hero-body"><div className="eyebrow">رحلة الوثيقة تبدأ من هنا</div><h2>كل مشروع يستحق أرشيفًا يُعتمد عليه.</h2><p>من البيانات الأولية إلى الملفات والمراجعة والاعتماد، اجعل كل خطوة واضحة وموثّقة.</p><div className="hero-actions">{canCreateProject&&<Link className="btn btn-primary" href="/projects?create=1"><Plus size={17}/> إنشاء مشروع</Link>}<Link className="btn btn-outline" href="/projects"><FolderKanban size={17}/> استعراض المشاريع</Link></div></div><span className="hero-tally">✦</span></section>
 <Panel title="وصول سريع" subtitle="الإجراءات المتاحة وفق دورك وصلاحيات حسابك"><div className="quick-actions">{canManageOrganizations&&<Link href="/organizations" className="quick-link"><Building2 size={20}/> إدارة الجهات</Link>}{canReview&&<Link href="/reviews" className="quick-link"><ClipboardCheck size={20}/> مراجعة المشاريع</Link>}{canImport&&<Link href="/imports" className="quick-link"><UploadCloud size={20}/> استيراد حزمة</Link>}{canExport&&<Link href="/exports" className="quick-link"><Download size={20}/> تصدير مشروع</Link>}{canMigrate&&<Link href="/migration" className="quick-link"><DatabaseZap size={20}/> ترحيل قديم</Link>}<Link href="/projects" className="quick-link"><FileText size={20}/> ملفات المشاريع</Link></div></Panel></div>
 <div className="split-grid"><Panel title="أحدث المشاريع" subtitle="آخر المشاريع المحفوظة في الأرشيف" action={<Link href="/projects">عرض جميع المشاريع ←</Link>}>{projects.error&&<Alert message={projects.error}/>} {projects.loading?<Loading/>:recent.length?<div className="table-scroller"><table className="data-table"><thead><tr><th>المشروع</th><th>السنة</th><th>الحالة</th><th>تاريخ الإنشاء</th></tr></thead><tbody>{recent.map(p=><tr key={p.id}><td><Link className="table-link" href={"/projects/"+p.id}>{p.name}<span className="table-sub">{p.projectNumber}</span></Link></td><td>{p.year}</td><td><Status value={p.status}/></td><td>{dateLabel(p.createdAt)}</td></tr>)}</tbody></table></div>:<Empty title="لا توجد مشاريع بعد" description="أنشئ أول مشروع لإعداد سجلات الأرشيف"/>}</Panel>
 <Panel title="أحدث الأنشطة" subtitle="سجل مختصر للتغييرات الأخيرة">{activity.error?<Alert message={activity.error}/>:activity.loading?<Loading/>:activity.data?.length?<div className="activity-list">{activity.data.map((a,i)=><div className="activity-item" key={i}><div className="activity-dot"><Activity size={17}/></div><div><strong>{a.action?.replaceAll("_"," ")}</strong><p>{a.description}</p></div><time>{dateLabel(a.createdAt)}</time></div>)}</div>:<Empty title="لا توجد أنشطة حديثة"/>}</Panel></div>
 </>;
}
export function Workspace({section}:{section:string}){
 switch(section){
 case "dashboard":return <Dashboard/>;
 case "organizations":return <Organizations/>;
 case "projects":return <Projects/>;
 case "files":return <AdvancedFileSearch/>;
 case "quarantine":return <QuarantinedFiles/>;
 case "account":return <AccountSettings/>;
 case "reviews":return <Projects reviewMode/>;
 case "imports":return <ImportExport mode="imports"/>;
 case "exports":return <ImportExport mode="exports"/>;
 case "migration":return <Migration/>;
 case "users":case "audit":case "settings":return <AdminSection section={section}/>;
 default:return <><PageHeader title="الصفحة غير موجودة"/><Panel title="المسار غير متاح"><Empty description="اختر قسمًا من القائمة الجانبية."/></Panel></>;
 }
}
