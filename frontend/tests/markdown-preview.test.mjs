import test from "node:test";
import assert from "node:assert/strict";
import {parseMarkdown,markdownInline,safeMarkdownLink} from "../lib/markdown.ts";

test("headings, bold, emphasis, code and thematic rules parse as structured data",()=>{
  const blocks=parseMarkdown("# عنوان رئيسي\n\nفقرة **مهمة** و *مائلة* و `code`.\n\n---\n\n```ts\nconst x = 1;\n```");
  assert.deepEqual(blocks.map(x=>x.type),["heading","paragraph","rule","code"]);
  assert.equal(blocks[0].level,1);
  assert.equal(blocks[1].children.some(x=>x.type==="strong"),true);
  assert.equal(blocks[1].children.some(x=>x.type==="em"),true);
  assert.equal(blocks[1].children.some(x=>x.type==="code"),true);
  assert.equal(blocks[3].language,"ts");
  assert.equal(blocks[3].value,"const x = 1;");
});

test("tables, quotes and lists are recognized",()=>{
  const blocks=parseMarkdown("| اسم | قيمة |\n| :--- | ---: |\n| الأول | ١ |\n\n> اقتباس\n\n- أول\n- ثاني\n\n1. A\n2. B");
  assert.deepEqual(blocks.map(x=>x.type),["table","quote","list","list"]);
  assert.deepEqual(blocks[0].align,["left","right"]);
  assert.equal(blocks[0].rows.length,1);
  assert.equal(blocks[2].items.length,2);
  assert.equal(blocks[3].ordered,true);
});

test("dangerous URL protocols are never turned into links",()=>{
  for(const url of ["javascript:alert(1)","data:text/html,abc","file:///etc/passwd","vbscript:evil","/api/backend/api/v1/files"]) {
    assert.equal(safeMarkdownLink(url),null,url);
  }
  assert.ok(safeMarkdownLink("https://example.com/docs"));
  assert.ok(safeMarkdownLink("mailto:info@example.com"));
  const nodes=markdownInline("[bad](javascript:alert) [good](https://example.com)");
  assert.equal(nodes.some(node=>node.type==="link"&&node.href.startsWith("javascript:")),false);
  assert.equal(nodes.some(node=>node.type==="link"&&node.href.startsWith("https:")),true);
});

test("embedded remote images are inert placeholders, raw HTML stays plain text",()=>{
  const nodes=markdownInline('![photo](https://remote.example/secret.png) <img src=x onerror=alert(1)>');
  assert.equal(nodes.some(node=>node.type==="image"&&node.value==="photo"),true);
  assert.equal(nodes.some(node=>node.type==="text"&&node.value.includes("<img")),true);
});

test("unclosed code fence and long documents have bounded parsing",()=>{
  const blocks=parseMarkdown("```js\n"+Array.from({length:6000},()=>"line").join("\n"));
  assert.equal(blocks[0].type,"code");
  assert.ok(blocks[0].value.length<=120000);
});
