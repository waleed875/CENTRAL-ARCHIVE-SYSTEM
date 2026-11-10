"use client";
import {useEffect,useState,FormEvent}from"react";import {Building2,Plus,Search,Edit3,Layers3,ChevronLeft,MapPin,Trash2}from"lucide-react";
import {api,jsonBody,qs}from"@/lib/api";import {useRemote}from"@/components/hooks";
import {PageHeader,Panel,Status,Empty,Loading,Alert,Modal,Field,Pagination}from"@/components/ui";
import type{Organization,PageData,UserInfo}from"@/lib/types";import{statusName}from"@/lib/types";
const typeOptions=[["COMPANY","مؤسسة / جهة رئيسية"],["FACTORY","مصنع"],["LINE","خط إنتاج"],["DEPARTMENT","قسم"]];
type OrgForm={name:string;code:string;type:string;parentId:string;description:string};
const initial:OrgForm={name:"",code:"",type:"COMPANY",parentId:"",description:""};
export function Organizations(){
 const [search,setSearch]=useState(""),[type,setType]=useState(""),[page,setPage]=useState(1),[editing,setEditing]=useState<Organization|null|undefined>(undefined),[form,setForm]=useState<OrgForm>(initial),[error,setError]=useState(""),[success,setSuccess]=useState(""),[saving,setSaving]=useState(false);
 const result=useRemote<PageData<Organization>>("/api/v1/organizations"+qs({page,size:15,search,type}));
 const all=useRemote<Organization[]>("/api/v1/organizations/all");
 const options=all.data??[];
 const current=useRemote<UserInfo>("/api/v1/auth/me");const isAdmin=!!current.data?.roles?.includes("system_admin");
 const parents=options.filter(o=>o.isActive&&(form.type==="FACTORY"?o.type==="COMPANY":form.type==="LINE"?o.type==="FACTORY":form.type==="DEPARTMENT"?o.type==="LINE":false));
 function startEdit(o?:Organization){setForm(o?{name:o.name,code:o.code,type:o.type,parentId:o.parentId??"",description:o.description??""}:initial);setEditing(o??null);setError("");setSuccess("")}
 async function deactivate(o:Organization){if(!window.confirm("سيتم تعطيل الجهة «"+o.name+"». لن تُحذف سجلاتها، وقد يُرفض الإجراء إذا كانت لها جهات فرعية أو مشاريع نشطة. هل تريد المتابعة؟"))return;setSaving(true);setError("");try{await api("/api/v1/organizations/"+o.id,{method:"DELETE"});setSuccess("تم تعطيل الجهة");result.reload();all.reload()}catch(e){setError(e instanceof Error?e.message:"تعذر تعطيل الجهة")}finally{setSaving(false)}}
 async function submit(e:FormEvent){e.preventDefault();setSaving(true);setError("");try{
 const body={name:form.name,code:form.code,type:form.type,parentId:form.type==="COMPANY"?null:form.parentId||null,description:form.description};
 if(!editing&&form.type!=="COMPANY"&&!form.parentId)throw new Error("اختر الجهة الأم");
 if(editing){await api("/api/v1/organizations/"+editing.id,{method:"PATCH",...jsonBody({name:form.name,code:form.code,description:form.description})})}
 else await api("/api/v1/organizations",{method:"POST",...jsonBody(body)});
 setEditing(undefined);setSuccess(editing?"تم تحديث بيانات الجهة بنجاح":"تم إنشاء الجهة بنجاح");result.reload();all.reload();
 }catch(err){setError(err instanceof Error?err.message:"تعذر حفظ الجهة")}finally{setSaving(false)}}
 return <><PageHeader eyebrow="الهيكل المؤسسي" title="الجهات والمؤسسات" description="إدارة الهيكل التنظيمي للجهات المرتبطة بمشاريع الأرشيف." action={isAdmin&&<button className="btn btn-primary" onClick={()=>startEdit()}><Plus size={17}/> إضافة جهة جديدة</button>}/>{success&&<Alert message={success} kind="success" onClose={()=>setSuccess("")}/>}
 <Panel title="دليل الجهات" subtitle="مؤسسات ومصانع وخطوط وأقسام ضمن تسلسل إداري موحّد"><div className="toolbar"><input value={search} onChange={e=>{setSearch(e.target.value);setPage(1)}} placeholder="ابحث بالاسم أو الرمز..." aria-label="بحث الجهات"/><select value={type} onChange={e=>{setType(e.target.value);setPage(1)}} aria-label="تصفية بنوع الجهة"><option value="">كل الأنواع</option>{typeOptions.map(([value,label])=><option value={value} key={value}>{label}</option>)}</select></div>
 {result.error&&<Alert message={result.error}/>}
 {result.loading?<Loading/>:!result.data?.data?.length?<Empty title="لا توجد جهات" description="أضف جهة رئيسية ثم اربط بها الأقسام والجهات الفرعية."/>:<div className="table-scroller"><table className="data-table"><thead><tr><th>الجهة</th><th>النوع</th><th>عدد المشاريع</th><th>الجهات الفرعية</th><th>الحالة</th><th>الإجراءات</th></tr></thead><tbody>{result.data.data.map(o=><tr key={o.id}><td><strong className="table-link">{o.name}</strong><span className="table-sub">{o.code}</span></td><td>{statusName(o.type)}</td><td>{o.projectsCount}</td><td>{o.childrenCount}</td><td><Status value={o.isActive?"ACTIVE":"INACTIVE"}/></td><td>{isAdmin&&<div className="chips"><button className="btn btn-quiet btn-sm" onClick={()=>startEdit(o)}><Edit3 size={14}/> تعديل</button>{o.isActive&&<button className="btn btn-danger btn-sm" disabled={saving} onClick={()=>deactivate(o)}><Trash2 size={14}/> تعطيل</button>}</div>}</td></tr>)}</tbody></table></div>}
 <Pagination page={page} totalPages={result.data?.totalPages??0} onChange={setPage}/></Panel>
 {editing!==undefined&&<Modal title={editing?"تعديل بيانات الجهة":"إنشاء جهة جديدة"} subtitle="أدخل البيانات الأساسية للجهة واحفظها في السجل المؤسسي." onClose={()=>setEditing(undefined)}><form onSubmit={submit} className="form-stack">
 <Field label="اسم الجهة *"><input required maxLength={200} value={form.name} onChange={e=>setForm({...form,name:e.target.value})} placeholder="مثال: الإدارة العامة للأرشيف"/></Field>
 <div className="form-grid"><Field label="رمز الجهة *"><input required maxLength={60} value={form.code} onChange={e=>setForm({...form,code:e.target.value})} placeholder="ORG-001"/></Field><Field label="نوع الجهة *"><select disabled={!!editing} value={form.type} onChange={e=>setForm({...form,type:e.target.value,parentId:""})}>{typeOptions.map(([v,label])=><option key={v} value={v}>{label}</option>)}</select></Field></div>
 {form.type!=="COMPANY"&&!editing&&<Field label="الجهة الأم *" hint="تختلف الجهات المتاحة حسب نوع الجهة الجديدة."><select required value={form.parentId} onChange={e=>setForm({...form,parentId:e.target.value})}><option value="">اختر الجهة الأم</option>{parents.map(o=><option key={o.id} value={o.id}>{o.name} ({o.code})</option>)}</select></Field>}
 <Field label="وصف الجهة"><textarea value={form.description} onChange={e=>setForm({...form,description:e.target.value})} placeholder="وصف مختصر للجهة ومسؤولياتها"/></Field>
 {error&&<Alert message={error}/>}<div className="form-actions"><button disabled={saving} className="btn btn-gold" type="submit">{saving?"جارٍ الحفظ...":"حفظ البيانات"}</button><button className="btn btn-quiet" type="button" onClick={()=>setEditing(undefined)}>إلغاء</button></div></form></Modal>}
 </>;
}
