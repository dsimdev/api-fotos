// Throwaway: from out/<OUT_NAME>/results.json, download the images of every store that has the exact EAN,
// and group identical files by hash, so we only have to look at the distinct ones.
// Usage: [OUT_NAME=vtex-40] [ALL_IMAGES=1] node download-store-images.mjs
//   default: only the FIRST image of each store (file <ean>-<store>.jpg)
//   ALL_IMAGES=1: every image of each store (first keeps the name, the others are <ean>-<store>-<n>.jpg)
// Output: out/<OUT_NAME>/images-by-store/ and out/<OUT_NAME>/images-index.json
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const out = join(import.meta.dirname, 'out', process.env.OUT_NAME || 'vtex');
const all = process.env.ALL_IMAGES === '1';
const dir = join(out, 'images-by-store');
mkdirSync(dir, { recursive: true });
const results = JSON.parse(readFileSync(join(out, 'results.json'), 'utf8'));
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';
const short = (h) => h.replace(/^www\./, '').split('.')[0];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const index = [];
for (const r of results) {
  const groups = new Map();
  for (const s of r.stores) {
    if (!s.exactEan || !s.images?.length) continue;
    const urls = all ? s.images : s.images.slice(0, 1);
    for (const [n, url] of urls.entries()) {
      try {
        const res = await fetch(url, { headers: { 'User-Agent': UA }, signal: AbortSignal.timeout(20000) });
        const buf = Buffer.from(await res.arrayBuffer());
        if (!res.ok || buf.length > 6 * 1024 * 1024) { console.log(`${r.ean} ${short(s.host)}#${n}: HTTP ${res.status} ${buf.length} bytes`); continue; }
        const hash = createHash('md5').update(buf).digest('hex').slice(0, 8);
        const file = n === 0 ? `${r.ean}-${short(s.host)}.jpg` : `${r.ean}-${short(s.host)}-${n}.jpg`;
        writeFileSync(join(dir, file), buf);
        index.push({ ean: r.ean, store: short(s.host), n, file, hash, bytes: buf.length });
        groups.set(hash, [...(groups.get(hash) || []), `${short(s.host)}#${n}`]);
      } catch (e) {
        console.log(`${r.ean} ${short(s.host)}#${n}: ${e.name}`);
      }
      await sleep(400);
    }
  }
  console.log(`${r.ean} | distinct images: ${groups.size}`);
}
writeFileSync(join(out, 'images-index.json'), JSON.stringify(index, null, 2));
console.log(`${index.length} files, ${new Set(index.map((i) => `${i.ean}:${i.hash}`)).size} distinct per EAN`);
