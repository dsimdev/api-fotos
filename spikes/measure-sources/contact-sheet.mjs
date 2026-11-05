// Throwaway: render before/after pairs on one PNG so many can be reviewed in a single look.
// Usage: node contact-sheet.mjs <pairs.json> <out-prefix> [pairsPerSheet=6]
// pairs.json: [{ "label": "...", "before": "path", "after": "path" }]. Output: <out-prefix>-1.png, -2.png, ...
import { readFileSync } from 'node:fs';
import { chromium } from 'playwright';

const [pairsFile, prefix, perArg] = process.argv.slice(2);
const pairs = JSON.parse(readFileSync(pairsFile, 'utf8'));
const per = Number(perArg || 6);
const CELL = 380;
const uri = (f) => 'data:image/jpeg;base64,' + readFileSync(f).toString('base64');
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 2 * (2 * CELL + 30), height: 800 } });
for (let s = 0; s * per < pairs.length; s++) {
  const chunk = pairs.slice(s * per, (s + 1) * per);
  const cells = chunk.map((p) => `<div style="display:flex;flex-direction:column;gap:2px"><div style="font:13px sans-serif">${p.label}</div><div style="display:flex;gap:4px">
    <img src="${uri(p.before)}" style="width:${CELL}px;height:${CELL}px;object-fit:contain;border:1px solid #bbb;background:#fff">
    <img src="${uri(p.after)}" style="width:${CELL}px;height:${CELL}px;object-fit:contain;border:2px solid #2a7;background:#fff"></div></div>`).join('');
  await page.setContent(`<body style="margin:6px;background:#eee"><div style="display:grid;grid-template-columns:repeat(2,auto);gap:10px 26px;width:max-content">${cells}</div></body>`);
  await page.screenshot({ path: `${prefix}-${s + 1}.png`, fullPage: true });
  console.log(`${prefix}-${s + 1}.png (${chunk.length} pairs)`);
}
await browser.close();
