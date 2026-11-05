# Mediciones

## Fase A — de dónde salen los candidatos (2026-11-05)

Desde la PC del usuario (IP de casa u oficina, **no** de datacenter), con Node 22 y Playwright 1.64.1
(Chromium headless shell 156). Muestra: los 10 EAN de dyssa de
`origen/prueba-2026-11-05/dyssa-seleccion.json`. Código descartable en `spikes/measure-sources/`
(rama `spike/measure-sources`); lo crudo (HTML, capturas, imágenes) queda en `out/`, ignorado por git.

**Regla de la medición:** navegador en su configuración normal, sin plugins de sigilo, sin falsear la
huella, sin resolver CAPTCHAs y sin proxies. Un bloqueo es un resultado, no un obstáculo: se paró ahí.

### Resumen

| Fuente | Resultado |
|---|---|
| **Google, HTTP simple** | 302 al formato nuevo (`udm=2`) y después 200 con la página de desafío de JavaScript (`SG_SS`, `enablejs`): **0 imágenes**. 2 requests |
| **Google, navegador headless** | **Bloqueo en la primera request**: redirige a `/sorry/` con reCAPTCHA (5,9 s). 1 request. No se probaron variantes |
| **Bing, HTTP simple** | 1 resultado y no era del producto. 1 request. No se probó con navegador |
| **VTEX por EAN** (Jumbo, Carrefour, Disco, Vea, Masonline, Día) | **9 de 10 EAN con coincidencia exacta** en al menos una tienda. 60 requests, p50 523 ms, p95 738 ms, sin errores ni bloqueos |

### VTEX por EAN

`GET https://<tienda>/api/catalog_system/pub/products/search?fq=alternateIds_Ean:<ean>` devuelve el producto
cuyo `items[].ean` coincide con el EAN pedido, con las URLs de sus imágenes (JPG de 1000×1000 o 2000×2000).

- **Productos equivocados: 0.** El EAN coincide exacto, así que desaparece el error típico de la prueba con
  Google (la variante equivocada, 20% de los candidatos).
- Cobertura por tienda: Jumbo 9, Disco 9, Vea 9, Carrefour 8, Masonline 8, Día 0. **Jumbo, Disco y Vea
  devuelven exactamente el mismo archivo** (mismo hash), así que cuentan como una sola fuente de imágenes.
- **No está en ninguna:** `7798126073437` (Gaona Sabores de Vida chips DDL). Marca más chica.

### Calidad de la imagen

Primera imagen de cada tienda, mirada una por una **por Claude** (sin independencia: ver `CLAUDE.md` sección 9).
"Banner" = panel lateral o inferior con el nombre y el peso, parte de la plantilla de la imagen.

| EAN | Producto | Jumbo = Disco = Vea | Carrefour | Masonline |
|---|---|---|---|---|
| 7790070035707 | Patitas de pollo picantes 400 g | banner lateral | banner lateral | banner lateral |
| 7790070104311 | Cocinero oliva 900 ml | banner lateral | no está | **limpia** |
| 7790093527784 | Cafayate Terroir Torrontés | **limpia** | sin ver | sin ver |
| 7790070337016 | Matarazzo lasagna 250 g | banner inferior | banner inferior | **limpia** (más borrosa) |
| 7790070433022 | Exquisita choco-latada 300 g | banner lateral | banner lateral | **limpia** (otro diseño de envase, con cartel "NUEVA") |
| 7790070760746 | Emilia Chardonnay | **limpia** | sin ver | sin ver |
| 7798126073437 | Gaona chips DDL | no está | no está | no está |
| 7793913002447 | Tregar leche 0% | **limpia** | sin ver | no está |
| 7790310984314 | Pep rueditas 74 g | banner lateral | banner lateral | banner lateral |
| 7790310985502 | Lay's ketchup 77 g | banner lateral | banner lateral | banner lateral |

- **Con al menos una imagen limpia: 6 de 10** (5 de 9 si no se cuenta la de Exquisita, que es de otro diseño de envase).
- **Banner en todas las tiendas vistas: 3** (Patitas, Pep, Lay's). Pep y Lay's tienen más de una imagen por tienda
  (hasta 7 en Carrefour); **no se miraron**.
- El banner no es de Jumbo: lo traen también Carrefour y Masonline, así que parece una plantilla que reparten las marcas.

### Lo que no se probó

- Recortar el panel. En las 5 imágenes de Jumbo con banner lateral que se vieron, la bolsa no cruza el panel
  (empieza en x≈820 de 1000 en Patitas, Cocinero y Exquisita, y en x≈725 en Pep y Lay's), así que un recorte podría
  alcanzar. **Es una hipótesis**, la posición cambia por producto y el banner de Matarazzo es inferior.
- Las imágenes adicionales de cada producto.
- Otras tiendas VTEX (por ejemplo Farmacity o Hiperlibertad) para cubrir marcas chicas como Gaona.
- Google desde una IP de datacenter: no se hizo porque desde la PC ya bloqueó, y una IP de datacenter suele ser peor.
- Los términos de uso de las APIs de VTEX de cada tienda, y los derechos de las imágenes (son de las marcas y los comercios).

### Cómo repetirlo

```
cd spikes/measure-sources
npm install && npx playwright install chromium
node probe.mjs <arm> <ean> "<consulta>"        # arms: google-http | google-browser | bing-http | vtex:<host>
node measure-vtex.mjs                          # los 10 EAN contra 6 tiendas VTEX
node download-store-images.mjs                 # baja la imagen de cada tienda y agrupa las idénticas por hash
```

**No volver a correr `google-http` ni `google-browser` sin una decisión explícita:** cada intento contra Google
puede dejar la IP marcada.
