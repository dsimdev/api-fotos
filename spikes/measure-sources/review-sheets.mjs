// Throwaway: contact sheets of every picked image, to review the whole delivery by eye.
// Usage: OUT_NAME=danone [PER_SHEET=20] [COLUMNS=5] node review-sheets.mjs
// Reads out/<OUT_NAME>/entrega/<SKU>.jpg and reporte.csv (for the description), writes out/<OUT_NAME>/review-N.png.
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { chromium } from 'playwright';

const out = join(import.meta.dirname, 'out', process.env.OUT_NAME || 'danone');
const per = Number(process.env.PER_SHEET || 20);
const cols = Number(process.env.COLUMNS || 5);
const CELL = 300;
const dir = join(out, 'entrega');

const desc = new Map();
for (const line of readFileSync(join(dir, 'reporte.csv'), 'utf8').replace(/^﻿/, '').split(/\r?\n/).slice(1)) {
  const m = /^([^,]*),([^,]*),"((?:[^"]|"")*)"/.exec(line);
  if (m) desc.set(m[1], m[3].replace(/""/g, '"'));
}
const files = readdirSync(dir).filter((f) => f.endsWith('.jpg')).sort();
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: cols * (CELL + 14) + 20, height: 900 } });
const manifest = [];
for (let s = 0; s * per < files.length; s++) {
  const chunk = files.slice(s * per, (s + 1) * per);
  const cells = chunk.map((f) => {
    const sku = f.replace(/\.jpg$/, '');
    const uri = 'data:image/jpeg;base64,' + readFileSync(join(dir, f)).toString('base64');
    return `<figure style="margin:0"><img src="${uri}" style="width:${CELL}px;height:${CELL}px;object-fit:contain;background:#fff;border:1px solid #bbb;display:block"><figcaption style="font:12px sans-serif;width:${CELL}px"><b>${esc(sku)}</b> ${esc((desc.get(sku) || '').slice(0, 44))}</figcaption></figure>`;
  }).join('');
  await page.setContent(`<body style="margin:8px;background:#eee"><div style="display:grid;grid-template-columns:repeat(${cols},${CELL}px);gap:10px 12px">${cells}</div></body>`);
  const path = join(out, `review-${s + 1}.png`);
  await page.screenshot({ path, fullPage: true });
  manifest.push({ sheet: path, skus: chunk.map((f) => f.replace(/\.jpg$/, '')) });
}
await browser.close();
writeFileSync(join(out, 'review-manifest.json'), JSON.stringify(manifest, null, 1));
console.log(`${files.length} images in ${manifest.length} sheets of up to ${per}:`);
manifest.forEach((m, i) => console.log(`  review-${i + 1}.png  ${m.skus[0]} .. ${m.skus.at(-1)} (${m.skus.length})`));
