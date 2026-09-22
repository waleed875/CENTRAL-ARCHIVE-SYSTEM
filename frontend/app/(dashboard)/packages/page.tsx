import { ResourcePage } from "@/components/resource-page";
export default function Page() { return <ResourcePage title="حزم الفروع" endpoint="packages.list" query={{ page: 1, size: 20 }} />; }
