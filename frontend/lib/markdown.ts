/**
 * Read-only Markdown subset for archive previews.
 * This parser produces structured data, never HTML. The React view renders text
 * with escaping and permits only explicitly validated external links.
 * Supports common Markdown/GFM: headings, paragraphs, lists, tables, quotes,
 * code fences, rules, emphasis, code spans and links.
 */
export type MdInline =
  | {type:"text"|"code"|"image";value:string}
  | {type:"strong"|"em"|"strike";children:MdInline[]}
  | {type:"link";href:string;children:MdInline[]};

export type MdBlock =
  | {type:"heading";level:number;children:MdInline[]}
  | {type:"paragraph";children:MdInline[]}
  | {type:"code";language:string;value:string}
  | {type:"quote";blocks:MdBlock[]}
  | {type:"rule"}
  | {type:"list";ordered:boolean;start:number;items:MdBlock[][]}
  | {type:"table";header:MdInline[][];rows:MdInline[][][];align:("left"|"center"|"right")[]};

const MAX_INPUT=120_000;
const MAX_LINES=4_000;
const MAX_DEPTH=6;
const ESCAPABLE=/[\\`*_{}\[\]()#+.!~>|-]/;

export function safeMarkdownLink(value:string):string|null {
  try {
    const url=new URL(value.trim());
    return ["https:","http:","mailto:"].includes(url.protocol)?url.href:null;
  } catch {return null}
}

export function markdownInline(text:string,depth=0):MdInline[] {
  if(depth>8)return [{type:"text",value:text}];
  const result:MdInline[]=[];
  let plain="";
  function flush(){if(plain){result.push({type:"text",value:plain});plain=""}}
  let i=0;
  while(i<text.length){
    if(text[i]==="\\"&&i+1<text.length&&ESCAPABLE.test(text[i+1])){
      plain+=text[i+1];i+=2;continue;
    }
    const chunk=text.slice(i);
    const image=chunk.match(/^!\[([^\]\n]{0,200})\]\(([^\n)]{1,2048})\)/);
    if(image){
      flush();
      // Do not load remote or data: images embedded in user documents.
      result.push({type:"image",value:image[1]||"صورة مضمنة"});
      i+=image[0].length;continue;
    }
    const link=chunk.match(/^\[([^\]\n]{1,300})\]\(([^\n)]{1,2048})\)/);
    if(link){
      const href=safeMarkdownLink(link[2]);
      flush();
      if(href)result.push({type:"link",href,children:markdownInline(link[1],depth+1)});
      else result.push({type:"text",value:link[1]});
      i+=link[0].length;continue;
    }
    if(text[i]==="`"){
      let run=1;while(text[i+run]==="`")run++;
      const fence="`".repeat(run);
      const end=text.indexOf(fence,i+run);
      if(end>=0){
        flush();result.push({type:"code",value:text.slice(i+run,end).replace(/^ | $/g,"")});
        i=end+run;continue;
      }
    }
    let formatted=false;
    for(const [marker,type] of [["**","strong"],["__","strong"],["~~","strike"],["*","em"],["_","em"]] as const){
      if(!text.startsWith(marker,i))continue;
      const end=text.indexOf(marker,i+marker.length);
      if(end<=i+marker.length)continue;
      const inside=text.slice(i+marker.length,end);
      if(!inside.trim()||inside.startsWith(" ")||inside.endsWith(" "))continue;
      flush();
      result.push({type,children:markdownInline(inside,depth+1)});
      i=end+marker.length;formatted=true;break;
    }
    if(formatted)continue;
    plain+=text[i];i++;
  }
  flush();
  return result;
}

function splitCells(line:string):string[]{
  let text=line.trim();
  if(text.startsWith("|"))text=text.slice(1);
  if(text.endsWith("|")&&!text.endsWith("\\|"))text=text.slice(0,-1);
  const cells:string[]=[];let value="",escaped=false,code=false;
  for(const ch of text){
    if(escaped){value+=ch;escaped=false;continue}
    if(ch==="\\"){escaped=true;continue}
    if(ch==="`"){code=!code;value+=ch;continue}
    if(ch==="|"&&!code){cells.push(value.trim());value="";continue}
    value+=ch;
  }
  if(escaped)value+="\\";
  cells.push(value.trim());
  return cells;
}
function tableSeparator(line:string):("left"|"right"|"center")[]|null{
  const cells=splitCells(line);
  if(!cells.length||!cells.every(c=>/^:?-{3,}:?$/.test(c)))return null;
  return cells.map(c=>c.startsWith(":")&&c.endsWith(":")?"center":c.endsWith(":")?"right":"left");
}
const heading=/^ {0,3}(#{1,6})\s+(.+)$/;
const fenced=/^ {0,3}(`{3,}|~{3,})\s*([^\s`~]*)?.*$/;
const listMarker=/^( {0,3})([-+*]|\d{1,7}[.)])\s+(.*)$/;
const quote=/^ {0,3}>\s?/;
const rule=/^ {0,3}(?:\*\s*){3,}$|^ {0,3}(?:-\s*){3,}$|^ {0,3}(?:_\s*){3,}$/;
function beginsBlock(lines:string[],index:number){
  const line=lines[index]??"";
  if(!line.trim())return true;
  return heading.test(line)||fenced.test(line)||quote.test(line)||rule.test(line)||listMarker.test(line)||
    (index+1<lines.length&&line.includes("|")&&tableSeparator(lines[index+1])!==null);
}

function blocks(lines:string[],depth:number):MdBlock[]{
  const output:MdBlock[]=[];
  let i=0;
  while(i<lines.length&&output.length<1000){
    const line=lines[i];
    if(!line.trim()){i++;continue}
    const fence=fenced.exec(line);
    if(fence){
      const opener=fence[1],symbol=opener[0],length=opener.length;
      const body:string[]=[];i++;
      while(i<lines.length){
        if(new RegExp("^ {0,3}"+(symbol==="`"?"`":"~")+"{"+length+",}\\s*$").test(lines[i])){i++;break}
        body.push(lines[i++]);
      }
      output.push({type:"code",language:(fence[2]??"").slice(0,32),value:body.join("\n")});continue;
    }
    const headingMatch=heading.exec(line);
    if(headingMatch){
      output.push({type:"heading",level:headingMatch[1].length,children:markdownInline(headingMatch[2].replace(/\s+#+\s*$/,""))});
      i++;continue;
    }
    if(rule.test(line)){output.push({type:"rule"});i++;continue}
    if(depth<MAX_DEPTH&&quote.test(line)){
      const body:string[]=[];
      while(i<lines.length&&(quote.test(lines[i])||(!lines[i].trim()&&quote.test(lines[i+1]??"")))){
        body.push(lines[i].replace(quote,""));i++;
      }
      output.push({type:"quote",blocks:blocks(body,depth+1)});continue;
    }
    const marker=listMarker.exec(line);
    if(marker&&depth<MAX_DEPTH){
      const indent=marker[1].length,ordered=/^\d/.test(marker[2]);
      const start=ordered?Math.max(1,Math.min(9999999,parseInt(marker[2],10))):1;
      const items:MdBlock[][]=[];
      while(i<lines.length&&items.length<300){
        const item=listMarker.exec(lines[i]);
        if(!item||item[1].length!==indent||(/^\d/.test(item[2]))!==ordered)break;
        const body=[item[3]];i++;
        while(i<lines.length){
          const next=listMarker.exec(lines[i]);
          if(next&&next[1].length===indent&&(/^\d/.test(next[2]))===ordered)break;
          if(!lines[i].trim()){
            if(i+1<lines.length&&/^\s{2,}\S/.test(lines[i+1])){body.push("");i++;continue}
            i++;break;
          }
          const prefix=(lines[i].match(/^ */)?.[0].length??0);
          if(prefix<=indent)break;
          body.push(lines[i].slice(Math.min(prefix,indent+2)));i++;
        }
        items.push(blocks(body,depth+1));
      }
      output.push({type:"list",ordered,start,items});continue;
    }
    if(i+1<lines.length&&line.includes("|")){
      const alignment=tableSeparator(lines[i+1]);
      if(alignment){
        const header=splitCells(line).map(markdownInline);
        const rows:MdInline[][][]=[];
        i+=2;
        while(i<lines.length&&lines[i].trim()&&lines[i].includes("|")&&rows.length<250){
          rows.push(splitCells(lines[i++]).map(markdownInline));
        }
        output.push({type:"table",header,rows,align:alignment});continue;
      }
    }
    const paragraph=[line];i++;
    while(i<lines.length&&!beginsBlock(lines,i)){paragraph.push(lines[i++])}
    output.push({type:"paragraph",children:markdownInline(paragraph.join("\n"))});
  }
  return output;
}

export function parseMarkdown(source:string):MdBlock[]{
  const bounded=source.slice(0,MAX_INPUT).replace(/\r\n?/g,"\n");
  return blocks(bounded.split("\n").slice(0,MAX_LINES),0);
}
