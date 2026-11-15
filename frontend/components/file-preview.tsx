"use client";
import {useEffect,useState} from "react";
import {Download,FileWarning,ZoomIn,ZoomOut,RotateCcw,ChevronLeft,ChevronRight} from "lucide-react";
import {download} from "@/lib/api";
import {MarkdownPreview} from "@/components/markdown-preview";
import {Modal,Loading,Alert} from "@/components/ui";
import type {ArchiveFile} from "@/lib/types";
import {sizeLabel} from "@/lib/types";

type PreviewType="pdf"|"image"|"audio"|"video"|"office"|"markdown"|"text"|"unsupported";
type OfficeRun={text:string;bold:boolean;italic:boolean;underline:boolean};
type WordBlock={type:string;align:string;runs:OfficeRun[];rows:string[][]};
type SheetView={name:string;rows:string[][];truncated:boolean};
type SlideView={number:number;lines:string[]};
type OfficePreview={type:string;blocks:WordBlock[];sheets:SheetView[];slides:SlideView[];truncated:boolean};

function extension(name:string){return name.split(".").pop()?.toLowerCase()??""}
export function previewType(file:ArchiveFile):PreviewType {
  const ext=extension(file.originalName);
  const mime=(file.mimeType??"").split(";")[0].trim().toLowerCase();
  // The backend always validates MIME and permissions. Do not guess based only on the
  // filename for unknown formats or risk displaying executable markup.
  if(ext==="pdf"&&(mime==="application/pdf"||!mime))return "pdf";
  if(["png","jpg","jpeg","gif","webp","bmp","avif"].includes(ext)&&(mime.startsWith("image/")||!mime))return "image";
  if(["mp3","wav","flac","m4a"].includes(ext)&&(mime.startsWith("audio/")||!mime))return "audio";
  if(["mp4","webm","mov"].includes(ext)&&(mime.startsWith("video/")||!mime))return "video";
  if(["docx","xlsx","pptx"].includes(ext))return "office";
  if(ext==="md")return "markdown";
  if(["txt","csv","md","json","xml","sql","html","htm","svg"].includes(ext))return "text";
  return "unsupported";
}
function errorForStatus(status:number){
  if(status===401)return "انتهت الجلسة؛ يرجى تسجيل الدخول مجددًا";
  if(status===403)return "لا تملك صلاحية معاينة هذا الملف";
  if(status===404)return "لم يُعثر على الملف أو لم يعد متاحًا";
  if(status===413)return "الملف كبير جدًا للمعاينة";
  if(status===415)return "هذا النوع غير مدعوم للمعاينة";
  if(status===416)return "تعذر طلب نطاق بيانات الملف";
  if(status===422)return "تعذرت قراءة محتوى المستند";
  return "تعذر فتح الملف (HTTP "+status+")";
}
async function authorizedPreview(url:string,signal:AbortSignal){
  const request=()=>fetch(url,{credentials:"same-origin",cache:"no-store",signal});
  let response=await request();
  if(response.status===401){
    const refresh=await fetch("/api/session/refresh",{method:"POST",credentials:"same-origin",signal});
    if(refresh.ok)response=await request();
  }
  if(!response.ok)throw new Error(errorForStatus(response.status));
  return response;
}
function OfficeView({data}:{data:OfficePreview}){
  const [sheetIndex,setSheetIndex]=useState(0);
  const current=data.sheets[sheetIndex];
  return <div className="archive-office-view">
    {data.type==="word"&&<div className="archive-word-page" dir="auto">
      {data.blocks.map((block,i)=>block.type==="table"?
        <div key={i} className="archive-table-scroll"><table className="archive-preview-table"><tbody>{block.rows.map((row,r)=><tr key={r}>{row.map((cell,c)=><td key={c}>{cell}</td>)}</tr>)}</tbody></table></div>:
        <p key={i} className={block.type==="heading"?"archive-word-heading":undefined} style={{textAlign:block.align==="CENTER"?"center":block.align==="RIGHT"?"right":block.align==="LEFT"?"left":"start"}}>
          {block.runs.map((run,k)=><span key={k} style={{fontWeight:run.bold?700:undefined,fontStyle:run.italic?"italic":undefined,textDecoration:run.underline?"underline":undefined}}>{run.text}</span>)}
        </p>)}
      {!data.blocks.length&&<p>المستند لا يحتوي نصًا قابلًا للمعاينة.</p>}
    </div>}
    {data.type==="excel"&&<>
      <div role="tablist" className="archive-sheet-tabs" aria-label="أوراق عمل Excel">
        {data.sheets.map((sheet,i)=><button key={i} type="button" role="tab" aria-selected={sheetIndex===i} className={sheetIndex===i?"active":""} onClick={()=>setSheetIndex(i)}>{sheet.name}</button>)}
      </div>
      {current?<div className="archive-table-scroll" dir="ltr"><table className="archive-preview-table archive-excel-table"><thead><tr><th scope="col">#</th>{Array.from({length:Math.max(0,...current.rows.map(row=>row.length))},(_,i)=><th key={i} scope="col">{columnName(i)}</th>)}</tr></thead>
        <tbody>{current.rows.map((row,i)=><tr key={i}><th scope="row">{i+1}</th>{Array.from({length:Math.max(0,...current.rows.map(r=>r.length))},(_,j)=><td key={j} dir="auto">{row[j]??""}</td>)}</tr>)}</tbody>
      </table></div>:<p>لا توجد أوراق عمل قابلة للعرض.</p>}
      {current?.truncated&&<p className="hint">تم عرض جزء من ورقة العمل فقط. نزّل الملف لمشاهدة جميع البيانات.</p>}
    </>}
    {data.type==="powerpoint"&&<div className="archive-slide-list">{data.slides.map(slide=><section className="archive-slide" key={slide.number}><strong>شريحة {slide.number}</strong>{slide.lines.map((line,i)=><p key={i}>{line}</p>)}</section>)}</div>}
    {data.truncated&&<p className="archive-preview-warning">المعاينة مختصرة لحماية أداء النظام. الملف الأصلي متاح للتنزيل.</p>}
    <p className="hint">معاينة للقراءة فقط؛ قد تختلف الرسومات والخطوط والتنسيقات المعقدة عن برنامج Office.</p>
  </div>;
}
function columnName(index:number){let name="";for(let n=index+1;n>0;n=Math.floor((n-1)/26))name=String.fromCharCode(65+(n-1)%26)+name;return name}

export function FilePreview({file,onClose}:{file:ArchiveFile;onClose:()=>void}){
  const type=previewType(file);
  const [source,setSource]=useState("");
  const [text,setText]=useState("");
  const [office,setOffice]=useState<OfficePreview|null>(null);
  const [error,setError]=useState("");
  const [loading,setLoading]=useState(!["unsupported","audio","video"].includes(type));
  const [zoom,setZoom]=useState(1);
  const [pdfPage,setPdfPage]=useState(1);
  const [pdfPages,setPdfPages]=useState(0);
  const mediaUrl="/api/backend/api/v1/files/"+encodeURIComponent(file.id)+"/preview";

  useEffect(()=>{
    if(["unsupported","audio","video","pdf"].includes(type))return;
    let cancelled=false;
    let objectUrl="";
    const controller=new AbortController();
    async function load(){
      try{
        const url=mediaUrl+(type==="office"?"/office":"");
        if(type!=="office"&&type!=="text"&&file.sizeBytes!=null&&file.sizeBytes>75*1024*1024)
          throw new Error("حجم الملف كبير للمعاينة داخل المتصفح؛ يمكنك تنزيله");
        const response=await authorizedPreview(url,controller.signal);
        if(type==="office"){
          const payload=await response.json() as OfficePreview;
          if(!["word","excel","powerpoint"].includes(payload.type))throw new Error("استجابة معاينة Office غير متوقعة");
          if(!cancelled)setOffice(payload);
        }else if(type==="text"||type==="markdown"){
          const body=await response.text();
          if(!cancelled)setText(body.slice(0,120000));
        }else{
          const blob=await response.blob();
          if(blob.size>75*1024*1024)throw new Error("حجم الملف كبير للمعاينة داخل المتصفح؛ يمكنك تنزيله");
          if(cancelled)return;
          objectUrl=URL.createObjectURL(blob);
          setSource(objectUrl);
        }
      }catch(e){
        if(!cancelled&&!(e instanceof DOMException&&e.name==="AbortError"))
          setError(e instanceof Error?e.message:"تعذرت معاينة الملف");
      }finally{if(!cancelled)setLoading(false)}
    }
    void load();
    return ()=>{cancelled=true;controller.abort();if(objectUrl)URL.revokeObjectURL(objectUrl)};
  },[mediaUrl,file.sizeBytes,type]);

  useEffect(()=>{
    if(type!=="pdf")return;
    let cancelled=false;
    let objectUrl="";
    const controller=new AbortController();
    setLoading(true);
    setError("");
    async function showPdf(){
      try{
        const base=mediaUrl+"/pdf";
        let count=pdfPages;
        if(!count){
          const info=await authorizedPreview(base+"/info",controller.signal);
          const payload=await info.json() as {pageCount:number};
          if(!Number.isInteger(payload.pageCount)||payload.pageCount<1)throw new Error("بيانات صفحات PDF غير صالحة");
          count=payload.pageCount;
          if(!cancelled)setPdfPages(count);
        }
        if(pdfPage>count)throw new Error("صفحة PDF المطلوبة غير موجودة");
        const response=await authorizedPreview(base+"/pages/"+pdfPage,controller.signal);
        const blob=await response.blob();
        if(!blob.type.includes("image/png"))throw new Error("خادم المعاينة لم يُرجع صورة PDF صالحة");
        if(cancelled)return;
        objectUrl=URL.createObjectURL(blob);
        setSource(objectUrl);
      }catch(e){
        if(!cancelled&&!(e instanceof DOMException&&e.name==="AbortError"))setError(e instanceof Error?e.message:"تعذرت معاينة PDF");
      }finally{if(!cancelled)setLoading(false)}
    }
    void showPdf();
    return ()=>{cancelled=true;controller.abort();if(objectUrl)URL.revokeObjectURL(objectUrl)};
  },[type,mediaUrl,pdfPage,pdfPages]);

  return <Modal title="معاينة الملف" subtitle={file.originalName+" · "+sizeLabel(file.sizeBytes)} onClose={onClose} wide>
    <div className="archive-preview-body">
      {loading?<Loading/>:error?<Alert message={error}/>:type==="unsupported"?
        <div className="archive-preview-unsupported"><FileWarning size={34}/><p>هذا النوع لا يدعم المعاينة داخل المتصفح حاليًا.</p><p className="hint">استخدم زر التنزيل لفتحه في البرنامج المناسب.</p></div>:
      type==="office"&&office?<OfficeView data={office}/>:
      type==="markdown"?<MarkdownPreview source={text}/>: 
      type==="text"?<pre dir="auto" className="archive-preview-text">{text||"الملف فارغ"}</pre>:
      type==="pdf"&&source?<div className="archive-pdf-pages">
        <div className="archive-pdf-controls">
          <button type="button" disabled={pdfPage<=1} onClick={()=>{setSource("");setPdfPage(p=>p-1)}} aria-label="الصفحة السابقة"><ChevronRight size={17}/> السابقة</button>
          <span>صفحة {pdfPage} من {pdfPages}</span>
          <button type="button" disabled={pdfPage>=pdfPages} onClick={()=>{setSource("");setPdfPage(p=>p+1)}} aria-label="الصفحة التالية">التالية <ChevronLeft size={17}/></button>
        </div>
        <img className="archive-pdf-page-image" src={source} alt={"صفحة "+pdfPage+" من "+pdfPages} onError={()=>setError("تعذر عرض صورة صفحة PDF")}/>
      </div>:
      type==="image"&&source?<div className="archive-preview-image-wrap"><div className="archive-preview-zoom">
        <button type="button" onClick={()=>setZoom(v=>Math.max(.25,+(v-.25).toFixed(2)))} aria-label="تصغير الصورة"><ZoomOut size={16}/></button>
        <span>{Math.round(zoom*100)}%</span>
        <button type="button" onClick={()=>setZoom(v=>Math.min(4,+(v+.25).toFixed(2)))} aria-label="تكبير الصورة"><ZoomIn size={16}/></button>
        <button type="button" onClick={()=>setZoom(1)} aria-label="إعادة ضبط التكبير"><RotateCcw size={16}/></button>
      </div><img src={source} alt={file.originalName} onError={()=>setError("تعذر عرض الصورة؛ قد لا يدعم المتصفح ترميزها")} style={{transform:"scale("+zoom+")",transformOrigin:"center center"}}/></div>:
      type==="audio"?<audio controls preload="metadata" src={mediaUrl} onError={()=>setError("تعذر تشغيل الصوت؛ تحقق من الترميز أو صلاحية الجلسة")} className="archive-preview-audio"/>:
      type==="video"?<video controls preload="metadata" src={mediaUrl} onError={()=>setError("تعذر تشغيل الفيديو؛ تحقق من الترميز أو صلاحية الجلسة")} className="archive-preview-video"/>:
      null}
    </div>
    <div className="form-actions">
      <button type="button" className="btn btn-outline" onClick={()=>download("/api/v1/files/"+file.id+"/download",file.originalName).catch(e=>setError(e.message))}><Download size={16}/> تنزيل الملف</button>
      <button type="button" className="btn btn-quiet" onClick={onClose}>إغلاق</button>
    </div>
  </Modal>;
}
