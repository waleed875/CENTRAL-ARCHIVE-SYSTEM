"use client";
import {useState,FormEvent} from "react";
import {useRouter} from "next/navigation";
import {api,jsonBody} from "@/lib/api";
import {useRemote} from "@/components/hooks";
import {PageHeader,Panel,Loading,Alert,Field} from "@/components/ui";
import type {UserInfo} from "@/lib/types";

export function AccountSettings(){
 const router=useRouter();
 const current=useRemote<UserInfo>("/api/v1/auth/me");
 const [name,setName]=useState<string|null>(null);
 const [oldPassword,setOldPassword]=useState(""),[newPassword,setNewPassword]=useState(""),[confirmPassword,setConfirmPassword]=useState("");
 const [busy,setBusy]=useState(false),[error,setError]=useState(""),[success,setSuccess]=useState("");
 async function updateName(event:FormEvent){
  event.preventDefault();setError("");setSuccess("");setBusy(true);
  try{
   await api("/api/v1/auth/me",{method:"PATCH",...jsonBody({fullName:name??current.data?.fullName??""})});
   setSuccess("تم تعديل الاسم");current.reload();
  }catch(e){setError(e instanceof Error?e.message:"تعذر تعديل الاسم")}finally{setBusy(false)}
 }
 async function updatePassword(event:FormEvent){
  event.preventDefault();setError("");setSuccess("");
  if(newPassword!==confirmPassword){setError("كلمتا المرور غير متطابقتين");return}
  setBusy(true);
  try{
   await api("/api/v1/auth/change-password",{method:"PATCH",...jsonBody({currentPassword:oldPassword,newPassword})});
   setOldPassword("");setNewPassword("");setConfirmPassword("");
   await fetch("/api/session/logout",{method:"POST"});
   router.replace("/login");router.refresh();
  }catch(e){setError(e instanceof Error?e.message:"تعذر تغيير كلمة المرور")}finally{setBusy(false)}
 }
 return <><PageHeader title="إعدادات حسابي" eyebrow="الملف الشخصي" description="تعديل الاسم وكلمة المرور. مستوى السرية والصلاحيات يديرهما مدير النظام فقط."/>
 {current.loading?<Loading/>:<>
 <Panel title="معلومات الحساب">
 <p>اسم الدخول: <strong>{current.data?.username??"—"}</strong></p>
 <p>الجهة: {current.data?.organization??"—"}</p>
 <p>مستوى السرية: {current.data?.maxConfidentialityLevelId??"غير محدد"} (يعدّله مدير النظام)</p>
 <form className="form-stack" onSubmit={updateName}>
  <Field label="الاسم الظاهر"><input required maxLength={200} value={name??current.data?.fullName??""} onChange={e=>setName(e.target.value)}/></Field>
  <div className="form-actions"><button disabled={busy} className="btn btn-gold">حفظ الاسم</button></div>
 </form></Panel>
 <Panel title="تغيير كلمة المرور" subtitle="يتطلب كلمة المرور الحالية. بعد الحفظ ستنتهي جميع الجلسات السابقة.">
 <form className="form-stack" onSubmit={updatePassword}>
  <Field label="كلمة المرور الحالية"><input type="password" autoComplete="current-password" required value={oldPassword} onChange={e=>setOldPassword(e.target.value)}/></Field>
  <Field label="كلمة المرور الجديدة"><input type="password" autoComplete="new-password" required minLength={8} value={newPassword} onChange={e=>setNewPassword(e.target.value)}/></Field>
  <Field label="تأكيد كلمة المرور الجديدة"><input type="password" autoComplete="new-password" required value={confirmPassword} onChange={e=>setConfirmPassword(e.target.value)}/></Field>
  <div className="form-actions"><button disabled={busy} className="btn btn-gold">تغيير كلمة المرور</button></div>
 </form></Panel></>}
 {current.error&&<Alert message={current.error}/>}
 {error&&<Alert message={error}/>}
 {success&&<Alert kind="success" message={success}/>}
 </>;
}
