import {NextRequest,NextResponse} from "next/server";
import {backendFetch,getRefreshToken,setTokenCookies,clearTokenCookies} from "@/lib/server/auth";
import {isSameOrigin} from "@/lib/server/request-origin";
export const runtime="nodejs";
export async function POST(req:NextRequest){
  if(!isSameOrigin(req))return NextResponse.json({error:"مصدر غير مسموح"},{status:403});
  const token=await getRefreshToken();
  if(!token)return NextResponse.json({error:"انتهت الجلسة"},{status:401});
  try{
    const upstream=await backendFetch("/api/v1/auth/refresh",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({token})});
    const payload=await upstream.json();
    if(!upstream.ok||!payload?.success){const res=NextResponse.json({error:"انتهت الجلسة"},{status:401});clearTokenCookies(res);return res}
    const response=NextResponse.json({user:payload.data.user});setTokenCookies(response,payload.data);return response;
  }catch{return NextResponse.json({error:"تعذر الاتصال بخادم الأرشيف"},{status:502})}
}
