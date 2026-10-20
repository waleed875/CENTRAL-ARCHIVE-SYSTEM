import { ResourcePage } from "@/components/resource-page";
export default function Page() { return <ResourcePage title="المستخدمون" endpoint="users.list" query={{ page: 1, limit: 20 }} createKey="users.create" detailGroup="users" />; }
