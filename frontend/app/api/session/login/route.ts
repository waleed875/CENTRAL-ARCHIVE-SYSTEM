import { NextRequest,NextResponse } from "next/server";
import { backendFetch,setTokenCookies } from "@/lib/server/auth";
import { isSameOrigin } from "@/lib/server/request-origin";
export const runtime="nodejs";
export async function POST(request:NextRequest){
  if(!isSameOrigin(request))return NextResponse.json({error:"مصدر غير مسموح"},{status:403});
  try{
    const body=await request.json();
    if(typeof body.username!=="string"||typeof body.password!=="string"||body.username.length>150)return NextResponse.json({error:"بيانات الدخول غير صحيحة"},{status:400});
    const upstream=await backendFetch("/api/v1/auth/login",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({username:body.username,password:body.password})});
    const payload=await upstream.json();
    if(!upstream.ok||!payload?.success)return NextResponse.json({error:payload?.data?.message??"اسم المستخدم أو كلمة المرور غير صحيحة"},{status:upstream.status});
    const tokens=payload.data;
    const response=NextResponse.json({user:tokens.user});
    setTokenCookies(response,tokens);
    return response;
  }catch{return NextResponse.json({error:"تعذر الاتصال بخادم الأرشيف"},{status:502})}
}
