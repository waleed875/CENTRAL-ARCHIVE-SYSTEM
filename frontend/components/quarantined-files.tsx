"use client";

import {useState} from "react";
import Link from "next/link";
import {Download,ShieldAlert} from "lucide-react";
import {useRemote} from "@/components/hooks";
import {PageHeader,Panel,Alert,Loading,Empty,Pagination} from "@/components/ui";
import {sizeLabel,dateLabel} from "@/lib/types";
import type {PageData} from "@/lib/types";

type QuarantineEntry={
 id:string;projectId:string;originalName:string;sourceReference?:string|null;
 sizeBytes:number;checksumSha256?:string|null;quarantineReason?:string|null;
 createdAt:string;
};

export function QuarantinedFiles(){
 const [page,setPage]=useState(1);
 const results=useRemote<PageData<QuarantineEntry>>(
  "/api/v1/admin/quarantine?page="+page+"&size=20");
 return <>
  <PageHeader title="الأصول المعزولة من الترحيل" eyebrow="الحفظ الآمن"
   description="تُحفظ الملفات التنفيذية أو غير المتعرف عليها دون تشغيلها أو إظهارها في المعاينة والبحث العادي."/>
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
  <div className="section-gap">
   <div className="hint"><ShieldAlert size={16}/> لا تفتح الملفات التنفيذية مباشرة، ولا تُعد إضافتها للملفات العادية قبل المراجعة الأمنية.</div>
  </div>
 </>;
}
