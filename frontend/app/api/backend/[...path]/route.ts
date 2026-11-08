import {NextRequest} from "next/server";
import {backendUrl,getAccessToken} from "@/lib/server/auth";
import {isSameOrigin} from "@/lib/server/request-origin";
export const runtime="nodejs";
export const dynamic="force-dynamic";
async function relay(request:NextRequest,ctx:{params:Promise<{path:string[]}>}){
  const {path}=await ctx.params;
  if(path.length<2||path[0]!=="api"||path[1]!=="v1"||path.some(v=>v==="."||v===".."||v.includes("\\")||v.includes("/")))return Response.json({success:false,data:{message:"مسار غير صالح"}},{status:400});
  if(!["GET","POST","PATCH","PUT","DELETE"].includes(request.method))return new Response(null,{status:405});
  if(request.method!=="GET"&&!isSameOrigin(request))return Response.json({success:false,data:{message:"مصدر غير مسموح"}},{status:403});
  const token=await getAccessToken();
  if(!token)return Response.json({success:false,data:{message:"يرجى تسجيل الدخول"}},{status:401});
  const url=backendUrl+"/"+path.map(encodeURIComponent).join("/")+request.nextUrl.search;
  const headers=new Headers();headers.set("Authorization","Bearer "+token);
  for(const key of ["content-type","accept","x-request-id"]){const value=request.headers.get(key);if(value)headers.set(key,value)}
  try{
    const upstream=await fetch(url,{method:request.method,headers,body:request.method==="GET"?undefined:request.body,cache:"no-store",redirect:"manual",duplex:"half"} as RequestInit&{duplex:string});
    const pass=new Headers();
    for(const key of ["content-type","content-disposition","content-length","retry-after","x-request-id"]){const value=upstream.headers.get(key);if(value)pass.set(key,value)}
    pass.set("Cache-Control","no-store");
    return new Response(upstream.body,{status:upstream.status,headers:pass});
  }catch{return Response.json({success:false,data:{message:"تعذر الاتصال بالخادم الخلفي"}},{status:502})}
}
export {relay as GET,relay as POST,relay as PATCH,relay as PUT,relay as DELETE};
