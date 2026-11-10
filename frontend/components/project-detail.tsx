"use client";
import {useState,FormEvent}from"react";import Link from"next/link";import {useRouter}from"next/navigation";import {ArrowRight,Edit3,UploadCloud,Download,Files,FolderKanban,Clock3,ClipboardCheck,CheckCheck,Archive,RotateCcw,Trash2,Plus,FileText,GitBranch,LockKeyhole}from"lucide-react";
import {api,jsonBody,download,ApiError}from"@/lib/api";import{useRemote}from"@/components/hooks";import{PageHeader,Panel,Status,Alert,Empty,Loading,Field,Modal}from"@/components/ui";
import type{Project,ArchiveFile,PageData,FileCategory,FileType,UserInfo,Organization,Reference,ProjectMember}from"@/lib/types";
import {canWrite,statusName,dateLabel,sizeLabel}from"@/lib/types";
type Mode="edit"|"upload"|"correction"|"reject"|"file-edit"|"member"|null;
export function ProjectDetail({id}:{id:string}){
 const router=useRouter();
 const project=useRemote<Project>("/api/v1/projects/"+id),members=useRemote<ProjectMember[]>("/api/v1/projects/"+id+"/members"),files=useRemote<PageData<ArchiveFile>>("/api/v1/projects/"+id+"/files?page=1&size=100"),versions=useRemote<Project[]>("/api/v1/projects/"+id+"/versions");
 const user=useRemote<UserInfo>("/api/v1/auth/me"),orgs=useRemote<Organization[]>("/api/v1/organizations/all"),types=useRemote<Reference[]>("/api/v1/project-types/all"),levels=useRemote<Reference[]>("/api/v1/confidentiality-levels/all");
 const cats=useRemote<FileCategory[]>("/api/v1/file-categories/all?isActive=true");
 const [mode,setMode]=useState<Mode>(null),[saving,setSaving]=useState(false),[error,setError]=useState(""),[success,setSuccess]=useState(""),[reason,setReason]=useState("");
 const [newMember,setNewMember]=useState({personName:"",organizationName:"",roleName:"",notes:""});
 const [edit,setEdit]=useState<Record<string,string>>({}),[uploadFile,setUploadFile]=useState<File|null>(null),[categoryId,setCategoryId]=useState(""),[typeId,setTypeId]=useState(""),[levelId,setLevelId]=useState(""),[selectedFile,setSelectedFile]=useState<ArchiveFile|null>(null);
 const fileTypes=useRemote<FileType[]>(categoryId?"/api/v1/file-types/by-category/"+categoryId+"?isActive=true":null);
 const p=project.data;
 const roles=user.data?.roles??[],isEditor=canWrite(roles,"central_data_entry"),isReviewer=canWrite(roles,"auditor"),isArchiver=canWrite(roles,"archive_officer"),isAdmin=roles.includes("system_admin");
 const canEdit=p&&isEditor&&!["APPROVED","ARCHIVED","REJECTED"].includes(p.status);
 const canUpload=p&&isEditor&&["DRAFT","IMPORTED","NEEDS_CORRECTION"].includes(p.status);
 async function addMember(e:FormEvent){
  e.preventDefault();setSaving(true);setError("");
  try{
   await api("/api/v1/projects/"+id+"/members",{method:"POST",...jsonBody({
    person_name:newMember.personName.trim(),
    organization_name:newMember.organizationName.trim()||null,
    role_name:newMember.roleName.trim()||null,
    notes:newMember.notes.trim()||null
   })});
   members.reload();setMode(null);
   setNewMember({personName:"",organizationName:"",roleName:"",notes:""});
   setSuccess("تمت إضافة المساهم");
  }catch(e){setError(e instanceof Error?e.message:"تعذر إضافة المساهم")}
  finally{setSaving(false)}
 }
 async function removeMember(member:ProjectMember){
  if(!window.confirm("إزالة "+member.personName+" من فريق المشروع؟"))return;
  setError("");setSaving(true);
  try{
   await api("/api/v1/projects/"+id+"/members/"+member.id,{method:"DELETE"});
   members.reload();setSuccess("تم تحديث قائمة المساهمين");
  }catch(e){setError(e instanceof Error?e.message:"تعذر إزالة المساهم")}
  finally{setSaving(false)}
 }
 function openEdit(){if(!p)return;setEdit({name:p.name,projectNumber:p.projectNumber,projectCode:p.projectCode??"",year:String(p.year),organizationId:p.organizationId,projectTypeId:p.projectTypeId??"",confidentialityLevelId:p.confidentialityLevelId??"",executingEntity:p.executingEntity??"",supervisorName:p.supervisorName??"",description:p.description??"",notes:p.notes??"",keywords:p.keywords??"",dataCompletionStatus:p.dataCompletionStatus});setError("");setMode("edit")}
 async function perform(path:string,body?:unknown){setError("");setSaving(true);try{await api("/api/v1/projects/"+id+path,{method:"PATCH",...(body?jsonBody(body):{})});setSuccess("تم تنفيذ الإجراء بنجاح");setMode(null);project.reload();versions.reload()}catch(e){setError(e instanceof Error?e.message:"تعذر تنفيذ الإجراء")}finally{setSaving(false)}}
 async function saveEdit(e:FormEvent){e.preventDefault();const body={...edit,year:Number(edit.year),projectTypeId:edit.projectTypeId||null,confidentialityLevelId:edit.confidentialityLevelId||null};await perform("",body)}
 async function sendFile(e:FormEvent){e.preventDefault();if(!uploadFile){setError("اختر ملفًا للرفع");return}if(!categoryId){setError("اختر فئة الملف");return}setSaving(true);setError("");try{
  const form=new FormData();form.append("file",uploadFile);form.append("metadata",new Blob([JSON.stringify({categoryId,typeId:typeId||null,confidentialityLevelId:levelId||null})],{type:"application/json"}));
  await api("/api/v1/projects/"+id+"/files/upload",{method:"POST",body:form});setSuccess("تم رفع الملف وربطه بالمشروع");setMode(null);setUploadFile(null);files.reload();
 }catch(e){setError(e instanceof Error?e.message:"تعذر رفع الملف")}finally{setSaving(false)}}
 async function saveFileMeta(e:FormEvent){e.preventDefault();if(!selectedFile)return;setSaving(true);setError("");try{await api("/api/v1/files/"+selectedFile.id,{method:"PATCH",...jsonBody({categoryId,typeId:typeId||null,confidentialityLevelId:levelId||null})});setMode(null);files.reload();setSuccess("تم تحديث بيانات الملف")}catch(e){setError(e instanceof Error?e.message:"تعذر تعديل الملف")}finally{setSaving(false)}}
 async function deleteFile(file:ArchiveFile){if(!window.confirm("هل تريد حذف الملف من المشروع؟ يمكن للمدير استعادته لاحقًا."))return;setError("");try{await api("/api/v1/files/"+file.id,{method:"DELETE"});files.reload();setSuccess("تم حذف الملف منطقيًا")}catch(e){setError(e instanceof Error?e.message:"تعذر حذف الملف")}}
 async function createVersion(){if(!window.confirm("إنشاء إصدار جديد من المشروع المعتمد؟"))return;setSaving(true);try{const result=await api<Project>("/api/v1/projects/"+id+"/new-version",{method:"POST",...jsonBody({})});router.push("/projects/"+result.id)}catch(e){setError(e instanceof Error?e.message:"تعذر إنشاء الإصدار")}finally{setSaving(false)}}
 async function deleteProject(){if(!window.confirm("حذف هذه المسودة نهائيًا؟ لا يمكن التراجع عن ذلك."))return;setSaving(true);try{await api("/api/v1/projects/"+id,{method:"DELETE"});router.push("/projects")}catch(e){setError(e instanceof Error?e.message:"تعذر حذف المشروع")}finally{setSaving(false)}}
 if(project.loading)return <Loading/>;
 if(project.error)return <><Link className="btn btn-quiet" href="/projects"><ArrowRight size={16}/> العودة للمشاريع</Link><div className="section-gap"><Alert message={project.error}/></div></>;
 if(!p)return <Empty title="المشروع غير موجود"/>;
 return <><div className="detail-header"><Link href="/projects" className="eyebrow" style={{display:"inline-flex",gap:6,alignItems:"center"}}><ArrowRight size={15}/> العودة إلى المشاريع</Link>
 <div className="detail-heading"><div><h1>{p.name}</h1><div className="detail-meta"><span>{p.projectNumber}</span><span>سنة {p.year}</span><span>الإصدار {p.versionNo??1}</span><Status value={p.status}/></div></div><div className="detail-actions">{canEdit&&<button onClick={openEdit} className="btn btn-outline"><Edit3 size={16}/> تعديل البيانات</button>}{canUpload&&<button onClick={()=>{setCategoryId("");setTypeId("");setLevelId("");setError("");setMode("upload")}} className="btn btn-outline"><UploadCloud size={16}/> رفع ملف</button>}{isEditor&&["DRAFT","IMPORTED","NEEDS_CORRECTION"].includes(p.status)&&<button disabled={saving} onClick={()=>perform("/submit-for-review")} className="btn btn-gold"><ClipboardCheck size={16}/> إرسال للمراجعة</button>}{isEditor&&["APPROVED","ARCHIVED","REJECTED"].includes(p.status)&&p.isLatestVersion&&<button onClick={createVersion} disabled={saving} className="btn btn-gold"><GitBranch size={16}/> إصدار جديد</button>}{isEditor&&p.status==="REJECTED"&&p.isLatestVersion&&<button onClick={()=>perform("/reopen")} disabled={saving} className="btn btn-outline"><RotateCcw size={16}/> إعادة فتح للتصحيح</button>}</div></div></div>
 {success&&<Alert kind="success" message={success} onClose={()=>setSuccess("")}/>}
 {error&&<Alert message={error} onClose={()=>setError("")}/>}
 <div className="detail-grid"><div><Panel title="مسار المشروع" subtitle="مراحل اعتماد الوثائق والتحقق من جاهزية الأرشفة"><div className="steps">{[["DRAFT","مسودة"],["UNDER_REVIEW","قيد المراجعة"],["APPROVED","معتمد"]].map(([status,label],index)=>{let reached=["DRAFT","UNDER_REVIEW","APPROVED"].indexOf(p.status);if(p.status==="ARCHIVED")reached=2;if(p.status==="NEEDS_CORRECTION"||p.status==="REJECTED")reached=1;return <div className={"step "+(index<reached?"done":index===reached?"current":"")} key={status}><span>{index<reached?"✓":index+1}</span><strong>{label}</strong></div>})}</div></Panel>
 <div className="section-gap"><Panel title="الملفات المرتبطة" subtitle="جميع الوثائق محفوظة تحت المشروع وصلاحياته" action={canUpload&&<button className="btn btn-outline btn-sm" onClick={()=>{setCategoryId("");setTypeId("");setError("");setMode("upload")}}><Plus size={15}/> رفع ملف</button>}>
 {files.error&&<Alert message={files.error}/>}
 {files.loading?<Loading/>:!files.data?.data?.length?<Empty title="لا توجد ملفات مرفوعة" description="يمكنك إضافة أول وثيقة إلى المشروع من زر رفع ملف."/>:<div className="table-scroller"><table className="data-table"><thead><tr><th>الملف</th><th>الفئة</th><th>الحجم</th><th>تاريخ الرفع</th><th>الإجراء</th></tr></thead><tbody>{files.data.data.map(f=><tr key={f.id}><td><div className="file-name"><span className="file-icon"><FileText size={17}/></span><strong>{f.originalName}</strong></div></td><td>{f.categoryName??"—"}</td><td>{sizeLabel(f.sizeBytes)}</td><td>{dateLabel(f.createdAt)}</td><td><div className="chips"><button title="تنزيل" className="btn btn-quiet btn-sm" onClick={()=>download("/api/v1/files/"+f.id+"/download",f.originalName).catch(e=>setError(e.message))}><Download size={15}/></button>{canUpload&&<button title="تعديل بيانات الملف" className="btn btn-quiet btn-sm" onClick={()=>{setSelectedFile(f);setCategoryId(f.categoryId);setTypeId(f.typeId??"");setLevelId(f.confidentialityLevelId??"");setMode("file-edit")}}><Edit3 size={15}/></button>}{canUpload&&<button title="حذف الملف" className="btn btn-danger btn-sm" onClick={()=>deleteFile(f)}><Trash2 size={15}/></button>}</div></td></tr>)}</tbody></table></div>}</Panel></div>
 <div className="section-gap"><Panel title="فريق العمل والمساهمون" subtitle="الأشخاص الذين شاركوا في الإنجاز، إضافة إلى المشرف"
  action={canUpload&&<button type="button" className="btn btn-outline btn-sm"
    onClick={()=>{setError("");setNewMember({personName:"",organizationName:"",roleName:"",notes:""});setMode("member")}}>
    <Plus size={15}/> إضافة شخص</button>}>
  {members.error&&<Alert message={members.error}/>}
  {members.loading?<Loading/>:!members.data?.length?
    <Empty title="لا يوجد مساهمون مسجلون" description="وثّق أسماء المشاركين وأدوارهم."/>:
    <div className="project-members-list">{members.data.map(member=>
      <div key={member.id} className="project-member-item"><div>
        <strong>{member.personName}</strong>
        <p className="hint">{[member.roleName,member.organizationName].filter(Boolean).join(" · ")||"عضو في المشروع"}</p>
        {member.notes&&<p className="hint">{member.notes}</p>}
      </div>
      {canUpload&&<button type="button" className="btn btn-quiet btn-sm"
        onClick={()=>removeMember(member)} disabled={saving}>إزالة</button>}
      </div>)}</div>}
 </Panel></div>
 <div className="section-gap"><Panel title="بيانات المشروع" subtitle="معلومات المشروع الأساسية والتوثيقية"><div className="key-grid">{[["رقم المشروع",p.projectNumber],["السنة",p.year],["الحالة",statusName(p.status)],["اكتمال البيانات",statusName(p.dataCompletionStatus)],["الجهة المنفذة",p.executingEntity],["المشرف",p.supervisorName],["الكلمات المفتاحية",p.keywords],["تاريخ الإنشاء",dateLabel(p.createdAt)]].map(([label,value])=><div className="key-value" key={String(label)}><span>{label}</span><strong>{String(value??"—")}</strong></div>)}</div>{p.description&&<div className="section-gap"><div className="meta-label">وصف المشروع</div><p style={{lineHeight:1.9}}>{p.description}</p></div>}{p.notes&&<div className="section-gap"><div className="meta-label">ملاحظات</div><p style={{lineHeight:1.9}}>{p.notes}</p></div>}</Panel></div>
 </div><aside><Panel title="حالة المراجعة" subtitle="القرار الأخير والملاحظات المتعلقة بالمشروع"><div className="form-stack"><div><span className="meta-label">الحالة الحالية</span><div style={{marginTop:10}}><Status value={p.status}/></div></div>{p.reviewNotes&&<div className="review-note"><strong>ملاحظات المراجع</strong><p>{p.reviewNotes}</p></div>}{p.rejectionReason&&<div className="review-note"><strong>سبب الرفض</strong><p>{p.rejectionReason}</p></div>}{p.submittedAt&&<div className="hint">أُرسل للمراجعة: {dateLabel(p.submittedAt)}</div>}
 {p.status==="UNDER_REVIEW"&&isReviewer&&<><button disabled={saving} onClick={()=>perform("/approve")} className="btn btn-gold"><CheckCheck size={16}/> اعتماد المشروع</button><button disabled={saving} onClick={()=>{setReason("");setError("");setMode("correction")}} className="btn btn-outline"><RotateCcw size={16}/> طلب تصحيح</button><button disabled={saving} onClick={()=>{setReason("");setError("");setMode("reject")}} className="btn btn-danger">رفض المشروع</button></>}
 {p.status==="APPROVED"&&isArchiver&&<button disabled={saving} className="btn btn-gold" onClick={()=>perform("/archive")}><Archive size={16}/> أرشفة المشروع</button>}
 </div></Panel>
 <div className="section-gap"><Panel title="إصدارات المشروع" subtitle="سجل تطور المشروع عبر النسخ">{versions.loading?<Loading/>:versions.data?.length?<div className="activity-list">{versions.data.map(v=><Link href={"/projects/"+v.id} key={v.id} className="activity-item"><div className="activity-dot"><GitBranch size={16}/></div><div><strong>الإصدار {v.versionNo}</strong><p>{dateLabel(v.createdAt)} · {statusName(v.status)}</p></div></Link>)}</div>:<Empty title="لا توجد إصدارات إضافية"/>}</Panel></div>
 {p.status==="DRAFT"&&isEditor&&<div className="section-gap"><button className="btn btn-danger" onClick={deleteProject} disabled={saving}><Trash2 size={16}/> حذف المسودة</button></div>}
 </aside></div>
 {mode==="member"&&<Modal title="إضافة مساهم" subtitle="أسماء الأشخاص الذين أنجزوا المشروع، بشكل مستقل عن المشرف." onClose={()=>setMode(null)}>
   <form className="form-stack" onSubmit={addMember}>
    <Field label="اسم الشخص *"><input required maxLength={200} value={newMember.personName}
      onChange={e=>setNewMember(v=>({...v,personName:e.target.value}))}/></Field>
    <Field label="جهة المساهم"><input maxLength={200} value={newMember.organizationName}
      onChange={e=>setNewMember(v=>({...v,organizationName:e.target.value}))}/></Field>
    <Field label="دوره في المشروع"><input maxLength={120} value={newMember.roleName}
      placeholder="مبرمج، مهندس، فني، مدقق"
      onChange={e=>setNewMember(v=>({...v,roleName:e.target.value}))}/></Field>
    <Field label="ملاحظات"><textarea maxLength={2000} value={newMember.notes}
      onChange={e=>setNewMember(v=>({...v,notes:e.target.value}))}/></Field>
    {error&&<Alert message={error}/>}
    <div className="form-actions"><button className="btn btn-gold"
      disabled={saving||!newMember.personName.trim()}>حفظ المساهم</button>
      <button type="button" className="btn btn-quiet" onClick={()=>setMode(null)}>إلغاء</button>
    </div>
   </form>
 </Modal>}
 {mode==="edit"&&<Modal title="تعديل بيانات المشروع" subtitle="تُحفظ التعديلات وفق القيود الخاصة بحالة المشروع." onClose={()=>setMode(null)}><form className="form-stack" onSubmit={saveEdit}>
 <Field label="اسم المشروع"><input maxLength={250} required value={edit.name} onChange={e=>setEdit({...edit,name:e.target.value})}/></Field>
 <div className="form-grid"><Field label="رقم المشروع"><input required maxLength={50} value={edit.projectNumber} onChange={e=>setEdit({...edit,projectNumber:e.target.value})}/></Field><Field label="السنة"><input type="number" min={1900} max={2100} value={edit.year} onChange={e=>setEdit({...edit,year:e.target.value})}/></Field></div>
 <Field label="الجهة التابعة" hint="الجهة التي يتبع لها المشروع إداريًا."><select value={edit.organizationId} onChange={e=>setEdit({...edit,organizationId:e.target.value})}>{orgs.data?.map(o=><option value={o.id} key={o.id}>{o.name}</option>)}</select></Field>
 <div className="form-grid"><Field label="نوع المشروع"><select value={edit.projectTypeId} onChange={e=>setEdit({...edit,projectTypeId:e.target.value})}><option value="">بدون تحديد</option>{types.data?.map(v=><option key={v.id} value={v.id}>{v.name}</option>)}</select></Field><Field label="السرية"><select value={edit.confidentialityLevelId} onChange={e=>setEdit({...edit,confidentialityLevelId:e.target.value})}><option value="">بدون تحديد</option>{levels.data?.map(v=><option key={v.id} value={v.id}>{v.name}</option>)}</select></Field></div>
 <Field label="الجهة المنفذة" hint="الشركة أو الفريق الذي نفّذ العمل فعليًا."><input maxLength={200} value={edit.executingEntity} onChange={e=>setEdit({...edit,executingEntity:e.target.value})}/></Field>
 <Field label="المشرف"><input maxLength={200} value={edit.supervisorName} onChange={e=>setEdit({...edit,supervisorName:e.target.value})}/></Field>
 <Field label="الوصف"><textarea value={edit.description} onChange={e=>setEdit({...edit,description:e.target.value})}/></Field><Field label="الملاحظات"><textarea value={edit.notes} onChange={e=>setEdit({...edit,notes:e.target.value})}/></Field>
 <Field label="حالة اكتمال البيانات"><select value={edit.dataCompletionStatus} onChange={e=>setEdit({...edit,dataCompletionStatus:e.target.value})}><option value="INCOMPLETE">غير مكتمل</option><option value="COMPLETE">مكتمل</option></select></Field>
 {error&&<Alert message={error}/>}<div className="form-actions"><button disabled={saving} className="btn btn-gold">حفظ التعديلات</button><button type="button" className="btn btn-quiet" onClick={()=>setMode(null)}>إلغاء</button></div>
 </form></Modal>}
 {(mode==="upload"||mode==="file-edit")&&<Modal title={mode==="upload"?"رفع ملف للمشروع":"تعديل بيانات الملف"} subtitle="تتبع الوثائق مستويات السرية وفئات الملفات المعتمدة." onClose={()=>setMode(null)}><form className="form-stack" onSubmit={mode==="upload"?sendFile:saveFileMeta}>
 {mode==="upload"&&<label className="upload-box"><UploadCloud size={24}/><strong>{uploadFile?.name??"اختر الملف من جهازك"}</strong>
  <small>وثائق PDF/Office وصور HEIC/AVIF، وتسجيلات MP3/WAV/FLAC/M4A، وفيديو MKV/WebM/AVI، وأرشيفات ZIP/RAR/7Z/TAR/GZ.</small>
  <small>ملفات التشغيل EXE وDLL وMSI غير مسموحة؛ يتحقق الخادم أيضًا من المحتوى الفعلي.</small>
  <input type="file" required onChange={e=>{
    const file=e.target.files?.[0]??null;
    if(file&&/\.(exe|dll|msi|bat|cmd|ps1|sh|com|scr|jar)$/i.test(file.name)){
      setUploadFile(null);setError("ملفات التشغيل غير مسموحة في الأرشيف العادي. اختر وثيقة أو ملف وسائط أو أرشيفًا معتمدًا.");
      e.target.value="";return;
    }
    setError("");setUploadFile(file);
  }}/></label>}
 <Field label="فئة الملف *"><select required value={categoryId} onChange={e=>{setCategoryId(e.target.value);setTypeId("")}}><option value="">اختر الفئة</option>{cats.data?.map(c=><option key={c.id} value={c.id}>{c.name}</option>)}</select></Field>
 <Field label="نوع الملف"><select value={typeId} onChange={e=>setTypeId(e.target.value)}><option value="">دون تحديد</option>{fileTypes.data?.map(t=><option key={t.id} value={t.id}>{t.name}{t.defaultExtension?" (."+t.defaultExtension+")":""}</option>)}</select></Field>
 <Field label="مستوى السرية"><select value={levelId} onChange={e=>setLevelId(e.target.value)}><option value="">مستوى المشروع / دون تحديد</option>{levels.data?.map(v=><option key={v.id} value={v.id}>{v.name}</option>)}</select></Field>
 {error&&<Alert message={error}/>}<div className="form-actions"><button disabled={saving} className="btn btn-gold">{saving?"جارٍ التنفيذ...":mode==="upload"?"رفع الملف":"حفظ البيانات"}</button><button className="btn btn-quiet" type="button" onClick={()=>setMode(null)}>إلغاء</button></div></form></Modal>}
 {(mode==="correction"||mode==="reject")&&<Modal title={mode==="correction"?"طلب تصحيح المشروع":"رفض المشروع"} subtitle="اكتب ملاحظات واضحة ليطّلع عليها مسؤول إدخال البيانات." onClose={()=>setMode(null)}><form className="form-stack" onSubmit={e=>{e.preventDefault();perform(mode==="correction"?"/request-correction":"/reject",mode==="correction"?{review_notes:reason}:{rejection_reason:reason})}}><Field label={mode==="correction"?"ملاحظات التصحيح *":"سبب الرفض *"}><textarea required maxLength={5000} value={reason} onChange={e=>setReason(e.target.value)} placeholder="اكتب التفاصيل هنا..."/></Field>{error&&<Alert message={error}/>}<div className="form-actions"><button disabled={saving||!reason.trim()} className="btn btn-gold">تأكيد الإجراء</button><button type="button" className="btn btn-quiet" onClick={()=>setMode(null)}>إلغاء</button></div></form></Modal>}
 </>;
}
