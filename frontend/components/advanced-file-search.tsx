"use client";

import {FormEvent,useEffect,useMemo,useState} from "react";
import {useRouter,useSearchParams} from "next/navigation";
import Link from "next/link";
import {Search,Eye,FolderOpen,RotateCcw} from "lucide-react";
import {useRemote} from "@/components/hooks";
import {FilePreview} from "@/components/file-preview";
import {PageHeader,Panel,Alert,Loading,Empty,Pagination} from "@/components/ui";
import {dateLabel,sizeLabel} from "@/lib/types";
import type {ArchiveFile,PageData,Organization,FileCategory} from "@/lib/types";

type Result={
 id:string;projectId:string;originalName:string;extension?:string|null;
 mimeType?:string|null;sizeBytes?:number|null;createdAt:string;
 categoryId?:string|null;categoryName?:string|null;projectName:string;
 projectNumber:string;projectYear:number;organizationId:string;
 organizationName:string;versionNo:number;isLatestVersion:boolean;
};
type SearchFilters={
 fileName:string;extension:string;categoryId:string;organizationId:string;
 projectNumber:string;year:string;from:string;to:string;
 includeVersions:boolean;sort:"newest"|"oldest"|"name";page:number;
};
const empty:SearchFilters={fileName:"",extension:"",categoryId:"",organizationId:"",
 projectNumber:"",year:"",from:"",to:"",includeVersions:false,sort:"newest",page:1};
function query(state:SearchFilters){
 const p=new URLSearchParams();
 for(const key of ["fileName","extension","categoryId","organizationId","projectNumber","year","from","to"] as const){
  if(state[key])p.set(key,state[key]);
 }
 if(state.includeVersions)p.set("includeVersions","true");
 if(state.sort!=="newest")p.set("sort",state.sort);
 if(state.page!==1)p.set("page",String(state.page));
 return p;
}
function readUrl(p:{get(name:string):string|null}){
 const mode=p.get("sort");
 const page=Number(p.get("page")??"1");
 return {
  fileName:p.get("fileName")??"",extension:p.get("extension")??"",
  categoryId:p.get("categoryId")??"",organizationId:p.get("organizationId")??"",
  projectNumber:p.get("projectNumber")??"",year:p.get("year")??"",
  from:p.get("from")??"",to:p.get("to")??"",
  includeVersions:p.get("includeVersions")==="true",
  sort:mode==="oldest"||mode==="name"?mode:"newest",
  page:Number.isSafeInteger(page)&&page>0?page:1
 } satisfies SearchFilters;
}
export function AdvancedFileSearch(){
 const router=useRouter();
 const params=useSearchParams();
 const filters=useMemo(()=>readUrl(params),[params]);
 const [draft,setDraft]=useState<SearchFilters>(filters);
 const [preview,setPreview]=useState<ArchiveFile|null>(null);
 useEffect(()=>{setDraft(filters)},[filters]);
 // The URL is the single source of truth. Searches made from the global
 // top bar, browser back/forward, and project-return links update results.
 function navigate(next:SearchFilters){
  const suffix=query(next).toString();
  router.push("/files"+(suffix?"?"+suffix:""),{scroll:false});
 }
 const apiQuery=query(filters);
 apiQuery.set("size","20");
 const endpoint="/api/v1/files/search?"+apiQuery.toString();
 const results=useRemote<PageData<Result>>(endpoint);
 const orgs=useRemote<Organization[]>("/api/v1/organizations/all");
 const categories=useRemote<FileCategory[]>("/api/v1/file-categories/all");
 const update=<K extends keyof SearchFilters>(key:K,value:SearchFilters[K])=>
  setDraft(v=>({...v,[key]:value}));
 const submit=(e:FormEvent)=>{e.preventDefault();navigate({...draft,page:1});};
 const reset=()=>{setDraft(empty);navigate(empty);};
 const back="/files"+(query(filters).toString()?"?"+query(filters).toString():"");
 function previewFile(file:Result){
  setPreview({id:file.id,projectId:file.projectId,originalName:file.originalName,
   categoryId:file.categoryId??"",categoryName:file.categoryName,
   mimeType:file.mimeType,sizeBytes:file.sizeBytes,status:"ACTIVE",createdAt:file.createdAt});
 }
 return <>
  <PageHeader eyebrow="استكشاف الأرشيف" title="بحث الملفات المتقدم"
   description="ابحث في اسم الملف عبر جميع المشاريع؛ لا يتم البحث في أسماء المشاريع أو داخل محتوى الوثائق."/>
  <Panel title="تصفية الملفات" subtitle="يمكن الجمع بين الفلاتر؛ لا تظهر إلا الملفات المسموح لك بعرضها.">
   <form className="form-stack" onSubmit={submit}>
    <div className="form-grid">
     <label className="field"><span>اسم الملف أو جزء منه</span>
      <input aria-label="البحث باسم الملف" maxLength={150} value={draft.fileName}
       onChange={e=>update("fileName",e.target.value)} placeholder="مثال: final أو تقرير"/></label>
     <label className="field"><span>امتداد الملف</span>
      <input maxLength={12} value={draft.extension} onChange={e=>update("extension",e.target.value)}
       placeholder="pdf / docx / xlsx"/></label>
     <label className="field"><span>تصنيف الملف</span>
      <select value={draft.categoryId} onChange={e=>update("categoryId",e.target.value)}>
       <option value="">كل التصنيفات</option>{categories.data?.map(c=><option key={c.id} value={c.id}>{c.name}</option>)}
      </select></label>
     <label className="field"><span>الجهة التابعة</span>
      <select value={draft.organizationId} onChange={e=>update("organizationId",e.target.value)}>
       <option value="">كل الجهات</option>{orgs.data?.map(o=><option key={o.id} value={o.id}>{o.name}</option>)}
      </select></label>
     <label className="field"><span>رقم المشروع (فلتر)</span>
      <input maxLength={50} value={draft.projectNumber} onChange={e=>update("projectNumber",e.target.value)}/></label>
     <label className="field"><span>سنة المشروع</span>
      <input type="number" min={1900} max={2100} value={draft.year}
       onChange={e=>update("year",e.target.value)}/></label>
     <label className="field"><span>رفع من تاريخ</span>
      <input type="date" value={draft.from} onChange={e=>update("from",e.target.value)}/></label>
     <label className="field"><span>رفع إلى تاريخ</span>
      <input type="date" min={draft.from||undefined} value={draft.to}
       onChange={e=>update("to",e.target.value)}/></label>
     <label className="field"><span>ترتيب النتائج</span>
      <select value={draft.sort} onChange={e=>update("sort",e.target.value as SearchFilters["sort"])}>
       <option value="newest">الأحدث أولًا</option><option value="oldest">الأقدم أولًا</option>
       <option value="name">اسم الملف أبجديًا</option>
      </select></label>
     <label className="field"><span>الإصدارات</span>
      <select value={draft.includeVersions?"all":"latest"}
       onChange={e=>update("includeVersions",e.target.value==="all")}>
       <option value="latest">الإصدار الحالي فقط</option><option value="all">جميع الإصدارات</option>
      </select></label>
    </div>
    <div className="form-actions">
     <button className="btn btn-gold" type="submit"><Search size={16}/> بحث في أسماء الملفات</button>
     <button className="btn btn-quiet" type="button" onClick={reset}><RotateCcw size={15}/> مسح الفلاتر</button>
    </div>
   </form>
  </Panel>
  <div className="section-gap"><Panel title="نتائج الملفات"
   subtitle={results.data?"عدد النتائج المطابقة: "+results.data.total.toLocaleString("ar"):"النتائج تحترم صلاحيات المستخدم ومستويات السرية"}>
   {results.error&&<Alert message={results.error}/>}
   {results.loading?<Loading/>:!results.data?.data?.length?
    <Empty title="لا توجد ملفات مطابقة" description="جرّب اسم ملف آخر أو خفف شروط البحث."/>:
    <div className="table-scroller"><table className="data-table"><thead><tr>
     <th>اسم الملف</th><th>المشروع المرتبط</th><th>الجهة</th><th>التصنيف</th>
     <th>الحجم</th><th>تاريخ الرفع</th><th>الإجراءات</th>
    </tr></thead><tbody>{results.data.data.map(f=><tr key={f.id}>
     <td><strong>{f.originalName}</strong><span className="table-sub">{f.extension??"—"}</span></td>
     <td><strong>{f.projectName}</strong><span className="table-sub">{f.projectNumber} · {f.projectYear} · الإصدار {f.versionNo}</span></td>
     <td>{f.organizationName}</td><td>{f.categoryName??"—"}</td>
     <td>{sizeLabel(f.sizeBytes)}</td><td>{dateLabel(f.createdAt)}</td>
     <td><div className="chips">
      <button className="btn btn-outline btn-sm" type="button" onClick={()=>previewFile(f)}><Eye size={15}/> معاينة الملف</button>
      <Link className="btn btn-quiet btn-sm" href={"/projects/"+f.projectId+"?back="+encodeURIComponent(back)}>
       <FolderOpen size={15}/> فتح المشروع
      </Link>
     </div></td>
    </tr>)}</tbody></table></div>}
   <Pagination page={filters.page} totalPages={results.data?.totalPages??0}
    onChange={page=>navigate({...filters,page})}/>
  </Panel></div>
  {preview&&<FilePreview key={preview.id} file={preview} onClose={()=>setPreview(null)}/>}
 </>;
}
