"use client";

import {useMemo,useState} from "react";
import type {ReactNode} from "react";
import {Check,Copy,FileImage} from "lucide-react";
import {parseMarkdown} from "@/lib/markdown";
import type {MdBlock,MdInline} from "@/lib/markdown";

function renderInline(nodes:MdInline[]):ReactNode {
  return nodes.map((node,index)=>{
    switch(node.type){
      case "text":return <span key={index}>{node.value}</span>;
      case "code":return <code key={index} className="archive-md-code-inline">{node.value}</code>;
      case "image":return <span key={index} className="archive-md-image-placeholder"><FileImage size={14}/> [{node.value}]</span>;
      case "strong":return <strong key={index}>{renderInline(node.children)}</strong>;
      case "em":return <em key={index}>{renderInline(node.children)}</em>;
      case "strike":return <del key={index}>{renderInline(node.children)}</del>;
      case "link":return <a key={index} href={node.href} target="_blank" rel="noopener noreferrer nofollow">{renderInline(node.children)}</a>;
    }
  });
}

function CodeFence({language,value}:{language:string;value:string}){
  const [copied,setCopied]=useState(false);
  async function copy(){
    try{await navigator.clipboard.writeText(value);setCopied(true)}
    catch{setCopied(false)}
  }
  return <div className="archive-md-codeblock">
    <div className="archive-md-codebar"><span>{language||"نص برمجي"}</span>
      <button type="button" onClick={()=>void copy()} aria-label="نسخ الكود">{copied?<Check size={15}/>:<Copy size={15}/>} {copied?"تم النسخ":"نسخ"}</button>
    </div>
    <pre dir="ltr"><code>{value}</code></pre>
  </div>;
}

function renderBlocks(blocks:MdBlock[],level=0):ReactNode {
  if(level>7)return null;
  return blocks.map((block,index)=>{
    const key=index;
    switch(block.type){
      case "heading":{
        const body=renderInline(block.children);
        switch(block.level){
          case 1:return <h1 key={key}>{body}</h1>;
          case 2:return <h2 key={key}>{body}</h2>;
          case 3:return <h3 key={key}>{body}</h3>;
          case 4:return <h4 key={key}>{body}</h4>;
          case 5:return <h5 key={key}>{body}</h5>;
          default:return <h6 key={key}>{body}</h6>;
        }
      }
      case "paragraph":return <p key={key}>{renderInline(block.children)}</p>;
      case "code":return <CodeFence key={key} language={block.language} value={block.value}/>;
      case "rule":return <hr key={key}/>;
      case "quote":return <blockquote key={key}>{renderBlocks(block.blocks,level+1)}</blockquote>;
      case "list":{
        const items=block.items.map((item,i)=><li key={i}>{renderBlocks(item,level+1)}</li>);
        return block.ordered?<ol key={key} start={block.start}>{items}</ol>:<ul key={key}>{items}</ul>;
      }
      case "table":return <div key={key} className="archive-md-table-wrap"><table><thead><tr>
        {block.header.map((cell,i)=><th key={i} scope="col" style={{textAlign:block.align[i]??"start"}}>{renderInline(cell)}</th>)}
      </tr></thead><tbody>
        {block.rows.map((row,i)=><tr key={i}>{block.header.map((_,j)=><td key={j} style={{textAlign:block.align[j]??"start"}}>{renderInline(row[j]??[])}</td>)}</tr>)}
      </tbody></table></div>;
    }
  });
}

export function MarkdownPreview({source}:{source:string}){
  const [raw,setRaw]=useState(false);
  const blocks=useMemo(()=>parseMarkdown(source),[source]);
  return <section className="archive-md-reader" aria-label="معاينة Markdown">
    <div className="archive-md-toolbar">
      <span>معاينة Markdown</span>
      <div role="group" aria-label="طريقة العرض">
        <button type="button" className={!raw?"active":""} aria-pressed={!raw} onClick={()=>setRaw(false)}>عرض منسق</button>
        <button type="button" className={raw?"active":""} aria-pressed={raw} onClick={()=>setRaw(true)}>النص الأصلي</button>
      </div>
    </div>
    {raw?<pre className="archive-preview-text" dir="auto">{source}</pre>:
      <article className="archive-md-content" dir="auto">{blocks.length?renderBlocks(blocks):<p>الملف فارغ</p>}</article>}
    <p className="hint">الروابط الخارجية تُفتح في نافذة منفصلة. الصور المضمنة ومحتوى HTML لا يُنفّذان حفاظًا على أمان الأرشيف.</p>
  </section>;
}
