// Uso: node bajar-imagenes.js <json-serpapi> <carpeta-salida> <prefijo> <posicion> [<posicion>...]
// Baja la imagen "original" de cada posicion pedida. Solo acepta image/*, maximo 4 MB, 20 s de timeout.
const fs = require('fs');
const path = require('path');
const [jsonFile, outDir, prefijo, ...posiciones] = process.argv.slice(2);
const r = JSON.parse(fs.readFileSync(jsonFile, 'utf8')).images_results || [];
fs.mkdirSync(outDir, { recursive: true });
(async () => {
  for (const p of posiciones.map(Number)) {
    const item = r.find((x) => x.position === p);
    if (!item) { console.log(`pos ${p}: no existe`); continue; }
    try {
      const res = await fetch(item.original, { signal: AbortSignal.timeout(20000), headers: { 'User-Agent': 'Mozilla/5.0' } });
      const tipo = res.headers.get('content-type') || '';
      if (!res.ok || !tipo.startsWith('image/')) { console.log(`pos ${p} (${item.source}): HTTP ${res.status}, tipo ${tipo} -> descartada`); continue; }
      const buf = Buffer.from(await res.arrayBuffer());
      if (buf.length > 4 * 1024 * 1024) { console.log(`pos ${p}: ${buf.length} bytes, demasiado grande`); continue; }
      const ext = tipo.includes('png') ? 'png' : tipo.includes('webp') ? 'webp' : 'jpg';
      const archivo = path.join(outDir, `${prefijo}-${p}-${item.source.replace(/[^a-z0-9]+/gi, '_')}.${ext}`);
      fs.writeFileSync(archivo, buf);
      console.log(`pos ${p} (${item.source}): ${tipo}, ${buf.length} bytes -> ${archivo}`);
    } catch (e) {
      console.log(`pos ${p} (${item.source}): fallo (${e.name})`);
    }
  }
})();
