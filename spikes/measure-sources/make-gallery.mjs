// Throwaway prototype of the DELIVERABLE for a measured catalog: for each EAN the image the tool would pick,
// named after the SKU, plus an HTML gallery and a CSV report.
// Usage: OUT_NAME=danone [INPUT=out/danone-130.json] node make-gallery.mjs
// Needs out/<OUT_NAME>/ results.json, images-index.json, banner-detect.json and crop-report.json, and the input
// list (ean, descripcion, sku) used for the measurement.
// Output, all in out/<OUT_NAME>/ (git-ignored, third-party images and client data):
//   entrega/<SKU>.jpg   the picked image, a real JPEG, named after the SKU
//   entrega/reporte.csv SKU, EAN, description, state, store, kind, size
//   galeria.html        the same, to look at (images by relative path)
// Pick rule (docs/proyecto/contrato-api.md section 4, a proposal): an untouched original beats a cropped one,
// then at least 500 px, then the larger one, then the store order. A candidate that is not a real JPEG (the
// store served PNG or WebP) is only used if there is no JPEG, and then it is reported, not copied.
import { copyFileSync, mkdirSync, openSync, readFileSync, readSync, closeSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const out = join(import.meta.dirname, 'out', process.env.OUT_NAME || 'danone');
const read = (f) => JSON.parse(readFileSync(join(out, f), 'utf8'));
const results = read('results.json');
const index = read('images-index.json');
const detect = read('banner-detect.json');
const crops = read('crop-report.json');
const input = JSON.parse(readFileSync(join(import.meta.dirname, process.env.INPUT || 'out/danone-130.json'), 'utf8'));
const skuOf = new Map(input.map((i) => [i.ean, i.sku]));
const STORE_ORDER = ['jumbo', 'carrefour', 'masonline', 'josimar', 'cordiez', 'disco', 'vea'];
const esc = (s) => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const csv = (s) => `"${String(s ?? '').replace(/"/g, '""')}"`;

function isJpeg(path) {
  const fd = openSync(path, 'r');
  const b = Buffer.alloc(3);
  readSync(fd, b, 0, 3, 0);
  closeSync(fd);
  return b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff;
}

function candidatesOf(ean) {
  const list = [];
  for (const i of index.filter((x) => x.ean === ean && x.n === 0)) {
    const d = detect[i.file];
    if (!d) continue;
    if (d.cls === 'none') {
      const path = join(out, 'images-by-store', i.file);
      list.push({ kind: 'original', dir: 'images-by-store', file: i.file, store: i.store, side: Math.min(d.w, d.h), jpeg: isJpeg(path) });
    } else if (crops[i.file]?.status === 'cropped') {
      list.push({ kind: 'cropped', dir: 'cropped', file: i.file, store: i.store, side: crops[i.file].outSize, jpeg: true });
    } else if (d.cls === 'side' || d.cls === 'bottom') {
      list.push({ kind: 'banner', dir: 'images-by-store', file: i.file, store: i.store, side: Math.min(d.w, d.h), jpeg: isJpeg(join(out, 'images-by-store', i.file)) });
    }
  }
  return list;
}
const rank = (c) => [c.kind === 'banner' ? 1 : 0, c.jpeg ? 0 : 1, c.kind === 'original' ? 0 : 1, c.side >= 500 ? 0 : 1, -c.side, STORE_ORDER.indexOf(c.store)];
const better = (a, b) => { const x = rank(a), y = rank(b); for (let k = 0; k < x.length; k++) if (x[k] !== y[k]) return x[k] - y[k]; return 0; };

mkdirSync(join(out, 'entrega'), { recursive: true });
const cards = [];
const report = ['SKU,EAN,Descripcion,Estado,Tienda,Tipo,Lado_px'];
const tally = { original: 0, cropped: 0, banner: 0, none: 0, notJpeg: 0 };
for (const r of results) {
  const sku = skuOf.get(r.ean) ?? '';
  const found = r.stores.some((s) => s.exactEan);
  const best = found ? candidatesOf(r.ean).sort(better)[0] : undefined;
  const name = r.descripcion;
  const label = `${esc(name)}<br>SKU ${esc(sku)} · ${esc(r.ean)}`;
  if (!best || best.kind === 'banner') {
    tally[best ? 'banner' : 'none']++;
    const why = !found ? 'No esta en ninguna tienda' : 'Solo con banner, no se pudo recortar';
    report.push([sku, r.ean, csv(name), best ? 'REVISAR' : 'SIN_IMAGEN', '', '', ''].join(','));
    cards.push(`<article class="none"><div class="ph">${best ? 'con banner' : 'sin imagen'}</div><h3>${label}</h3><p class="tag red">${why}</p></article>`);
    continue;
  }
  if (!best.jpeg) {
    tally.notJpeg++;
    report.push([sku, r.ean, csv(name), 'NO_JPEG', best.store, best.kind, best.side].join(','));
    cards.push(`<article class="none"><div class="ph">no es JPEG</div><h3>${label}</h3><p class="tag red">La tienda sirvio otro formato (${esc(best.store)})</p></article>`);
    continue;
  }
  tally[best.kind]++;
  copyFileSync(join(out, best.dir, best.file), join(out, 'entrega', `${sku}.jpg`));
  report.push([sku, r.ean, csv(name), best.kind === 'original' ? 'OK' : 'OK_RECORTADA', best.store, best.kind, best.side].join(','));
  const tag = best.kind === 'original' ? '<p class="tag green">Limpia</p>' : '<p class="tag blue">Banner recortado</p>';
  cards.push(`<article><img loading="lazy" src="entrega/${esc(sku)}.jpg" alt=""><h3>${label}</h3><p>${esc(best.store)} · ${best.side}px</p>${tag}</article>`);
}
writeFileSync(join(out, 'entrega', 'reporte.csv'), '﻿' + report.join('\r\n') + '\r\n');

const html = `<!doctype html><html lang="es"><head><meta charset="utf-8"><title>Entrega: ${results.length} productos</title>
<style>
body{font:14px/1.4 system-ui,sans-serif;margin:0;padding:20px;background:#eef0ec;color:#3d4438}
h1{margin:0 0 4px;font-size:20px;color:#1b2016}.sum{margin:0 0 18px;color:#636b5c}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:12px}
article{background:#fff;border:1px solid #ccd3c4;border-radius:12px;padding:10px;display:flex;flex-direction:column;gap:4px}
article img,.ph{width:100%;aspect-ratio:1/1;object-fit:contain;background:#fff;border:1px solid #e3e7dd;border-radius:8px}
.ph{display:flex;align-items:center;justify-content:center;color:#b3261e;background:#fdecea}
h3{margin:4px 0 0;font-size:12px;color:#1b2016;font-weight:600}article p{margin:0;font-size:11px;color:#636b5c}
.tag{align-self:flex-start;padding:1px 8px;border-radius:999px;border:1px solid currentColor;font-weight:600}
.green{color:#2f6b2f}.blue{color:#2a5d8f}.red{color:#b3261e}
</style></head><body>
<h1>${results.length} productos de la tienda</h1>
<p class="sum">${tally.original} limpias · ${tally.cropped} con el banner recortado · ${tally.banner} con banner que no se pudo recortar · ${tally.notJpeg} en otro formato · ${tally.none} sin imagen. Cada foto de la carpeta <b>entrega/</b> se llama como su SKU.</p>
<div class="grid">${cards.join('\n')}</div></body></html>`;
writeFileSync(join(out, 'galeria.html'), html);
console.log('entrega/ y galeria.html:', JSON.stringify(tally), '| total', results.length);
