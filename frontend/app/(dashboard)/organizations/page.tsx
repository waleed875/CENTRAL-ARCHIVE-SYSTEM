import { ResourcePage } from "@/components/resource-page";
export default function Page() { return <ResourcePage title="المؤسسات" endpoint="organizations.list" query={{ page: 1, size: 20 }} />; }
