import type { ApiEnvelope,PageData } from "./types";
export class ApiError extends Error {
  constructor(public status:number, message:string, public code?:string){super(message);this.name="ApiError"}
}
export async function api<T>(path:string,init:RequestInit={},retry=true):Promise<T>{
  if(!path.startsWith("/api/v1/"))throw new ApiError(400,"مسار غير صالح");
  const response=await fetch("/api/backend"+path,{...init,credentials:"same-origin",cache:"no-store"});
  if(response.status===401&&retry){
    const refresh=await fetch("/api/session/refresh",{method:"POST",credentials:"same-origin"});
    if(refresh.ok)return api<T>(path,init,false);
    window.location.assign("/login");
    throw new ApiError(401,"انتهت الجلسة. يرجى تسجيل الدخول مجددًا");
  }
  // Some Spring endpoints (for example deleting a project member) intentionally return 204.
  // A successful empty response is valid and must not be parsed as a missing JSON envelope.
  if(response.status===204)return undefined as T;
  const contentType=response.headers.get("content-type")??"";
  let payload:ApiEnvelope<T>|null=null;
  if(contentType.includes("application/json")){try{payload=await response.json()}catch{}}
  if(!response.ok||payload?.success===false){
    const errorData=payload?.data as Record<string,unknown>|undefined;
    throw new ApiError(response.status, String(errorData?.message??payload?.meta?.message??"تعذر تنفيذ الطلب"),String(errorData?.code??""));
  }
  if(!payload)throw new ApiError(502,"استجابة غير متوقعة من الخادم");
  return payload.data;
}
export function jsonBody(data:unknown):RequestInit{return{headers:{"Content-Type":"application/json"},body:JSON.stringify(data)}}
export function qs(params:Record<string,string|number|undefined|null>){const s=new URLSearchParams();Object.entries(params).forEach(([k,v])=>{if(v!==undefined&&v!==null&&v!=="")s.set(k,String(v))});return s.size?"?"+s.toString():""}
export async function download(path:string,filename:string){
  const response=await fetch("/api/backend"+path,{credentials:"same-origin"});
  if(!response.ok)throw new ApiError(response.status,"تعذر تنزيل الملف");
  const blob=await response.blob();const url=URL.createObjectURL(blob);const a=document.createElement("a");a.href=url;a.download=filename;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
}
export type {PageData};
