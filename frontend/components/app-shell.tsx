"use client";
import {useEffect,useState,FormEvent} from "react";
import Link from "next/link";import {usePathname,useRouter} from "next/navigation";
import {LayoutDashboard,Building2,FolderKanban,ClipboardCheck,PackageOpen,PackageCheck,DatabaseZap,UsersRound,ScrollText,Settings2,LibraryBig,Search,LogOut,Menu,X,Plus,ShieldCheck,ShieldAlert} from "lucide-react";
import {api} from "@/lib/api";import type{UserInfo}from "@/lib/types";
const menu=[
{href:"/",label:"الرئيسية",icon:LayoutDashboard},
{href:"/organizations",label:"الجهات والمؤسسات",icon:Building2},
{href:"/projects",label:"المشاريع",icon:FolderKanban},
{href:"/files",label:"بحث الملفات المتقدم",icon:Search},
{href:"/reviews",label:"المراجعة والاعتماد",icon:ClipboardCheck},
{href:"/imports",label:"استيراد الحزم",icon:PackageOpen},
{href:"/exports",label:"تصدير المشاريع",icon:PackageCheck},
{href:"/migration",label:"ترحيل البيانات",icon:DatabaseZap},
{href:"/quarantine",label:"الملفات المعزولة",icon:ShieldAlert},
{href:"/users",label:"المستخدمون",icon:UsersRound},
{href:"/audit",label:"سجل التدقيق",icon:ScrollText},
{href:"/settings",label:"الإعدادات",icon:Settings2},
{href:"/account",label:"إعدادات حسابي",icon:UsersRound}
];
export function AppShell({children}:{children:React.ReactNode}){
 const [user,setUser]=useState<UserInfo|null>(null),[open,setOpen]=useState(false),[search,setSearch]=useState(""),[signingOut,setSigningOut]=useState(false);
 const pathname=usePathname(),router=useRouter();
 const authorized=(...roles:string[])=>!!user&&(user.roles.includes("system_admin")||roles.some(role=>user.roles.includes(role)));
 const showSection=(href:string)=>{if(["/","/organizations","/projects","/files","/account"].includes(href))return true; if(href==="/reviews")return authorized("auditor");if(href==="/imports")return authorized("import_officer");if(href==="/exports")return authorized("export_officer");return authorized("system_admin")};
 useEffect(()=>{let active=true;api<UserInfo>("/api/v1/auth/me").then(v=>{if(active)setUser(v)}).catch(()=>{});return()=>{active=false}},[]);
 useEffect(()=>setOpen(false),[pathname]);
 function submitSearch(e:FormEvent){e.preventDefault();router.push("/files?fileName="+encodeURIComponent(search))}
 async function logout(){setSigningOut(true);try{await fetch("/api/session/logout",{method:"POST"})}finally{router.replace("/login");router.refresh()}}
 return <div className="shell"><div className={open?"mobile-scrim visible":"mobile-scrim"} onClick={()=>setOpen(false)}/>
 <aside className={"sidebar"+(open?" open":"")} aria-label="التنقل الرئيسي">
   <div className="brand"><div className="brand-mark"><LibraryBig size={26} strokeWidth={1.6}/></div><div><strong>النظام المركزي</strong><span>للأرشيف والتوثيق</span></div><button className="mobile-close" onClick={()=>setOpen(false)} aria-label="إغلاق القائمة"><X size={20}/></button></div>
   <nav className="side-links">{menu.filter(({href})=>showSection(href)).map(({href,label,icon:Icon})=>{const active=pathname===href||(href!=="/"&&pathname.startsWith(href+"/"));return <Link className={"side-link"+(active?" active":"")} href={href} key={href}><Icon size={19} strokeWidth={1.8}/><span>{label}</span>{active&&<span className="active-indicator"/>}</Link>})}</nav>
   <div className="sidebar-bottom"><div className="sidebar-glyph">۞</div><div className="side-secure"><ShieldCheck size={17}/><span>مساحة أرشيف مؤمّنة</span></div><small>Central Archive System</small></div>
 </aside>
 <div className="main-column"><header className="topbar"><div className="topbar-start"><button className="icon-button menu-toggle" onClick={()=>setOpen(true)} aria-label="فتح القائمة"><Menu/></button><strong>{menu.find(m=>pathname===m.href)?.label??(pathname.startsWith("/projects/")?"تفاصيل المشروع":"الأرشيف")}</strong></div>
 <form className="top-search" onSubmit={submitSearch}><Search size={18}/><input value={search} onChange={e=>setSearch(e.target.value)} placeholder="ابحث باسم الملف..." aria-label="البحث في أسماء الملفات"/><kbd>↵</kbd></form>
 <div className="top-actions">{authorized("central_data_entry")&&<Link className="top-create" href="/projects?create=1"><Plus size={17}/> مشروع جديد</Link>}<Link href="/account" className="profile" title="إعدادات حسابي"><div className="avatar">{user?.fullName?.[0]??"م"}</div><div className="profile-copy"><strong>{user?.fullName??"المستخدم"}</strong><span>{user?.roles?.includes("system_admin")?"مدير النظام":user?.organization??"مستخدم الأرشيف"}</span></div></Link><button className="icon-button logout" title="تسجيل الخروج" onClick={logout} disabled={signingOut} aria-label="تسجيل الخروج"><LogOut size={19}/></button></div>
 </header><main className="page-content">{children}</main><footer className="footer">النظام المركزي للأرشيف <span>•</span> بيئة عمل موحّدة للوثائق والمشاريع</footer></div></div>
}
