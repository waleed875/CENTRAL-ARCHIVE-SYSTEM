"use client";
import {useEffect,useState} from "react";
import {Download,FileWarning} from "lucide-react";
import {download} from "@/lib/api";
import {Modal,Loading,Alert} from "@/components/ui";
import type {ArchiveFile} from "@/lib/types";
import {sizeLabel} from "@/lib/types";

type PreviewType = "pdf"|"image"|"audio"|"video"|"text"|"unsupported";
function previewType(file:ArchiveFile):PreviewType {
  const ext=file.originalName.split(".").pop()?.toLowerCase()??"";
  const mime=(file.mimeType??"").split(";")[0].trim().toLowerCase();
  if(ext==="pdf"&&mime==="application/pdf")return "pdf";
  if(["png","jpg","jpeg","gif","webp","bmp","avif"].includes(ext)&&mime.startsWith("image/"))return "image";
  if(["mp3","wav","flac","m4a"].includes(ext)&&mime.startsWith("audio/"))return "audio";
  if(["mp4","webm","mov"].includes(ext)&&mime.startsWith("video/"))return "video";
  if(["txt","csv","md","json","xml","sql","html","htm","svg","docx","xlsx","pptx"].includes(ext))return "text";
  return "unsupported";
}
export function FilePreview({file,onClose}:{file:ArchiveFile;onClose:()=>void}){
  const type=previewType(file);
  const [source,setSource]=useState("");
  const [text,setText]=useState("");
  const [error,setError]=useState("");
  const [loading,setLoading]=useState(type!=="unsupported"&&type!=="audio"&&type!=="video");
  const mediaUrl="/api/backend/api/v1/files/"+encodeURIComponent(file.id)+"/preview";
  useEffect(()=>{
    if(type==="unsupported"||type==="audio"||type==="video")return;
    let cancelled=false;
    let objectUrl="";
    const controller=new AbortController();
    async function load(){
      try{
        async function request(){return fetch("/api/backend/api/v1/files/"+encodeURIComponent(file.id)+"/preview",{credentials:"same-origin",cache:"no-store",signal:controller.signal});}
        let response=await request();
        if(response.status===401){
          const refreshed=await fetch("/api/session/refresh",{method:"POST",credentials:"same-origin",signal:controller.signal});
          if(refreshed.ok)response=await request();
        }
        if(!response.ok)throw new Error(response.status===401?"انتهت جلسة الدخول":response.status===403?"لا تملك صلاحية معاينة هذا الملف":response.status===415?"نوع الملف لا يدعم المعاينة":"تعذر فتح الملف ("+response.status+")");
        // Office documents are converted to bounded text by the server. Avoid unbounded
        // browser memory consumption for large binary previews.
        if(type!=="text"&&file.sizeBytes!=null&&file.sizeBytes>75*1024*1024){
          throw new Error("حجم الملف كبير للمعاينة في المتصفح؛ استخدم التنزيل");
        }
        if(type==="text"){
          const body=await response.text();
          if(!cancelled)setText(body.slice(0,120000));
        }else{
          const blob=await response.blob();
          if(blob.size>75*1024*1024)throw new Error("حجم الملف كبير للمعاينة في المتصفح؛ استخدم التنزيل");
          if(cancelled)return;
          objectUrl=URL.createObjectURL(blob);
          setSource(objectUrl);
        }
      }catch(e){
        if(!cancelled)setError(e instanceof Error?e.message:"تعذرت معاينة الملف");
      }finally{if(!cancelled)setLoading(false)}
    }
    void load();
    return ()=>{cancelled=true;controller.abort();if(objectUrl)URL.revokeObjectURL(objectUrl)};
  },[file.id,file.sizeBytes,type]);
  return <Modal title="معاينة الملف" subtitle={file.originalName+" · "+sizeLabel(file.sizeBytes)} onClose={onClose} wide>
    <div style={{width:"min(100%,1000px)",minHeight:220}}>
      {loading?<Loading/>:error?<Alert message={error}/>:type==="unsupported"?
        <div style={{textAlign:"center",padding:36}}><FileWarning size={34}/><p>هذا النوع لا يدعم المعاينة داخل المتصفح حاليًا.</p><p className="hint">يمكنك تنزيل الملف لفتحه باستخدام البرنامج المناسب.</p></div>:
      type==="text"?<pre dir="auto" style={{whiteSpace:"pre-wrap",overflowWrap:"anywhere",maxHeight:"65vh",overflow:"auto",fontSize:13,lineHeight:1.9,background:"#f7f8fa",borderRadius:10,padding:16}}>{text||"الملف فارغ"}</pre>:
      type==="pdf"?<iframe title={"معاينة "+file.originalName} src={source} style={{width:"100%",height:"65vh",border:0,background:"white"}}/>:
      type==="image"?<img src={source} alt={file.originalName} style={{display:"block",maxWidth:"100%",maxHeight:"65vh",margin:"auto",objectFit:"contain"}}/>:
      type==="audio"?<audio controls src={source} style={{width:"100%"}}/>:
      type==="video"?<video controls preload="metadata" src={mediaUrl} onError={()=>setError("تعذر تشغيل الفيديو؛ قد يكون ترميز MP4 غير مدعوم أو انتهت الجلسة")} style={{display:"block",maxWidth:"100%",maxHeight:"65vh",margin:"auto"}}/>:null}
    </div>
    <div className="form-actions">
      <button type="button" className="btn btn-outline" onClick={()=>download("/api/v1/files/"+file.id+"/download",file.originalName).catch(e=>setError(e.message))}><Download size={16}/> تنزيل الملف</button>
      <button type="button" className="btn btn-quiet" onClick={onClose}>إغلاق</button>
    </div>
  </Modal>;
}
