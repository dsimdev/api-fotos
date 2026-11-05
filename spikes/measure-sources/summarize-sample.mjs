// Throwaway: consolidate a VTEX run into per-EAN outcomes and per-stratum numbers.
// Usage: [OUT_NAME=vtex-40] node summarize-sample.mjs
// Needs out/<OUT_NAME>/results.json (measure-vtex), images-index.json (download-store-images) and
// banner-detect.json (detect-banner over out/<OUT_NAME>/images-by-store).
// Outcome per EAN: not-found | clean (some distinct image has no banner) | banner-only (every classifiable image has
// a banner) | unclassified (only images the detector cannot judge).
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const out = join(import.meta.dirname, 'out', process.env.OUT_NAME || 'vtex');
const read = (f) => JSON.parse(readFileSync(join(out, f), 'utf8'));
const results = read('results.json');
const index = read('images-index.json');
const detect = read('banner-detect.json');

const rows = results.map((r) => {
  const files = index.filter((i) => i.ean === r.ean);
  const distinct = new Map();
  for (const f of files) if (!distinct.has(f.hash)) distinct.set(f.hash, { ...f, ...detect[f.file], stores: [] });
  for (const f of files) distinct.get(f.hash).stores.push(`${f.store}#${f.n}`);
  const imgs = [...distinct.values()];
  const clean = imgs.filter((i) => i.cls === 'none').sort((a, b) => b.w * b.h - a.w * a.h);
  const banner = imgs.filter((i) => i.cls === 'side' || i.cls === 'bottom');
  const storesFound = r.stores.filter((s) => s.exactEan).map((s) => s.host.replace(/^www\./, '').split('.')[0]);
  const outcome = !storesFound.length ? 'not-found' : clean.length ? 'clean' : banner.length ? 'banner-only' : 'unclassified';
  const first = files.find((f) => f.n === 0 && detect[f.file]);
  return { ean: r.ean, descripcion: r.descripcion, stratum: r.stratum || 'all', storesFound, distinctImages: imgs.length, outcome, bestClean: clean[0] ? { file: clean[0].file, w: clean[0].w, h: clean[0].h, stores: clean[0].stores } : null, cropable: banner.filter((b) => b.cropBox).length, firstStoreFirstImageClean: first ? detect[first.file].cls === 'none' : null };
});
writeFileSync(join(out, 'summary.json'), JSON.stringify(rows, null, 2));

const pct = (n, d) => (d ? `${n}/${d} (${Math.round((100 * n) / d)}%)` : '0/0');
for (const s of ['all', ...new Set(rows.map((r) => r.stratum).filter((x) => x !== 'all'))]) {
  const rs = s === 'all' ? rows : rows.filter((r) => r.stratum === s);
  const c = (o) => rs.filter((r) => r.outcome === o).length;
  console.log(`\n== ${s} (${rs.length} EANs) ==`);
  console.log(`  found by exact EAN in >=1 store : ${pct(rs.length - c('not-found'), rs.length)}`);
  console.log(`  at least one clean image        : ${pct(c('clean'), rs.length)}`);
  console.log(`  banner only (all classifiable)  : ${pct(c('banner-only'), rs.length)}`);
  console.log(`  unclassified only               : ${pct(c('unclassified'), rs.length)}`);
  console.log(`  not found                       : ${pct(c('not-found'), rs.length)}`);
  const found = rs.filter((r) => r.outcome !== 'not-found');
  const withFirst = found.filter((r) => r.firstStoreFirstImageClean !== null);
  console.log(`  clean if we only took the first image of the first store: ${pct(withFirst.filter((r) => r.firstStoreFirstImageClean).length, withFirst.length)} of those found`);
  console.log(`  stores per found EAN: avg ${(found.reduce((a, r) => a + r.storesFound.length, 0) / (found.length || 1)).toFixed(1)}`);
}
console.log('\nPer EAN:');
for (const r of rows) console.log(`${r.stratum.padEnd(11)} ${r.ean} ${r.outcome.padEnd(12)} stores:${r.storesFound.length} imgs:${r.distinctImages} ${r.bestClean ? `best ${r.bestClean.w}x${r.bestClean.h} ${r.bestClean.stores[0]}` : ''} | ${r.descripcion.slice(0, 40)}`);
