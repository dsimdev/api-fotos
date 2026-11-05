// Throwaway: pick a reproducible, stratified sample of EANs from the dump of dyssa's valid Argentine EANs.
// Usage: node select-sample.mjs [perStratum=20]
// Strata (a PROXY, not brand fame): small = brands with <= 5 articles in dyssa's page, large = 6 or more.
// One article per brand (distinct brands), seeded shuffle, the 10 EANs of the first test are excluded.
// Output: sample-dyssa-40.json (EAN + description only, safe to version).
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const perStratum = Number(process.argv[2] || 20);
const SEED = 20261105;
const dump = JSON.parse(readFileSync(join(import.meta.dirname, 'out', 'dyssa-valid779.json'), 'utf8'));
const first10 = new Set(JSON.parse(readFileSync(join(import.meta.dirname, '..', '..', 'docs', 'proyecto', 'origen', 'prueba-2026-11-05', 'dyssa-seleccion.json'), 'utf8')).map((a) => a.ean));

function mulberry32(a) {
  return () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rnd = mulberry32(SEED);
const shuffle = (xs) => { const a = [...xs]; for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a; };

const brands = new Map();
for (const a of dump) (brands.get(a.codigoMarca) || brands.set(a.codigoMarca, []).get(a.codigoMarca)).push(a);

const sample = [];
for (const [stratum, test] of [['small-brand', (n) => n <= 5], ['large-brand', (n) => n >= 6]]) {
  const candidates = shuffle([...brands.values()].filter((v) => test(v.length)));
  for (const arts of candidates) {
    if (sample.filter((s) => s.stratum === stratum).length >= perStratum) break;
    const options = arts.filter((a) => !first10.has(a.ean));
    if (!options.length) continue;
    const a = shuffle(options)[0];
    sample.push({ ean: a.ean, descripcion: a.descripcion, codigoMarca: a.codigoMarca, codigoRubro: a.codigoRubro, stratum });
  }
}
writeFileSync(join(import.meta.dirname, 'sample-dyssa-40.json'), JSON.stringify(sample, null, 2));
console.log(`seed ${SEED}: ${sample.length} EANs (${sample.filter((s) => s.stratum === 'small-brand').length} small-brand, ${sample.filter((s) => s.stratum === 'large-brand').length} large-brand), ${new Set(sample.map((s) => s.codigoMarca)).size} distinct brands`);
for (const s of sample) console.log(`${s.stratum.padEnd(11)} ${s.ean} ${s.codigoRubro.padEnd(11)} ${s.descripcion}`);
