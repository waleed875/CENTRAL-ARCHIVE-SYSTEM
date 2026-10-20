import { notFound } from "next/navigation";
import { OperationForm } from "@/components/operation-form";
import { operationByKey } from "@/lib/api/operation-definitions";

export default async function OperationPage({ params, searchParams }: { params: Promise<{ key: string }>; searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const { key } = await params;
  const operation = operationByKey.get(key);
  if (!operation) notFound();
  const query = await searchParams;
  const initialParams: Record<string, string> = {};
  for (const name of operation.pathParams) {
    const value = query[name];
    if (typeof value === "string") initialParams[name] = value;
  }
  return <OperationForm operation={operation} initialParams={initialParams} />;
}
