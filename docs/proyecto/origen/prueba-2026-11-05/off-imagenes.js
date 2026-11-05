// Uso: node off-imagenes.js <carpeta-salida> <ean> [<ean>...]  -> baja la imagen frontal de Open Food Facts
const fs = require('fs');
const path = require('path');
const [outDir, ...eans] = process.argv.slice(2);
fs.mkdirSync(outDir, { recursive: true });
const H = { 'User-Agent': 'axum-imagenes-prueba/0.1 (prueba interna de cobertura)' };
(async () => {
  for (const ean of eans) {
    const j = await (await fetch(`https://world.openfoodfacts.org/api/v2/product/${ean}.json?fields=image_front_url`, { headers: H })).json();
    const url = j.product && j.product.image_front_url;
    if (!url) { console.log(ean, 'sin imagen'); continue; }
    const r = await fetch(url, { headers: H, signal: AbortSignal.timeout(20000) });
    const tipo = r.headers.get('content-type') || '';
    const buf = Buffer.from(await r.arrayBuffer());
    const ext = tipo.includes('png') ? 'png' : tipo.includes('webp') ? 'webp' : 'jpg';
    const f = path.join(outDir, `off-${ean}.${ext}`);
    fs.writeFileSync(f, buf);
    console.log(ean, tipo, buf.length, 'bytes ->', f);
    await new Promise((x) => setTimeout(x, 1500));
  }
})();
