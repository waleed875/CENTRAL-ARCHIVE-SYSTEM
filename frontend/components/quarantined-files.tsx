"use client";

import {useState,FormEvent} from "react";
import {api,jsonBody} from "@/lib/api";
import Link from "next/link";
import {Download,ShieldAlert} from "lucide-react";
import {useRemote} from "@/components/hooks";
import {PageHeader,Panel,Alert,Loading,Empty,Pagination,Modal,Field} from "@/components/ui";
import {sizeLabel,dateLabel} from "@/lib/types";
import type {PageData} from "@/lib/types";

type QuarantineEntry={
 id:string;projectId:string;originalName:string;sourceReference?:string|null;
 sizeBytes:number;checksumSha256?:string|null;quarantineReason?:string|null;
 createdAt:string;
};

export function QuarantinedFiles(){
 const [page,setPage]=useState(1);
 const [review,setReview]=useState<QuarantineEntry|null>(null);
 const [decision,setDecision]=useState("KEEP"),[note,setNote]=useState("");
 const [busy,setBusy]=useState(false),[error,setError]=useState(""),[success,setSuccess]=useState("");
 async function submitReview(event:FormEvent){
  event.preventDefault();if(!review)return;
  if(decision==="RELEASE"&&!window.confirm("سيُعاد التحقق من بصمة الملف وتوقيعه. لا يمكن الإفراج عن أي ملف يفشل الفحص. متابعة؟"))return;
  setBusy(true);setError("");setSuccess("");
  try{
   await api("/api/v1/admin/quarantine/"+encodeURIComponent(review.id)+"/review",
    {method:"POST",...jsonBody({decision,note})});
   setReview(null);setNote("");setSuccess(decision==="KEEP"?"تم توثيق القرار وإبقاء الملف معزولًا":"تم التحقق من الملف والإفراج عنه بنجاح");
   results.reload();
  }catch(e){setError(e instanceof Error?e.message:"تعذرت مراجعة الملف")}finally{setBusy(false)}
 }
 const results=useRemote<PageData<QuarantineEntry>>(
  "/api/v1/admin/quarantine?page="+page+"&size=20");
 return <>
  <PageHeader title="الأصول المعزولة من الترحيل" eyebrow="الحفظ الآمن"
   description="تُحفظ الملفات التنفيذية أو غير المتعرف عليها دون تشغيلها أو إظهارها في المعاينة والبحث العادي."/>
  {success&&<Alert kind="success" message={success}/>}
  {error&&<Alert message={error}/>}
  <Panel title="ملفات تحتاج إلى مراجعة إدارية"
   subtitle="استرجاعها متاح فقط لمدير النظام بصيغة .bin غير تنفيذية افتراضيًا. تعامل مع محتوياتها في بيئة فحص معزولة.">
   {results.error&&<Alert message={results.error}/>}
   {results.loading?<Loading/>:!results.data?.data?.length?
    <Empty title="لا توجد ملفات معزولة" description="ستظهر هنا الملفات التاريخية التي تحتاج إلى مراجعة بعد الترحيل."/>:
    <div className="table-scroller"><table className="data-table"><thead><tr>
      <th>اسم الملف الأصلي</th><th>مرجع المصدر</th><th>الحجم</th>
      <th>سبب العزل</th><th>تاريخ الترحيل</th><th>الإجراءات</th>
    </tr></thead><tbody>{results.data.data.map(file=><tr key={file.id}>
     <td><strong>{file.originalName}</strong><span className="table-sub">SHA-256: {file.checksumSha256??"—"}</span></td>
     <td>{file.sourceReference??"—"}</td>
     <td>{sizeLabel(file.sizeBytes)}</td><td>{file.quarantineReason??"محتوى غير معروف"}</td>
     <td>{dateLabel(file.createdAt)}</td>
     <td><div className="chips">
       <button type="button" className="btn btn-gold btn-sm" onClick={()=>{setReview(file);setDecision("KEEP");setNote("");setError("")}}>مراجعة القرار</button>
       <Link className="btn btn-quiet btn-sm" href={"/projects/"+file.projectId}>عرض المشروع</Link>
       <a className="btn btn-outline btn-sm" rel="nofollow"
        href={"/api/backend/api/v1/admin/quarantine/"+encodeURIComponent(file.id)+"/download"}
        download={"quarantine-"+file.id+".bin"}>
         <Download size={15}/> تنزيل للمراجعة
       </a>
     </div></td>
    </tr>)}</tbody></table></div>}
   <Pagination page={page} totalPages={results.data?.totalPages??0} onChange={setPage}/>
  </Panel>
  {review&&<Modal title={"مراجعة "+review.originalName} subtitle="قرار إداري مسجل. الإفراج لا يتم إلا بعد الفحص الآلي وبصمة SHA-256، وقد يرفض الملفات غير الآمنة." onClose={()=>setReview(null)}>
   <form className="form-stack" onSubmit={submitReview}>
    <p>السبب: {review.quarantineReason??"غير معروف"}</p>
    <p>SHA-256: <span className="monospace">{review.checksumSha256??"غير موجود"}</span></p>
    <Field label="القرار"><select value={decision} onChange={e=>setDecision(e.target.value)}>
     <option value="KEEP">الإبقاء معزولًا وتوثيق المراجعة</option>
     <option value="RELEASE">إعادة فحص الملف والإفراج إذا كان آمنًا</option>
    </select></Field>
    <Field label="مبرر القرار"><textarea required minLength={12} maxLength={1000} value={note} onChange={e=>setNote(e.target.value)} placeholder="اكتب نتيجة المراجعة وأسباب القرار"/></Field>
    {error&&<Alert message={error}/>}
    <div className="form-actions"><button disabled={busy} className="btn btn-gold">تسجيل القرار</button></div>
   </form>
  </Modal>}
  <div className="section-gap">
   <div className="hint"><ShieldAlert size={16}/> لا تفتح الملفات التنفيذية مباشرة، ولا تُعد إضافتها للملفات العادية قبل المراجعة الأمنية.</div>
  </div>
 </>;
}
