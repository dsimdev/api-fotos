// Throwaway: from out/vtex/results.json, download the first image of EVERY store that has the exact EAN,
// and group identical files by hash, so we only have to look at the distinct ones.
// Usage: node download-store-images.mjs
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const out = join(import.meta.dirname, 'out', 'vtex');
const dir = join(out, 'images-by-store');
mkdirSync(dir, { recursive: true });
const results = JSON.parse(readFileSync(join(out, 'results.json'), 'utf8'));
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';
const short = (h) => h.replace(/^www\./, '').split('.')[0];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

for (const r of results) {
  const groups = new Map();
  for (const s of r.stores) {
    if (!s.exactEan || !s.images?.length) continue;
    try {
      const res = await fetch(s.images[0], { headers: { 'User-Agent': UA }, signal: AbortSignal.timeout(20000) });
      const buf = Buffer.from(await res.arrayBuffer());
      if (!res.ok || buf.length > 4 * 1024 * 1024) { console.log(`${r.ean} ${short(s.host)}: HTTP ${res.status}`); continue; }
      const hash = createHash('md5').update(buf).digest('hex').slice(0, 8);
      writeFileSync(join(dir, `${r.ean}-${short(s.host)}.jpg`), buf);
      groups.set(hash, [...(groups.get(hash) || []), short(s.host)]);
    } catch (e) {
      console.log(`${r.ean} ${short(s.host)}: ${e.name}`);
    }
    await sleep(500);
  }
  console.log(`${r.ean} | distinct images: ${groups.size} | ${[...groups].map(([h, hosts]) => `${h}[${hosts.join('+')}]`).join('  ')}`);
}
