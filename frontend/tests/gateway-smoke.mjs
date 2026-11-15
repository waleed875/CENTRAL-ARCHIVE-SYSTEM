import assert from "node:assert/strict";
import http from "node:http";
import {spawn} from "node:child_process";
import {setTimeout as sleep} from "node:timers/promises";

const mock=http.createServer(async(req,res)=>{
  const chunks=[];for await (const piece of req)chunks.push(piece);
  const payload=Buffer.concat(chunks);
  const route=new URL(req.url??"/","http://mock.local").pathname;
  function json(status,data){res.writeHead(status,{"Content-Type":"application/json; charset=utf-8"});res.end(JSON.stringify(data))}
  if(route==="/api/v1/auth/login"&&req.method==="POST"){
    const body=JSON.parse(payload.toString());assert.equal(body.username,"archivist");assert.equal(body.password,"test-password");
    return json(200,{success:true,data:{token:"test-access-token",refreshToken:"test-refresh-token",expiresIn:3600,refreshExpiresIn:86400,user:{id:"user-1",username:"archivist",fullName:"مستخدم اختبار",roles:["system_admin"]}}});
  }
  if(route==="/api/v1/auth/refresh"&&req.method==="POST"){
    assert.equal(JSON.parse(payload.toString()).token,"test-refresh-token");
    return json(200,{success:true,data:{token:"refreshed-access-token",refreshToken:"refreshed-refresh-token",expiresIn:3600,refreshExpiresIn:86400,user:{username:"archivist",roles:["system_admin"]}}});
  }
  if(!["Bearer test-access-token","Bearer refreshed-access-token"].includes(req.headers.authorization??""))return json(401,{success:false,data:{message:"يرجى تسجيل الدخول"}});
  if(route==="/api/v1/auth/me"&&req.method==="GET")return json(200,{success:true,data:{username:"archivist",fullName:"مستخدم اختبار",roles:["system_admin"]}});
  if(route==="/api/v1/auth/logout"&&req.method==="POST")return json(200,{success:true});
  if(route==="/api/v1/projects"&&req.method==="POST"){
    assert.equal(JSON.parse(payload.toString()).name,"مشروع اختبار");
    return json(201,{success:true,data:{id:"project-1",name:"مشروع اختبار",status:"DRAFT"}});
  }
  if(route==="/api/v1/projects"&&req.method==="GET")return json(200,{success:true,data:{data:[{id:"project-1",name:"مشروع اختبار",status:"DRAFT"}],page:1,limit:2,total:1,totalPages:1}});
  if(route==="/api/v1/projects/project-1/members/member-1"&&req.method==="DELETE"){res.writeHead(204);res.end();return;}
  if(route==="/api/v1/projects/project-1/files/upload"&&req.method==="POST"){
    assert.match(req.headers["content-type"]??"",/multipart\/form-data/);
    assert.match(payload.toString(),/name="metadata"/);
    assert.match(payload.toString(),/name="file"/);
    assert.match(payload.toString(),/hello archive/);
    return json(201,{success:true,data:{id:"file-1",originalName:"document.txt"}});
  }
  if(route==="/api/v1/files/file-1/download"&&req.method==="GET"){
    res.writeHead(200,{"Content-Type":"application/octet-stream","Content-Disposition":'attachment; filename="document.txt"'});res.end("hello archive");return;
  }
  if(route==="/api/v1/files/file-1/preview/pdf/info"&&req.method==="GET"){
    return json(200,{pageCount:2});
  }
  if(route==="/api/v1/files/file-1/preview/pdf/pages/1"&&req.method==="GET"){
    const tinyPng=Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jR1cAAAAASUVORK5CYII=","base64");
    res.writeHead(200,{"Content-Type":"image/png","Cache-Control":"no-store","Content-Length":tinyPng.length});
    res.end(tinyPng);return;
  }
  return json(404,{success:false,data:{message:"غير موجود"}});
});
await new Promise(resolve=>mock.listen(0,"127.0.0.1",resolve));
const backend="http://127.0.0.1:"+mock.address().port;
const frontend="http://127.0.0.1:3105";
const next=spawn(process.execPath,[".next/standalone/server.js"],{env:{...process.env,BACKEND_URL:backend,HOSTNAME:"0.0.0.0",PORT:"3105",NODE_ENV:"production"},stdio:["ignore","pipe","pipe"]});
let logs="";next.stdout.on("data",d=>logs+=d.toString());next.stderr.on("data",d=>logs+=d.toString());
async function request(path,options={}){
  const headers=new Headers(options.headers);
  if(options.method&&options.method!=="GET"&&!headers.has("Origin"))headers.set("Origin",frontend);
  return fetch(frontend+path,{redirect:"manual",...options,headers});
}
function cookies(res){return res.headers.getSetCookie().map(v=>v.split(";")[0]).join("; ")}
try{
  let ready=false;
  for(let i=0;i<100;i++){try{const r=await request("/login");if(r.ok){ready=true;break}}catch{}await sleep(150)}
  assert.ok(ready,"Next server did not start: "+logs);
  const denied=await request("/api/backend/api/v1/projects");
  assert.equal(denied.status,401);
  const loginBody=JSON.stringify({username:"archivist",password:"test-password"});
  for(const origin of ["https://malicious.example","http://127.0.0.1:3106","https://127.0.0.1:3105","http://0.0.0.0:3105","null",""]){
    for(const path of ["/api/session/login","/api/session/refresh","/api/session/logout"]){
      const blocked=await request(path,{method:"POST",headers:{"Content-Type":"application/json",Origin:origin,"X-Forwarded-Host":"malicious.example"},body:loginBody});
      assert.equal(blocked.status,403,`Accepted invalid origin ${origin} for ${path}`);
      assert.equal(blocked.headers.get("set-cookie"),null);
    }
  }
  const localhostLogin=await fetch("http://localhost:3105/api/session/login",{method:"POST",headers:{"Content-Type":"application/json",Origin:"http://localhost:3105"},body:loginBody});
  assert.equal(localhostLogin.status,200,await localhostLogin.text());
  const login=await request("/api/session/login",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({username:"archivist",password:"test-password"})});
  assert.equal(login.status,200,await login.text().catch(()=>""));
  const loginData=await (await fetch(frontend+"/api/session/login",{method:"POST",headers:{"Content-Type":"application/json"},body:loginBody})).json();
  assert.equal(loginData.user.username,"archivist");
  assert.equal(loginData.token,undefined,"JWT leaked in response body");
  const sessionCookie=cookies(login);
  assert.match(sessionCookie,/archive_access=/);assert.match(sessionCookie,/archive_refresh=/);
  assert.match(login.headers.get("set-cookie")??"",/HttpOnly/i);
  const headers={Cookie:sessionCookie};
  const me=await request("/api/backend/api/v1/auth/me",{headers});assert.equal(me.status,200);assert.equal((await me.json()).data.username,"archivist");
  const query=await request("/api/backend/api/v1/projects?page=1&size=2",{headers});assert.equal(query.status,200);assert.equal((await query.json()).data.total,1);
  const created=await request("/api/backend/api/v1/projects",{method:"POST",headers:{...headers,"Content-Type":"application/json"},body:JSON.stringify({name:"مشروع اختبار"})});
  assert.equal(created.status,201);assert.equal((await created.json()).data.id,"project-1");
  const deletedMember=await request("/api/backend/api/v1/projects/project-1/members/member-1",{method:"DELETE",headers});assert.equal(deletedMember.status,204);assert.equal(await deletedMember.text(),"");
  const csrf=await request("/api/backend/api/v1/projects",{method:"POST",headers:{...headers,"Content-Type":"application/json",Origin:"https://malicious.example"},body:"{}"});assert.equal(csrf.status,403);
  const form=new FormData();form.append("file",new Blob(["hello archive"],{type:"text/plain"}),"document.txt");form.append("metadata",new Blob([JSON.stringify({categoryId:"category-1"})],{type:"application/json"}),"metadata.json");
  const uploaded=await request("/api/backend/api/v1/projects/project-1/files/upload",{method:"POST",headers,body:form});assert.equal(uploaded.status,201,await uploaded.text().catch(()=>""));
  const file=await request("/api/backend/api/v1/files/file-1/download",{headers});assert.equal(file.status,200);assert.equal(await file.text(),"hello archive");assert.match(file.headers.get("content-disposition")??"",/document.txt/);
  const pdfInfo=await request("/api/backend/api/v1/files/file-1/preview/pdf/info",{headers});
  assert.equal(pdfInfo.status,200);
  assert.equal((await pdfInfo.json()).pageCount,2);
  const pdfImage=await request("/api/backend/api/v1/files/file-1/preview/pdf/pages/1",{headers});
  assert.equal(pdfImage.status,200);
  assert.match(pdfImage.headers.get("content-type")??"",/image\\/png/);
  const imageBytes=new Uint8Array(await pdfImage.arrayBuffer());
  assert.deepEqual(Array.from(imageBytes.slice(0,4)),[137,80,78,71]);
  const privatePdf=await request("/api/backend/api/v1/files/file-1/preview/pdf/info");
  assert.equal(privatePdf.status,401);
  const refreshed=await request("/api/session/refresh",{method:"POST",headers});assert.equal(refreshed.status,200);assert.match(cookies(refreshed),/refreshed-access-token/);
  const loggedOut=await request("/api/session/logout",{method:"POST",headers});assert.equal(loggedOut.status,200);assert.match(loggedOut.headers.get("set-cookie")??"",/archive_access=/);
  console.log("Gateway smoke: localhost/127.0.0.1 login with 0.0.0.0 binding, HttpOnly cookies, auth, projects, 204 no-content delete, CSRF, multipart, download, refresh, logout PASS");
}catch(e){console.error(logs);throw e}
finally{next.kill("SIGTERM");await new Promise(resolve=>mock.close(resolve))}
