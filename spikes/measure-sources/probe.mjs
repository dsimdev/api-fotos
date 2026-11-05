// Throwaway probe: ONE request per arm, to see what each source really answers before building extractors.
// Usage: node probe.mjs <arm> <ean> "<query>"
// Arms: google-http | google-browser | bing-http | vtex:<host>   (e.g. vtex:www.jumbo.com.ar)
// Raw responses go to out/probe/ (git-ignored). Nothing here evades detection: no stealth plugins,
// no fingerprint changes, no CAPTCHA solving, no proxies. A block is a result, not an obstacle.
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [arm, ean, query] = process.argv.slice(2);
if (!arm || !ean) {
  console.error('Usage: node probe.mjs <arm> <ean> "<query>"');
  process.exit(1);
}
const q = query || ean;
const outDir = join(import.meta.dirname, 'out', 'probe');
mkdirSync(outDir, { recursive: true });
const tag = `${arm.replace(/[^a-z0-9]+/gi, '_')}-${ean}`;

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';
const HEADERS = { 'User-Agent': UA, 'Accept-Language': 'es-AR,es;q=0.9', Accept: 'text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8' };

const BLOCK_SIGNS = [
  ['/sorry/', 'redirect to /sorry/ (Google block page)'],
  ['unusual traffic', 'unusual traffic notice'],
  ['g-recaptcha', 'reCAPTCHA present'],
  ['recaptcha', 'reCAPTCHA mention'],
  ['enablejs', 'JavaScript-required page'],
  ['captcha', 'captcha mention'],
];
const signs = (text, url = '') => BLOCK_SIGNS.filter(([s]) => (url + text).toLowerCase().includes(s)).map(([, d]) => d);

async function googleHttp() {
  const url = `https://www.google.com.ar/search?q=${encodeURIComponent(q)}&udm=2&hl=es&gl=ar`;
  const t0 = Date.now();
  const res = await fetch(url, { headers: HEADERS, redirect: 'manual', signal: AbortSignal.timeout(20000) });
  const body = await res.text();
  writeFileSync(join(outDir, `${tag}.html`), body);
  return {
    url, status: res.status, ms: Date.now() - t0, bytes: body.length,
    location: res.headers.get('location'),
    signs: signs(body, res.headers.get('location') || ''),
    imgTags: (body.match(/<img\b/g) || []).length,
    imgresLinks: (body.match(/imgres\?/g) || []).length,
  };
}

async function googleBrowser() {
  const { chromium } = await import('playwright');
  const url = `https://www.google.com.ar/search?q=${encodeURIComponent(q)}&udm=2&hl=es&gl=ar`;
  const browser = await chromium.launch({ headless: true });
  try {
    // Plain context: real locale/timezone of the user, Playwright's default (headless) user agent, nothing hidden.
    const ctx = await browser.newContext({ locale: 'es-AR', timezoneId: 'America/Argentina/Buenos_Aires', viewport: { width: 1366, height: 900 } });
    const page = await ctx.newPage();
    const t0 = Date.now();
    const resp = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
    const html = await page.content();
    writeFileSync(join(outDir, `${tag}.html`), html);
    await page.screenshot({ path: join(outDir, `${tag}.png`), fullPage: false });
    const finalUrl = page.url();
    return {
      url, status: resp?.status(), ms: Date.now() - t0, bytes: html.length, finalUrl, title: await page.title(),
      userAgent: await page.evaluate(() => navigator.userAgent),
      signs: signs(html, finalUrl),
      imgTags: await page.locator('img').count(),
      imgresLinks: await page.locator('a[href*="imgres"]').count(),
    };
  } finally {
    await browser.close();
  }
}

async function bingHttp() {
  const url = `https://www.bing.com/images/search?q=${encodeURIComponent(q)}&setlang=es-AR&cc=AR`;
  const t0 = Date.now();
  const res = await fetch(url, { headers: HEADERS, redirect: 'follow', signal: AbortSignal.timeout(20000) });
  const body = await res.text();
  writeFileSync(join(outDir, `${tag}.html`), body);
  const tiles = [...body.matchAll(/class="iusc"[^>]*\sm="([^"]+)"/g)].map((m) => {
    try {
      const j = JSON.parse(m[1].replace(/&quot;/g, '"').replace(/&amp;/g, '&'));
      return { image: j.murl, page: j.purl, title: j.t };
    } catch { return null; }
  }).filter(Boolean);
  return { url, status: res.status, ms: Date.now() - t0, bytes: body.length, signs: signs(body), tiles: tiles.length, sample: tiles.slice(0, 3) };
}

async function vtex(host) {
  const url = `https://${host}/api/catalog_system/pub/products/search?fq=alternateIds_Ean:${encodeURIComponent(ean)}`;
  const t0 = Date.now();
  const res = await fetch(url, { headers: HEADERS, signal: AbortSignal.timeout(20000) });
  const body = await res.text();
  writeFileSync(join(outDir, `${tag}.json`), body);
  let products = null;
  try { products = JSON.parse(body); } catch { /* not JSON */ }
  const first = Array.isArray(products) ? products[0] : null;
  return {
    url, status: res.status, ms: Date.now() - t0, bytes: body.length,
    isJson: Array.isArray(products), products: Array.isArray(products) ? products.length : null,
    sample: first ? { name: first.productName, brand: first.brand, link: first.link, images: (first.items?.[0]?.images || []).map((i) => i.imageUrl).slice(0, 2), eans: (first.items || []).map((i) => i.ean) } : null,
  };
}

const run = {
  'google-http': googleHttp,
  'google-browser': googleBrowser,
  'bing-http': bingHttp,
}[arm] || (arm.startsWith('vtex:') ? () => vtex(arm.slice(5)) : null);

if (!run) { console.error(`Unknown arm: ${arm}`); process.exit(1); }
try {
  console.log(JSON.stringify({ arm, ean, query: q, ...(await run()) }, null, 2));
} catch (e) {
  console.log(JSON.stringify({ arm, ean, query: q, error: `${e.name}: ${e.message}` }, null, 2));
  process.exitCode = 2;
}
