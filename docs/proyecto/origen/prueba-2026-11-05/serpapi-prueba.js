// Prueba de SerpApi google_images. Uso: node serpapi-prueba.js <archivo-con-la-key> <carpeta-salida> <consulta> [<consulta>...]
// Nunca imprime la key ni la URL con la key.
const fs = require('fs');
const path = require('path');

const [keyFile, outDir, ...consultas] = process.argv.slice(2);
const apiKey = fs.readFileSync(keyFile, 'utf8').trim();
if (!/^[0-9a-f]{64}$/i.test(apiKey)) { console.error('La key no tiene el formato esperado'); process.exit(1); }

(async () => {
  for (const q of consultas) {
    const params = new URLSearchParams({ engine: 'google_images', q, gl: 'ar', hl: 'es', google_domain: 'google.com.ar', api_key: apiKey });
    let res, texto;
    try {
      res = await fetch('https://serpapi.com/search.json?' + params.toString());
      texto = await res.text();
    } catch (e) {
      console.log(`\n=== "${q}" -> fallo de red (${e.name})`);
      continue;
    }
    if (texto.includes(apiKey)) { console.log('La respuesta contiene la key, no se guarda'); continue; }
    const archivo = path.join(outDir, 'serpapi-' + q.replace(/[^a-z0-9]+/gi, '_').slice(0, 60) + '.json');
    fs.writeFileSync(archivo, texto);
    let json;
    try { json = JSON.parse(texto); } catch { console.log(`\n=== "${q}" -> HTTP ${res.status}, respuesta no JSON`); continue; }
    console.log(`\n=== "${q}" -> HTTP ${res.status}  estado: ${json.search_metadata && json.search_metadata.status}  error: ${json.error || '-'}`);
    const r = json.images_results || [];
    console.log(`resultados: ${r.length}`);
    r.slice(0, 12).forEach((x) => {
      console.log(`${String(x.position).padStart(2)} | ${x.original_width}x${x.original_height} | prod:${x.is_product ? 'si' : 'no'} | ${x.source} | ${String(x.title).slice(0, 70)}`);
      console.log(`     página: ${x.link}`);
    });
  }
})();
