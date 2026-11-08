import {NextRequest,NextResponse} from "next/server";
import {backendFetch,getAccessToken,clearTokenCookies} from "@/lib/server/auth";
import {isSameOrigin} from "@/lib/server/request-origin";
export const runtime="nodejs";
export async function POST(req:NextRequest){
  if(!isSameOrigin(req))return NextResponse.json({error:"مصدر غير مسموح"},{status:403});
  const token=await getAccessToken();
  if(token){try{await backendFetch("/api/v1/auth/logout",{method:"POST",headers:{Authorization:"Bearer "+token}})}catch{/* locally revoke cookies even if backend offline */}}
  const response=NextResponse.json({success:true});clearTokenCookies(response);return response;
}
