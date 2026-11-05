// Throwaway: detect the "banner template" (a near-solid side or bottom panel with the product name and weight)
// in product images, and compare the detector with hand labels when a labels file is given.
// Usage: node detect-banner.mjs <images-dir> [labels.json]
// Decoding is done by Chromium (Playwright) on a canvas, so no image library is needed.
// Classes: side | bottom | none | unknown (unknown = the frame of the image is not white, so it is not a
// white-background catalog shot and the heuristic does not apply).
// Output: <images-dir>/../banner-detect.json (or $DETECT_OUT)
// ASSUMPTION: the product does not reach the right 35% / bottom 30% of the image. True for the catalog shots with
// margins, FALSE for crops that re-center the product at full size: do not use this detector to verify those.
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { chromium } from 'playwright';

const [dir, labelsFile] = process.argv.slice(2);
if (!dir) { console.error('Usage: node detect-banner.mjs <images-dir> [labels.json]'); process.exit(1); }
const labels = labelsFile ? Object.fromEntries(Object.entries(JSON.parse(readFileSync(labelsFile, 'utf8'))).filter(([k]) => !k.startsWith('_'))) : {};

// gap: white runs shorter than this fraction of the width, with colour on BOTH sides, count as colour (the white
// text inside a panel). 0 turns it off. Any value can be overridden with PARAMS='{"fill":0.6}' for tuning.
const PARAMS = { size: 400, white: 235, frame: 0.03, frameWhiteMin: 0.8, sideFrom: 0.65, bottomFrom: 0.7, fill: 0.6, minRun: 0.06, gap: 0.08, ...JSON.parse(process.env.PARAMS || '{}') };

const files = readdirSync(dir).filter((f) => /\.(jpe?g|png|webp)$/i.test(f)).sort();
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
await page.setContent('<canvas id="c"></canvas>');

const analyze = (b64, p) => page.evaluate(async ([b64, p]) => {
  const img = new Image();
  img.src = 'data:image/png;base64,' + b64;
  await img.decode();
  const S = p.size;
  const c = document.getElementById('c'); c.width = S; c.height = S;
  const ctx = c.getContext('2d', { willReadFrequently: true });
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, S, S);
  ctx.drawImage(img, 0, 0, S, S);
  const d = ctx.getImageData(0, 0, S, S).data;
  const non = new Uint8Array(S * S);
  for (let i = 0; i < S * S; i++) non[i] = d[i * 4] >= p.white && d[i * 4 + 1] >= p.white && d[i * 4 + 2] >= p.white ? 0 : 1;
  const maxGap = Math.round(S * p.gap);
  if (maxGap > 0) {
    for (let y = 0; y < S; y++) {
      let x = 0;
      while (x < S) {
        if (non[y * S + x]) { x++; continue; }
        let e = x;
        while (e < S && !non[y * S + e]) e++;
        if (x > 0 && e < S && e - x <= maxGap) for (let k = x; k < e; k++) non[y * S + k] = 2;
        x = e;
      }
    }
  }
  const f = Math.max(1, Math.round(S * p.frame));
  let frameN = 0, frameW = 0;
  // Only the LEFT and TOP edges: the banner panels touch the right and bottom edges, which would make a
  // genuine banner image look like a non-white background.
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) if (x < f || y < f) { frameN++; if (!non[y * S + x]) frameW++; }
  const frameWhite = frameW / frameN;
  const col = new Float32Array(S), row = new Float32Array(S);
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) { if (non[y * S + x]) { col[x]++; row[y]++; } }
  for (let i = 0; i < S; i++) { col[i] /= S; row[i] /= S; }
  const run = (arr, from) => { let best = 0, bestStart = -1, cur = 0, curStart = 0; for (let i = Math.floor(from * S); i < S; i++) { if (arr[i] >= p.fill) { if (!cur) curStart = i; cur++; if (cur > best) { best = cur; bestStart = curStart; } } else cur = 0; } return { len: best / S, start: bestStart < 0 ? null : bestStart / S }; };
  return { frameWhite, w: img.naturalWidth, h: img.naturalHeight, side: run(col, p.sideFrom), bottom: run(row, p.bottomFrom) };
}, [b64, p]);

const results = {};
for (const f of files) {
  const r = await analyze(readFileSync(join(dir, f)).toString('base64'), PARAMS);
  let cls = 'none', cropBox = null;
  if (r.frameWhite < PARAMS.frameWhiteMin) cls = 'unknown';
  else if (r.side.len >= PARAMS.minRun) { cls = 'side'; cropBox = { x: 0, y: 0, w: r.side.start, h: 1 }; }
  else if (r.bottom.len >= PARAMS.minRun) { cls = 'bottom'; cropBox = { x: 0, y: 0, w: 1, h: r.bottom.start }; }
  results[f] = { cls, w: r.w, h: r.h, frameWhite: +r.frameWhite.toFixed(3), sideRun: +r.side.len.toFixed(3), bottomRun: +r.bottom.len.toFixed(3), cropBox };
}
await browser.close();
// DETECT_OUT overrides the output path. Default: next to the images folder, which OVERWRITES the previous
// banner-detect.json of that run: pass DETECT_OUT when analysing a second folder.
writeFileSync(process.env.DETECT_OUT || join(dirname(dir), 'banner-detect.json'), JSON.stringify(results, null, 2));

const counts = {};
for (const r of Object.values(results)) counts[r.cls] = (counts[r.cls] || 0) + 1;
console.log(`${files.length} images:`, JSON.stringify(counts));

if (Object.keys(labels).length) {
  const m = { tp: 0, fp: 0, tn: 0, fn: 0, unknown: 0 };
  const wrong = [];
  for (const [f, truth] of Object.entries(labels)) {
    const r = results[f];
    if (!r) { console.log(`label without image: ${f}`); continue; }
    if (r.cls === 'unknown') { m.unknown++; wrong.push(`${f}: truth=${truth} detector=unknown`); continue; }
    const pred = r.cls === 'none' ? 'clean' : 'banner';
    if (truth === 'banner' && pred === 'banner') m.tp++;
    else if (truth === 'banner') { m.fn++; wrong.push(`${f}: truth=banner detector=clean (side ${r.sideRun}, bottom ${r.bottomRun})`); }
    else if (pred === 'banner') { m.fp++; wrong.push(`${f}: truth=clean detector=${r.cls} (side ${r.sideRun}, bottom ${r.bottomRun})`); }
    else m.tn++;
  }
  console.log('vs hand labels:', JSON.stringify(m), `| agree ${m.tp + m.tn}/${Object.keys(labels).length}`);
  wrong.forEach((w) => console.log('  disagreement:', w));
}
