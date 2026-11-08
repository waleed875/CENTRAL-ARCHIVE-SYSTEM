import {redirect} from "next/navigation";
import {getAccessToken} from "@/lib/server/auth";
import {AppShell} from "@/components/app-shell";
export const dynamic="force-dynamic";
export default async function WorkspaceLayout({children}:{children:React.ReactNode}){
  if(!await getAccessToken())redirect("/login");
  return <AppShell>{children}</AppShell>
}
