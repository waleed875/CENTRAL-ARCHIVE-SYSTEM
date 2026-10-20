import { ResourcePage } from "@/components/resource-page";

export default function Page() {
  return <ResourcePage title="حزم التصدير" endpoint="exportPackages.list" createKey="exportPackages.create" detailGroup="exportPackages" />;
}
