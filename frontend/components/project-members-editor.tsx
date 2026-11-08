"use client";
import type { ProjectMemberDraft } from "@/lib/types";

const ROLES=["باحث","مهندس","مبرمج","مصمم","منفذ","فني","مدقق","محلل","مشارك","أخرى"];
export function ProjectMembersEditor({members,onChange}:{members:ProjectMemberDraft[];onChange:(value:ProjectMemberDraft[])=>void}){
 function edit(index:number,field:keyof ProjectMemberDraft,value:string){
  onChange(members.map((item,i)=>i===index?{...item,[field]:value}:item));
 }
 return <section className="project-members-editor">
  <div className="project-members-heading"><div><strong>فريق العمل والمساهمون</strong>
    <p className="hint">الأشخاص الذين أنجزوا المشروع، بالإضافة إلى المشرف. يمكنك إضافة أكثر من شخص.</p></div>
    <button type="button" className="btn btn-outline btn-sm" onClick={()=>onChange([...members,
      {personName:"",organizationName:"",roleName:"",notes:""}])}>+ إضافة شخص</button>
  </div>
  {!members.length&&<p className="hint">لم تضف أسماء المشاركين بعد.</p>}
  {members.map((member,index)=><div className="project-member-fields" key={index}>
    <div className="project-member-fields-head"><strong>المساهم {index+1}</strong>
      <button type="button" className="btn btn-quiet btn-sm"
        onClick={()=>onChange(members.filter((_,i)=>i!==index))}>إزالة</button></div>
    <div className="form-grid">
      <label className="field"><span>الاسم الكامل *</span><input required maxLength={200}
        value={member.personName} onChange={e=>edit(index,"personName",e.target.value)}/></label>
      <label className="field"><span>الجهة التي يعمل بها</span><input maxLength={200}
        value={member.organizationName} onChange={e=>edit(index,"organizationName",e.target.value)}/></label>
      <label className="field"><span>الدور في المشروع</span><input maxLength={120}
        list="member-role-choices" placeholder="مبرمج، مهندس، فني، مدقق..."
        value={member.roleName} onChange={e=>edit(index,"roleName",e.target.value)}/></label>
      <label className="field"><span>ملاحظات</span><input maxLength={2000}
        value={member.notes} onChange={e=>edit(index,"notes",e.target.value)}/></label>
    </div>
  </div>)}
  <datalist id="member-role-choices">{ROLES.map(role=><option value={role} key={role}/>)}</datalist>
 </section>;
}
export function toMemberCreatePayload(members:ProjectMemberDraft[]){
 return members.map(m=>({person_name:m.personName.trim(),
   organization_name:m.organizationName.trim()||null,
   role_name:m.roleName.trim()||null,notes:m.notes.trim()||null}));
}
