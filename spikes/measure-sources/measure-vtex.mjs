// Throwaway measurement: look every EAN of the dyssa sample up in the public VTEX catalog API of several
// Argentine supermarkets, and download the first image of the first store that has the product.
// Usage: node measure-vtex.mjs [path-to-selection.json]
// Output: console table + out/vtex/results.json + out/vtex/images/ (git-ignored, third-party content).
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const selection = process.argv[2] || join(import.meta.dirname, '..', '..', 'docs', 'proyecto', 'origen', 'prueba-2026-11-05', 'dyssa-seleccion.json');
// VTEX_HOSTS (comma separated) and OUT_NAME (folder under out/) can be overridden from the environment.
const HOSTS = (process.env.VTEX_HOSTS || 'www.jumbo.com.ar,www.carrefour.com.ar,www.disco.com.ar,www.vea.com.ar,diaonline.supermercadosdia.com.ar,www.masonline.com.ar').split(',');
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';
const PAUSE_MS = Number(process.env.PAUSE_MS || 1000);
// PARALLEL_HOSTS=1: ask every store for the same EAN at once, then pause. Each store still sees a single
// request at a time, about one every (latency + PAUSE_MS), and the run is as long as the slowest store.
const PARALLEL = process.env.PARALLEL_HOSTS === '1';
// SKIP_FIRST_IMAGE=1: do not download the first image here (download-store-images.mjs does it later).
const SKIP_FIRST_IMAGE = process.env.SKIP_FIRST_IMAGE === '1';
const out = join(import.meta.dirname, 'out', process.env.OUT_NAME || 'vtex');
mkdirSync(join(out, 'images'), { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function sniff(buf) {
  if (buf.length > 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return 'jpg';
  if (buf.length > 8 && buf.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return 'png';
  if (buf.length > 12 && buf.toString('ascii', 0, 4) === 'RIFF' && buf.toString('ascii', 8, 12) === 'WEBP') return 'webp';
  return null;
}
function dimensions(buf, type) {
  if (type === 'png') return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
  if (type === 'jpg') {
    let i = 2;
    while (i + 9 < buf.length) {
      if (buf[i] !== 0xff) { i++; continue; }
      const m = buf[i + 1];
      if (m >= 0xc0 && m <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(m)) return { h: buf.readUInt16BE(i + 5), w: buf.readUInt16BE(i + 7) };
      i += 2 + buf.readUInt16BE(i + 2);
    }
  }
  return null;
}

async function lookup(host, ean) {
  const url = `https://${host}/api/catalog_system/pub/products/search?fq=alternateIds_Ean:${ean}`;
  const t0 = Date.now();
  try {
    const res = await fetch(url, { headers: { 'User-Agent': UA, Accept: 'application/json' }, signal: AbortSignal.timeout(20000) });
    const text = await res.text();
    let products;
    try { products = JSON.parse(text); } catch { return { host, status: res.status, ms: Date.now() - t0, note: 'not JSON' }; }
    if (!Array.isArray(products)) return { host, status: res.status, ms: Date.now() - t0, note: 'unexpected shape' };
    const hit = products.flatMap((p) => (p.items || []).filter((it) => it.ean === ean).map((it) => ({ p, it })))[0];
    return {
      host, status: res.status, ms: Date.now() - t0, products: products.length, exactEan: !!hit,
      name: hit?.p.productName, brand: hit?.p.brand, page: hit?.p.link,
      images: (hit?.it.images || []).map((i) => i.imageUrl),
    };
  } catch (e) {
    return { host, ms: Date.now() - t0, note: `${e.name}` };
  }
}

async function download(url, ean) {
  try {
    const res = await fetch(url, { headers: { 'User-Agent': UA }, signal: AbortSignal.timeout(20000) });
    const buf = Buffer.from(await res.arrayBuffer());
    if (buf.length > 4 * 1024 * 1024) return { note: 'too big' };
    const type = sniff(buf);
    if (!res.ok || !type) return { note: `HTTP ${res.status}, type by bytes: ${type}` };
    const file = join(out, 'images', `${ean}.${type}`);
    writeFileSync(file, buf);
    return { file, type, bytes: buf.length, contentType: res.headers.get('content-type'), ...(dimensions(buf, type) || {}) };
  } catch (e) {
    return { note: e.name };
  }
}

const items = JSON.parse(readFileSync(selection, 'utf8'));
const results = [];
let stopped = null;
for (const a of items) {
  let perHost = [];
  if (PARALLEL) {
    perHost = await Promise.all(HOSTS.map((host) => lookup(host, a.ean)));
    await sleep(PAUSE_MS);
  } else {
    for (const host of HOSTS) {
      perHost.push(await lookup(host, a.ean));
      await sleep(PAUSE_MS);
    }
  }
  const first = perHost.find((h) => h.exactEan && h.images?.length);
  const image = first && !SKIP_FIRST_IMAGE ? await download(first.images[0], a.ean) : null;
  // A third party that says "slow down" or "go away" ends the run: a measurement never insists.
  const refusal = perHost.find((h) => [401, 403, 429].includes(h.status));
  if (refusal) stopped = `${refusal.host} answered ${refusal.status} at EAN ${results.length + 1} of ${items.length}`;
  results.push({ ean: a.ean, descripcion: a.descripcion, stratum: a.stratum, stores: perHost, bestStore: first?.host || null, image });
  const found = perHost.filter((h) => h.exactEan).map((h) => h.host.replace(/^www\./, '').split('.')[0]);
  console.log(`${a.ean} | ${a.descripcion.slice(0, 38).padEnd(38)} | stores: ${found.length}/${HOSTS.length} ${found.join(',') || '-'}${image?.file ? ` | img ${image.type} ${image.w || '?'}x${image.h || '?'} ${Math.round(image.bytes / 1024)}KB` : ''}`);
  if (stopped) {
    console.log(`\nSTOPPED, not insisting: ${stopped}`);
    break;
  }
}

const ms = results.flatMap((r) => r.stores.map((s) => s.ms)).sort((x, y) => x - y);
const covered = results.filter((r) => r.bestStore).length;
const perStore = Object.fromEntries(HOSTS.map((h) => [h, results.filter((r) => r.stores.find((s) => s.host === h)?.exactEan).length]));
const bad = HOSTS.map((h) => [h, results.flatMap((r) => r.stores).filter((s) => s.host === h && (s.note || s.status !== 200)).length]).filter(([, n]) => n);
console.log(`\nCovered by at least one store: ${covered}/${results.length}`);
console.log('Per store:', JSON.stringify(perStore));
for (const s of [...new Set(results.map((r) => r.stratum).filter(Boolean))]) {
  const rs = results.filter((r) => r.stratum === s);
  console.log(`Stratum ${s}: ${rs.filter((r) => r.bestStore).length}/${rs.length} covered`);
}
console.log(`Latency per request: p50 ${ms[Math.floor(ms.length / 2)]} ms, p95 ${ms[Math.floor(ms.length * 0.95)]} ms, max ${ms.at(-1)} ms (${ms.length} requests)`);
if (bad.length) console.log('Hosts with errors or non-200:', JSON.stringify(bad));
writeFileSync(join(out, 'results.json'), JSON.stringify(results, null, 2));
