import { ResourcePage } from "@/components/resource-page";
export default function Page() { return <ResourcePage title="المشاريع" endpoint="projects.list" query={{ page: 1, size: 20 }} />; }
