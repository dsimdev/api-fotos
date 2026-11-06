// Throwaway: from out/<OUT_NAME>/results.json, download the images of every store that has the exact EAN.
// Usage: [OUT_NAME=vtex-40] [ALL_IMAGES=1] [CONCURRENCY=6] [PAUSE_MS=300] node download-store-images.mjs
//   default: only the FIRST image of each store (file <ean>-<store>.jpg)
//   ALL_IMAGES=1: every image of each store (first keeps the name, the others are <ean>-<store>-<n>.jpg)
//   CONCURRENCY: parallel downloads (default 1). The images come from each store's CDN, so a few at a time
//   spread over several hosts; each worker pauses PAUSE_MS between its own downloads.
// Asks for JPEG/PNG and never WebP (the VTEX CDN serves WebP to a browser, and Java cannot read it).
// Output: out/<OUT_NAME>/images-by-store/ and out/<OUT_NAME>/images-index.json
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const out = join(import.meta.dirname, 'out', process.env.OUT_NAME || 'vtex');
const all = process.env.ALL_IMAGES === '1';
const concurrency = Number(process.env.CONCURRENCY || 1);
const pause = Number(process.env.PAUSE_MS || 400);
const dir = join(out, 'images-by-store');
mkdirSync(dir, { recursive: true });
const results = JSON.parse(readFileSync(join(out, 'results.json'), 'utf8'));
const UA = 'api-fotos/0.1 (measurement)';
const short = (h) => h.replace(/^www\./, '').split('.')[0];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const tasks = [];
for (const r of results) {
  for (const s of r.stores) {
    if (!s.exactEan || !s.images?.length) continue;
    for (const [n, url] of (all ? s.images : s.images.slice(0, 1)).entries()) tasks.push({ ean: r.ean, store: short(s.host), n, url });
  }
}

const index = [];
let next = 0;
let failed = 0;
async function worker() {
  while (next < tasks.length) {
    const t = tasks[next++];
    try {
      const res = await fetch(t.url, { headers: { 'User-Agent': UA, Accept: 'image/jpeg,image/png;q=0.9' }, signal: AbortSignal.timeout(20000) });
      const buf = Buffer.from(await res.arrayBuffer());
      if (!res.ok || buf.length > 6 * 1024 * 1024) { failed++; console.log(`${t.ean} ${t.store}#${t.n}: HTTP ${res.status} ${buf.length} bytes`); continue; }
      const hash = createHash('md5').update(buf).digest('hex').slice(0, 8);
      const file = t.n === 0 ? `${t.ean}-${t.store}.jpg` : `${t.ean}-${t.store}-${t.n}.jpg`;
      writeFileSync(join(dir, file), buf);
      index.push({ ean: t.ean, store: t.store, n: t.n, file, hash, bytes: buf.length, contentType: res.headers.get('content-type') });
    } catch (e) {
      failed++;
      console.log(`${t.ean} ${t.store}#${t.n}: ${e.name}`);
    }
    await sleep(pause);
  }
}
await Promise.all(Array.from({ length: Math.max(1, concurrency) }, worker));

writeFileSync(join(out, 'images-index.json'), JSON.stringify(index, null, 2));
const types = {};
index.forEach((i) => (types[i.contentType] = (types[i.contentType] || 0) + 1));
console.log(`${index.length} files downloaded of ${tasks.length} (${failed} failed), ${new Set(index.map((i) => `${i.ean}:${i.hash}`)).size} distinct per EAN`);
console.log('content types served:', JSON.stringify(types));
