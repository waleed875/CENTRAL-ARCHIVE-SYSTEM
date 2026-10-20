// Collects the exact responses a frontend developer will code against, so the handoff quotes what
// the server actually sends rather than what its source suggests. Everything it creates it removes.

const ORIGIN = "http://localhost:8080";
const BASE = `${ORIGIN}/api/v1`;

const token = (
  await (
    await fetch(`${BASE}/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "admin", password: "admin123" }),
    })
  ).json()
).data.token;

const call = async (method, path, payload) => {
  const init = { method, headers: { Authorization: `Bearer ${token}` } };
  if (payload !== undefined) {
    init.headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(payload);
  }
  const response = await fetch(`${BASE}${path}`, init);
  const text = await response.text();
  let body;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = { raw: text };
  }
  return { status: response.status, body };
};

const show = (label, result) =>
  console.log(`\n--- ${label}  [${result.status}]\n${JSON.stringify(result.body, null, 2)}`);

console.log("=== 1. the enum catalog: names only, or names and Arabic? ===");
{
  const enums = await call("GET", "/enums");
  const values = enums.body?.data ?? {};
  console.log(`  keys: ${Object.keys(values).join(", ")}`);
  console.log(`  projectStatus: ${JSON.stringify(values.projectStatus)}`);
  console.log(`  any label alongside a value? ${JSON.stringify(values).includes("label") ? "yes" : "no"}`);
}

console.log("\n=== 2. the delete, end to end, with the refusals verbatim ===");
// The project number is unique, so a fixed one would make a second run fail on the create rather than
// on anything this script is checking. Suffixing it keeps the script re-runnable.
const stamp = Date.now().toString(36).slice(-6).toUpperCase();
const organizationId = (await call("GET", "/organizations/all")).body.data[0].id;
const created = await call("POST", "/projects", {
  name: "مشروع للتسليم",
  organizationId,
  projectNumber: `HANDOFF-${stamp}`,
  year: 2026,
});
if (created.status !== 201) {
  console.log(`  could not create the project to delete: [${created.status}]`);
  console.log(JSON.stringify(created.body, null, 2));
  process.exit(1);
}
const id = created.body.data.id;
console.log(`  created ${created.body.data.projectNumber} = ${id} (${created.body.data.status})`);

const member = await call("POST", `/projects/${id}/members`, {
  person_name: "أ.ب",
  role_name: "مدخل بيانات",
});
console.log(`  added a member: [${member.status}] (snake_case)`);
show("refusal: a draft that has members", await call("DELETE", `/projects/${id}`));

await call("DELETE", `/projects/${id}/members/${member.body.data.id}`);
show("the delete itself", await call("DELETE", `/projects/${id}`));
show("reading it afterwards", await call("GET", `/projects/${id}`));

console.log("\n=== 3. refusals the UI must be able to show ===");
{
  const approved = (await call("GET", "/projects?status=APPROVED&size=1")).body.data.data[0];
  show(`a project that is ${approved.status}`, await call("DELETE", `/projects/${approved.id}`));
  show("an identifier that does not exist", await call("DELETE", "/projects/00000000-0000-0000-0000-000000000000"));
}

console.log("\n=== 4. what the audit log shows afterwards (admin only) ===");
{
  const logs = await call("GET", `/audit-logs?entityType=Project&entityId=${id}&size=10`);
  const rows = logs.body?.data?.data ?? [];
  for (const row of rows) {
    console.log(`  ${row.action.padEnd(7)} ${row.createdAt}  ${row.description}`);
  }
  console.log(`  a dedicated status field on the row: ${rows.some((r) => r.status || r.outcome) ? "yes" : "no — it lives inside description"}`);
}