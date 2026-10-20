import { ResourcePage } from "@/components/resource-page";
export default function Page() { return <ResourcePage title="الأدوار" endpoint="roles.list" query={{ page: 1, limit: 20 }} createKey="roles.create" detailGroup="roles" />; }
