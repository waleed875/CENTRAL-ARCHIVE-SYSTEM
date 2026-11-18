"use client";

import {useEffect} from "react";
import {useRemote} from "@/components/hooks";
import {Alert,Loading} from "@/components/ui";

type MigrationLiveSnapshot = {
  batchId:string;sourceId:string;completedFiles:number;transferredBytes:number;
  filesPerMinute:number;mibPerSecond:number;estimatedSecondsRemaining:number|null;
  secondsSinceLastCompletion:number|null;stalled:boolean;
  activeSourceFiles:string[];averageReadDecodeMs:number;
  averageUploadMs:number;averageMetadataMs:number;
};

const number=(value:number)=>new Intl.NumberFormat("ar",{maximumFractionDigits:1}).format(value);
function duration(seconds:number|null) {
  if (seconds==null) return "لا تتوفر بيانات كافية";
  if (seconds<60) return Math.round(seconds)+" ثانية";
  if (seconds<3600) return Math.round(seconds/60)+" دقيقة";
  return number(seconds/3600)+" ساعة";
}

/** In-process telemetry only: durable completion counts remain in the batch table. */
export function MigrationLiveProgress({batchId}:{batchId:string}){
  const live=useRemote<MigrationLiveSnapshot>(
    "/api/v1/migration-batches/"+encodeURIComponent(batchId)+"/live-progress"
  );
  useEffect(()=>{
    const timer=window.setInterval(live.reload,3000);
    return()=>window.clearInterval(timer);
  },[live.reload]);

  if(live.error) return <Alert message={"تعذر قراءة مؤشرات الترحيل المباشرة: "+live.error}/>;
  if(live.loading&&!live.data) return <Loading/>;
  if(!live.data) return <p className="hint">لا تتوفر مقاييس مباشرة بعد. قد تكون الدفعة في مرحلة ترحيل المشاريع، أو تعمل على خادم آخر.</p>;
  const s=live.data;
  return <section className="section-gap" aria-label="مراقبة الترحيل المباشرة">
    <div className="chips"><strong>سرعة الترحيل المباشرة</strong>
      {s.stalled&&<span className="hint" role="status">تحذير: لم يكتمل ملف منذ دقيقتين رغم وجود مهام نشطة</span>}
    </div>
    <div className="dashboard-grid">
      <div className="stat-card"><span className="hint">ملفات مكتملة خلال التشغيل الحالي</span><strong>{number(s.completedFiles)}</strong></div>
      <div className="stat-card"><span className="hint">حجم الملفات المنقولة والمحفوظة</span><strong>{number(s.transferredBytes/1048576)} MiB</strong></div>
      <div className="stat-card"><span className="hint">ملفات / دقيقة (آخر 60 ثانية)</span><strong>{number(s.filesPerMinute)}</strong></div>
      <div className="stat-card"><span className="hint">معدل النقل (آخر 60 ثانية)</span><strong>{number(s.mibPerSecond)} MiB/s</strong></div>
    </div>
    <p className="hint">الوقت المتبقي التقريبي: {duration(s.estimatedSecondsRemaining)}. يعتمد على الملفات المكتملة في التشغيل الحالي وقد يتغير حسب أحجام الملفات.</p>
    <p className="hint">متوسط الزمن لكل ملف: القراءة وفك الترميز {number(s.averageReadDecodeMs)} ms · رفع MinIO {number(s.averageUploadMs)} ms · حفظ بيانات الربط {number(s.averageMetadataMs)} ms</p>
    {s.activeSourceFiles.length>0&&<p className="hint">ملفات قيد المعالجة: {s.activeSourceFiles.join("، ")}</p>}
    <p className="hint">هذه مؤشرات مؤقتة من خادم التنفيذ، وليست بديلًا عن سجل الملفات الدائم أو تدقيق MinIO.</p>
  </section>;
}
