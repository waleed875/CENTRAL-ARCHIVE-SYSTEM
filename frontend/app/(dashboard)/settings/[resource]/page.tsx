import { notFound } from "next/navigation";
import { ResourcePage } from "@/components/resource-page";

const resources: Record<string, { title: string; group: string }> = {
  "confidentiality-levels": { title: "مستويات السرية", group: "confidentialityLevels" },
  "project-types": { title: "أنواع المشاريع", group: "projectTypes" },
  "file-categories": { title: "فئات الملفات", group: "fileCategories" },
  "file-types": { title: "أنواع الملفات", group: "fileTypes" },
};

export default async function SettingsResourcePage({ params }: { params: Promise<{ resource: string }> }) {
  const { resource } = await params;
  const definition = resources[resource];
  if (!definition) notFound();
  return <ResourcePage title={definition.title} endpoint={`${definition.group}.list`} createKey={`${definition.group}.create`} detailGroup={definition.group} />;
}
