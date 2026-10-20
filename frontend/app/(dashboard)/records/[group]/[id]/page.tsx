import { notFound } from "next/navigation";
import { endpointByKey } from "@/lib/api/catalog";
import { RecordDetail } from "@/components/record-detail";

export default async function RecordPage({ params }: { params: Promise<{ group: string; id: string }> }) {
  const { group, id } = await params;
  if (!endpointByKey.has(`${group}.get`)) notFound();
  return <RecordDetail group={group} id={id} />;
}
