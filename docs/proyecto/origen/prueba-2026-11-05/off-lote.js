// Uso: node off-lote.js <seleccion.json>   -> consulta Open Food Facts por EAN (10 GET, con pausa)
const fs = require('fs');
const sel = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const pausa = (ms) => new Promise((r) => setTimeout(r, ms));
(async () => {
  let encontrados = 0, conImagen = 0;
  for (const a of sel) {
    const url = `https://world.openfoodfacts.org/api/v2/product/${a.ean}.json?fields=code,product_name,brands,image_front_url`;
    try {
      const res = await fetch(url, { headers: { 'User-Agent': 'axum-imagenes-prueba/0.1 (prueba interna de cobertura)' }, signal: AbortSignal.timeout(20000) });
      const j = await res.json();
      const p = j.product;
      const hit = j.status === 1 && p;
      if (hit) { encontrados++; if (p.image_front_url) conImagen++; }
      console.log(`${a.ean} | HTTP ${res.status} | ${hit ? 'ENCONTRADO: ' + (p.product_name || '(sin nombre)') + ' | imagen frontal: ' + (p.image_front_url ? 'si' : 'no') : 'no esta'}`);
    } catch (e) { console.log(`${a.ean} | fallo (${e.name})`); }
    await pausa(1500);
  }
  console.log(`\nEncontrados: ${encontrados}/${sel.length} | con imagen frontal: ${conImagen}`);
})();
