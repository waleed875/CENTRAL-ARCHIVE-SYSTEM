import { notFound } from "next/navigation";
import { QueryForm } from "@/components/query-form";
import { queryByKey } from "@/lib/api/query-definitions";

export default async function QueryPage({ params, searchParams }: { params: Promise<{ key: string }>; searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const { key } = await params;
  const definition = queryByKey.get(key);
  if (!definition) notFound();
  const search = await searchParams;
  const initialParams: Record<string, string> = {};
  for (const name of [...definition.pathParams, ...definition.queryParams]) {
    if (typeof search[name] === "string") initialParams[name] = search[name];
  }
  return <QueryForm definition={definition} initialParams={initialParams} />;
}
