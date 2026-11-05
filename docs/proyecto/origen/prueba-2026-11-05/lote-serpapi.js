// Uso: node lote-serpapi.js <archivo-key> <seleccion.json> <carpeta-salida> <candidatos-por-ean> [<ean-a-saltear>...]
// Por cada articulo: busca "EAN + nombre normalizado" en SerpApi google_images, descarta redes sociales y paginas repetidas,
// baja los primeros N candidatos y escribe resumen.json. Nunca imprime la key.
const fs = require('fs');
const path = require('path');
const [keyFile, seleccionFile, outDir, nArg, ...saltear] = process.argv.slice(2);
const N = Number(nArg || 3);
const apiKey = fs.readFileSync(keyFile, 'utf8').trim();
if (!/^[0-9a-f]{64}$/i.test(apiKey)) { console.error('Formato de key inesperado'); process.exit(1); }
const SOCIALES = /(instagram|facebook|pinterest|tiktok|youtube|twitter|x\.com|reddit)\./i;

function nombreNormalizado(d) {
  return d.replace(/(\d+(?:GR|G|ML|KG|L|CC))(?:X\d+)+$/i, '$1').replace(/\s+/g, ' ').trim().toLowerCase();
}

(async () => {
  fs.mkdirSync(outDir, { recursive: true });
  const articulos = JSON.parse(fs.readFileSync(seleccionFile, 'utf8')).filter((a) => !saltear.includes(a.ean));
  const resumen = [];
  for (const a of articulos) {
    const q = `${a.ean} ${nombreNormalizado(a.descripcion)}`;
    const params = new URLSearchParams({ engine: 'google_images', q, gl: 'ar', hl: 'es', google_domain: 'google.com.ar', api_key: apiKey });
    let json;
    try {
      const res = await fetch('https://serpapi.com/search.json?' + params.toString());
      const texto = await res.text();
      if (texto.includes(apiKey)) throw new Error('la respuesta contiene la key');
      json = JSON.parse(texto);
      if (!res.ok || json.error) { console.log(`${a.ean}: HTTP ${res.status} ${json.error || ''}`); continue; }
    } catch (e) { console.log(`${a.ean}: fallo (${e.message})`); continue; }
    fs.writeFileSync(path.join(outDir, `serpapi-${a.ean}.json`), JSON.stringify(json));
    const todos = json.images_results || [];
    const vistos = new Set();
    const candidatos = [];
    for (const x of todos) {
      const pagina = (x.link || '').split('?')[0];
      if (SOCIALES.test(x.link || '') || vistos.has(pagina)) continue;
      vistos.add(pagina);
      candidatos.push(x);
      if (candidatos.length >= N) break;
    }
    const fila = { ean: a.ean, descripcion: a.descripcion, consulta: q, totalResultados: todos.length, candidatos: [] };
    for (const c of candidatos) {
      const item = { posicion: c.position, fuente: c.source, titulo: c.title, pagina: c.link, ancho: c.original_width, alto: c.original_height, archivo: null, estado: '' };
      try {
        const r = await fetch(c.original, { signal: AbortSignal.timeout(20000), headers: { 'User-Agent': 'Mozilla/5.0' } });
        const tipo = r.headers.get('content-type') || '';
        if (!r.ok || !tipo.startsWith('image/')) item.estado = `descarga: HTTP ${r.status} ${tipo}`;
        else {
          const buf = Buffer.from(await r.arrayBuffer());
          if (buf.length > 4 * 1024 * 1024) item.estado = 'demasiado grande';
          else {
            const ext = tipo.includes('png') ? 'png' : tipo.includes('webp') ? 'webp' : 'jpg';
            item.archivo = path.join(outDir, `${a.ean}-${candidatos.indexOf(c) + 1}-${String(c.source).replace(/[^a-z0-9]+/gi, '_').slice(0, 20)}.${ext}`);
            fs.writeFileSync(item.archivo, buf);
            item.estado = 'ok';
          }
        }
      } catch (e) { item.estado = `descarga fallo (${e.name})`; }
      fila.candidatos.push(item);
    }
    resumen.push(fila);
    console.log(`\n${a.ean} | ${a.descripcion} | consulta: "${q}" | resultados: ${todos.length}`);
    fila.candidatos.forEach((c, i) => console.log(`  ${i + 1}. [${c.estado}] ${c.fuente} | ${c.ancho}x${c.alto} | ${String(c.titulo).slice(0, 60)}${c.archivo ? '\n     ' + c.archivo : ''}`));
  }
  fs.writeFileSync(path.join(outDir, 'resumen.json'), JSON.stringify(resumen, null, 2));
})();
