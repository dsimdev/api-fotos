// Throwaway: remove the banner template panel from product images and re-center the product on a white square.
// Usage: node crop-banner.mjs <images-dir> <banner-detect.json> <out-dir>
// Only images the detector marked side|bottom are processed. How it works (all in Chromium on a canvas):
//   1. non-white mask at 400x400, connected components (4-connectivity, no gap filling);
//   2. main = the largest component (the product). The panel is a SEPARATE component to the right (tall) or
//      below (wide) the product, plus the small badge next to it;
//   3. the product bounding box = every component that is not panel; copy ONLY that rectangle from the
//      ORIGINAL pixels onto a white square (no upscaling, 8% margin).
// Safety: if the panel is not a separate component (it touches the product) nothing is cropped
// (status no-separate-panel). The product is never cut: only its bounding box is copied.
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
  const img = new Image();
  img.src = 'data:image/png;base64,' + b64;
  await img.decode();
  const W = img.naturalWidth, H = img.naturalHeight, S = p.size;
  const c = document.createElement('canvas'); c.width = S; c.height = S;
  const ctx = c.getContext('2d', { willReadFrequently: true });
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, S, S); ctx.drawImage(img, 0, 0, S, S);
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
    comps.push({ id, area, minX, maxX, minY, maxY });
  }
  const big = comps.filter((k) => k.area >= p.minArea * S * S);
  if (!big.length) return { status: 'empty' };
  const main = big.reduce((a, b) => (b.area > a.area ? b : a));
  const panel = new Set();
  const sideTall = big.filter((k) => k !== main && k.minX >= 0.55 * S && k.minX >= main.maxX - 0.02 * S && k.maxY - k.minY >= 0.4 * S);
  if (sideTall.length) {
    const left = Math.min(...sideTall.map((k) => k.minX));
    big.filter((k) => k !== main && k.minX >= left - 0.04 * S && k.minX >= main.maxX - 0.02 * S).forEach((k) => panel.add(k));
  }
  const bottomWide = big.filter((k) => k !== main && k.minY >= 0.7 * S && k.minY >= main.maxY - 0.02 * S && k.maxX - k.minX >= 0.4 * S);
  if (bottomWide.length) {
    const top = Math.min(...bottomWide.map((k) => k.minY));
    big.filter((k) => k !== main && k.minY >= top - 0.04 * S && k.minY >= main.maxY - 0.02 * S).forEach((k) => panel.add(k));
  }
  if (!panel.size) return { status: 'no-separate-panel', components: big.length };
  const keep = big.filter((k) => !panel.has(k));
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
