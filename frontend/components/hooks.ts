"use client";
import {useCallback,useEffect,useState}from"react";
import {api}from"@/lib/api";
export function useRemote<T>(path:string|null){
 const [data,setData]=useState<T|null>(null),[error,setError]=useState(""),[loading,setLoading]=useState(!!path),[revision,setRevision]=useState(0);
 const reload=useCallback(()=>setRevision(n=>n+1),[]);
 useEffect(()=>{if(!path){setData(null);setLoading(false);return}let alive=true;setLoading(true);setError("");
 api<T>(path).then(v=>{if(alive)setData(v)}).catch(e=>{if(alive)setError(e instanceof Error?e.message:"تعذر جلب البيانات")}).finally(()=>{if(alive)setLoading(false)});
 return()=>{alive=false};},[path,revision]);
 return{data,error,loading,reload};
}
