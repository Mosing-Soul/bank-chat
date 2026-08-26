import fs from 'node:fs/promises';
import { FileBlob, SpreadsheetFile } from '@oai/artifact-tool';

const source = 'D:/JetBrains/project/bank-chat/bank-agent-demo/assets/华辰银行零售客户经理QA知识库_Mock_V1.0.xlsx';
const out = 'D:/JetBrains/project/bank-chat/outputs/rag_expansion_v2';
await fs.mkdir(out, { recursive: true });
const wb = await SpreadsheetFile.importXlsx(await FileBlob.load(source));
const summary = await wb.inspect({kind:'workbook,sheet,region,computedStyle', maxChars:12000, tableMaxRows:8, tableMaxCols:16, tableMaxCellChars:100});
await fs.writeFile(`${out}/existing_inspect.txt`, summary.ndjson ?? String(summary), 'utf8');
for (const name of ['使用说明','客户经理QA','准入与材料矩阵','办理流程SOP','异常处置矩阵']) {
  const png = await wb.render({sheetName:name, autoCrop:'all', scale:0.75, format:'png'});
  await fs.writeFile(`${out}/existing_${name}.png`, new Uint8Array(await png.arrayBuffer()));
}
console.log('inspected and rendered');
