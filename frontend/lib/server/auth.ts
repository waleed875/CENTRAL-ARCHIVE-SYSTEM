import "server-only";
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
export const ACCESS="archive_access",REFRESH="archive_refresh";
export const backendUrl=(process.env.BACKEND_URL??"http://localhost:8080").replace(/\/$/,"");
function secureCookies(){
  const override=process.env.AUTH_COOKIE_SECURE;
  if(override==="true")return true;
  if(override==="false")return false;
  return process.env.NODE_ENV==="production";
}
export function setTokenCookies(response:NextResponse,tokens:{token:string;refreshToken:string;expiresIn:number;refreshExpiresIn:number}){
  const defaults={httpOnly:true,secure:secureCookies(),sameSite:"lax" as const,path:"/"};
  response.cookies.set(ACCESS,tokens.token,{...defaults,maxAge:Math.max(60,tokens.expiresIn)});
  response.cookies.set(REFRESH,tokens.refreshToken,{...defaults,maxAge:Math.max(60,tokens.refreshExpiresIn)});
}
export function clearTokenCookies(response:NextResponse){response.cookies.delete(ACCESS);response.cookies.delete(REFRESH)}
export async function getAccessToken(){return (await cookies()).get(ACCESS)?.value}
export async function getRefreshToken(){return (await cookies()).get(REFRESH)?.value}
export async function backendFetch(path:string,init:RequestInit={}){return fetch(backendUrl+path,{...init,cache:"no-store",signal:AbortSignal.timeout(30000)})}
