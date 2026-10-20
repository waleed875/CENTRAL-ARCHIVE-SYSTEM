export type MemberInput = { personName: string; organizationName: string; roleName: string; notes: string };

export function memberPayload(member: MemberInput) {
  return {
    person_name: member.personName.trim(),
    organization_name: member.organizationName.trim(),
    role_name: member.roleName.trim(),
    notes: member.notes.trim() || null,
  };
}

export function appendJsonPart(form: FormData, name: string, value: unknown) {
  form.append(name, new Blob([JSON.stringify(value)], { type: "application/json" }), `${name}.json`);
}
