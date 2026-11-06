// Throwaway: remove the banner template panel from product images and re-center the product on a white square.
// Usage: node crop-banner.mjs <images-dir> <banner-detect.json> <out-dir>
// Only images the detector marked side|bottom are processed. How it works (all in Chromium on a canvas):
//   1. non-white mask at 400x400, connected components (4-connectivity, no gap filling);
//   2. the PANEL is found by what it IS, not by being "the smallest thing": a solid rectangle (fill >= 0.5), thick
//      enough, tall at the right (a side strip) or wide at the bottom (a bottom strip), with ALL the rest of the
//      content on one side of it. Plus the small badge ("900g") that sits in the same column or row;
//   3. the product = every other component (a white pack breaks into pieces, so it is NOT assumed to be one
//      component and NOT assumed to be the largest: v1 assumed that and picked the panel as the product);
//   4. only that bounding box is copied from the ORIGINAL pixels onto a white square (no upscaling, 8% margin);
//   5. the result is VERIFIED: the same panel search runs on the output and must find nothing, otherwise the
//      image is reported as leftover-panel and not delivered.
// Safety: if no panel qualifies, nothing is cropped (no-separate-panel). The product is never cut.
// Output: <out-dir>/<file>.jpg and <out-dir>/../crop-report.json
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { chromium } from 'playwright';

const [dir, detectFile, outDir] = process.argv.slice(2);
if (!dir || !detectFile || !outDir) { console.error('Usage: node crop-banner.mjs <images-dir> <banner-detect.json> <out-dir>'); process.exit(1); }
mkdirSync(outDir, { recursive: true });
const detect = JSON.parse(readFileSync(detectFile, 'utf8'));
const todo = Object.entries(detect).filter(([, r]) => r.cls === 'side' || r.cls === 'bottom').map(([f]) => f);
const P = { size: 400, white: 235, minArea: 0.002, margin: 0.08 };

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();

const crop = (b64, p) => page.evaluate(async ([b64, p]) => {
  const S = p.size;

  function components(ctx) {
    const d = ctx.getImageData(0, 0, S, S).data;
    const mask = new Uint8Array(S * S);
    for (let i = 0; i < S * S; i++) mask[i] = d[i * 4] >= p.white && d[i * 4 + 1] >= p.white && d[i * 4 + 2] >= p.white ? 0 : 1;
    const label = new Int32Array(S * S), stack = new Int32Array(S * S), comps = [];
    for (let s0 = 0; s0 < S * S; s0++) {
      if (!mask[s0] || label[s0]) continue;
      const id = comps.length + 1;
      let sp = 0, area = 0, minX = S, maxX = 0, minY = S, maxY = 0;
      stack[sp++] = s0; label[s0] = id;
      while (sp) {
        const q = stack[--sp], x = q % S, y = (q / S) | 0;
        area++; if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y;
        if (x > 0 && mask[q - 1] && !label[q - 1]) { label[q - 1] = id; stack[sp++] = q - 1; }
        if (x < S - 1 && mask[q + 1] && !label[q + 1]) { label[q + 1] = id; stack[sp++] = q + 1; }
        if (y > 0 && mask[q - S] && !label[q - S]) { label[q - S] = id; stack[sp++] = q - S; }
        if (y < S - 1 && mask[q + S] && !label[q + S]) { label[q + S] = id; stack[sp++] = q + S; }
      }
      comps.push({ id, area, minX, maxX, minY, maxY, w: maxX - minX + 1, h: maxY - minY + 1 });
    }
    return comps.filter((k) => k.area >= p.minArea * S * S);
  }

  // The panel group (the strip plus whatever sits in its column or row), or null when nothing qualifies.
  function findPanel(big) {
    const fill = (k) => k.area / (k.w * k.h);
    const group = new Set();
    // Side strip: tall, narrow-ish, solid, in the right part, with the rest of the content to its left.
    const sides = big.filter((k) => k.h >= 0.4 * S && k.w >= 0.06 * S && k.w <= 0.3 * S && k.minX >= 0.55 * S && fill(k) >= 0.5)
      .sort((a, b) => b.area - a.area);
    for (const strip of sides) {
      const inColumn = big.filter((k) => k.minX >= strip.minX - 0.04 * S);
      const rest = big.filter((k) => !inColumn.includes(k));
      if (!rest.length || Math.max(...rest.map((k) => k.maxX)) > strip.minX + 0.02 * S) continue;
      inColumn.forEach((k) => group.add(k));
      break;
    }
    // Bottom strip: wide, thick, solid, in the lower part, with the rest of the content above it.
    const bottoms = big.filter((k) => k.w >= 0.4 * S && k.h >= 0.1 * S && k.minY >= 0.6 * S && fill(k) >= 0.5 && !group.has(k))
      .sort((a, b) => b.area - a.area);
    for (const strip of bottoms) {
      const inRow = big.filter((k) => k.minY >= strip.minY - 0.04 * S);
      const rest = big.filter((k) => !inRow.includes(k) && !group.has(k));
      if (!rest.length || Math.max(...rest.map((k) => k.maxY)) > strip.minY + 0.02 * S) continue;
      inRow.forEach((k) => group.add(k));
      break;
    }
    return group.size ? group : null;
  }

  const img = new Image();
  img.src = 'data:image/png;base64,' + b64;
  await img.decode();
  const W = img.naturalWidth, H = img.naturalHeight;
  const c = document.createElement('canvas'); c.width = S; c.height = S;
  const ctx = c.getContext('2d', { willReadFrequently: true });
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, S, S); ctx.drawImage(img, 0, 0, S, S);
  const big = components(ctx);
  if (!big.length) return { status: 'empty' };
  const panel = findPanel(big);
  if (!panel) return { status: 'no-separate-panel', components: big.length };
  const keep = big.filter((k) => !panel.has(k));
  if (!keep.length) return { status: 'no-separate-panel', components: big.length };

  const minX = Math.min(...keep.map((k) => k.minX)), maxX = Math.max(...keep.map((k) => k.maxX));
  const minY = Math.min(...keep.map((k) => k.minY)), maxY = Math.max(...keep.map((k) => k.maxY));
  const sx = W / S, sy = H / S;
  const bx = Math.floor(minX * sx), by = Math.floor(minY * sy);
  const bw = Math.ceil((maxX + 1) * sx) - bx, bh = Math.ceil((maxY + 1) * sy) - by;
  const side = Math.ceil(Math.max(bw, bh) / (1 - 2 * p.margin));
  const out = document.createElement('canvas'); out.width = side; out.height = side;
  const o = out.getContext('2d');
  o.fillStyle = '#fff'; o.fillRect(0, 0, side, side);
  o.drawImage(img, bx, by, bw, bh, Math.round((side - bw) / 2), Math.round((side - bh) / 2), bw, bh);

  // Verify the OUTPUT: run the same panel search on it. A panel that survives means the crop did not work.
  const vc = document.createElement('canvas'); vc.width = S; vc.height = S;
  const vctx = vc.getContext('2d', { willReadFrequently: true });
  vctx.fillStyle = '#fff'; vctx.fillRect(0, 0, S, S); vctx.drawImage(out, 0, 0, S, S);
  const left = findPanel(components(vctx));
  if (left) return { status: 'leftover-panel', panelComponents: panel.size, leftover: left.size, productBox: [bx, by, bw, bh], source: [W, H] };

  return { status: 'cropped', panelComponents: panel.size, productBox: [bx, by, bw, bh], source: [W, H], outSize: side, data: out.toDataURL('image/jpeg', 0.92).split(',')[1] };
}, [b64, p]);

const report = {};
for (const f of todo) {
  const r = await crop(readFileSync(join(dir, f)).toString('base64'), P);
  if (r.status === 'cropped') { writeFileSync(join(outDir, f), Buffer.from(r.data, 'base64')); delete r.data; }
  report[f] = r;
}
await browser.close();
writeFileSync(join(dirname(outDir), 'crop-report.json'), JSON.stringify(report, null, 2));
const counts = {};
for (const r of Object.values(report)) counts[r.status] = (counts[r.status] || 0) + 1;
console.log(`${todo.length} images with a panel:`, JSON.stringify(counts));
