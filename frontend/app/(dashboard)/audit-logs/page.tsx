import { ResourcePage } from "@/components/resource-page";
export default function Page() { return <ResourcePage title="سجل التدقيق" endpoint="audit.list" query={{ page: 1, size: 20 }} />; }
