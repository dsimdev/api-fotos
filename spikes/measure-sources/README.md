# spikes/measure-sources

Scripts de Node de **medición**, escritos para decidir de dónde salen las fotos y cómo limpiarlas. **No son el producto**: lo
que se mide acá ya está portado a Java (`fotos-core`, `fotos-stores`, `fotos-tool`) y la herramienta no depende de nada de
esta carpeta. Se conservan para poder **repetir las mediciones** de `docs/proyecto/mediciones.md`.

Lo que tienen de importante:

- La salida va a `out/` (ignorada por git: son imágenes de terceros y datos de un cliente). Hay que correr los scripts en el
  orden de la sección "Cómo repetirlo" de cada fase de `mediciones.md`. Requieren la planilla del cliente, que **no está en
  el repo**: hay que pedirla de nuevo.
- Usan Chromium (Playwright) para decodificar imágenes en un canvas; `npm install` en esta carpeta lo trae.
- `crop-banner.mjs` es la **versión 2** del recortador (busca el panel por su forma y verifica la salida); la primera dejó el
  panel puesto en 8 fotos. El port a Java coincide con esta versión en 670 de 671 resultados sobre 791 imágenes reales.
- `make-gallery.mjs` y `review-sheets.mjs` arman la entrega del **prototipo**, con la regla de elección anterior (una original
  le gana a una recortada). La regla vigente, por capas (borde, nitidez, mayoría entre tiendas, resolución...), solo existe en
  Java (`CandidatePicker`): no uses estos scripts para decidir qué foto entregar.
- Cuidado con las tiendas reales. Solo `measure-vtex.mjs` se detiene solo ante un 401, 403 o 429. `download-store-images.mjs`
  únicamente pausa entre descargas y `probe.mjs` no hace ninguna de las dos cosas. Para consultar por EAN con límites de
  ritmo, reintento y cortacircuito, usá la herramienta (`fotos-stores`), no estos scripts.
