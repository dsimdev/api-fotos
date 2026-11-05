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

### Lo que no se probó (al cierre de la fase A; las tiendas VTEX extra y las imágenes adicionales se midieron en A2)

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

## Fase A2 — muestra grande de VTEX (2026-11-05)

40 EAN **nuevos** de dyssa (`spikes/measure-sources/sample-dyssa-40.json`), elegidos con semilla fija (20261105) de los
725 EAN argentinos válidos de la primera página de `get-articulos`: 20 de "marca chica" y 20 de "marca grande", una
marca distinta por EAN. **"Chica" = 5 artículos o menos en el catálogo de dyssa: es una aproximación, no la fama de la
marca** (Gancia, Martini o Hesperidina cuentan como chicas). Tiendas: Jumbo, Carrefour, Disco, Vea, Masonline, Josimar
y Cordiez (Día se descartó: 0 de 10 en la fase A). Hiperlibertad, Makro y Supermami no responden como VTEX; Farmacity sí,
pero no se incluyó. 280 consultas, p50 516 ms, p95 730 ms, sin errores ni bloqueos.

### Cobertura (exacta, sin juicio de imagen)

| | EAN | Encontrados por EAN exacto |
|---|---|---|
| Todos | 40 | **37 (93%)** |
| Marca chica | 20 | 20 (100%) |
| Marca grande | 20 | 17 (85%) |

- No están en ninguna tienda: Sabor de Vida barra de cereal (otra vez la familia Gaona), Maní tubular y Don Nicanor Sangiovese.
- Promedio de 5,1 tiendas por EAN encontrado, pero **3 dependen de una sola**: Gaona Sinfonía (solo Masonline), Valenciano
  Masita (solo Josimar) y Pineral (solo Carrefour). Las tiendas regionales suman cobertura de cola larga.
- 274 imágenes bajadas, 165 distintas por EAN una vez agrupadas las idénticas. 240 son de 1000×1000 y 26 de 2000×2000.

### Calidad: ¿hay una imagen sin banner?

Con `detect-banner.mjs` (detector automático, ver límites abajo). **Es una estimación, no una medición hecha a mano de las 274.**

| | EAN | Al menos una imagen limpia | Solo con banner | No encontrados |
|---|---|---|---|---|
| Todos | 40 | **23 (58%)** | 14 | 3 |
| Marca chica | 20 | 15 (75%) | 5 | 0 |
| Marca grande | 20 | 8 (40%) | 9 | 3 |

- **Elegir entre tiendas e imágenes pesa mucho:** tomando solo la primera imagen de la primera tienda, serían 10 de 37 (27%)
  de los encontrados; eligiendo, 23 de 37.
- **Solo la posición 0 es de fiar.** De las 23 con imagen limpia, 21 la tienen en la posición 0 (la foto principal). Las otras
  2 dependen de una imagen posterior: la de Lira es un frente limpio, pero **la de Hesperidina es la parte de atrás de la
  botella** (contraetiqueta). Una imagen sin panel no es necesariamente un frente: hay que verificarlo.
- Los grandes proveedores (snacks, alimentos) usan la plantilla con banner mucho más que las bebidas alcohólicas.
- Limpia no es lo mismo que buena: Jamaica Gold Rum solo tiene una limpia de 170×170, y la de SAMT es de baja calidad agrandada.

### El detector, y cuánto se le puede creer

Busca una franja casi sólida a la derecha o abajo (el panel). Se ajustó una vez: la primera versión daba 30 de 40 (75%) y
**dos de las cinco "limpias" que se miraron tenían banner** (el panel de Pep tiene texto blanco grande y el de Chamigo es más
angosto y corto). La segunda rellena el blanco encerrado entre píxeles de color y baja el umbral.

| Comprobación | Resultado |
|---|---|
| Primera muestra (38 etiquetas a mano, control de regresión) | 33 de 38 coinciden: 22 banners bien, 11 limpias bien, **1 falso positivo** (Exquisita/Masonline, cartel rojo "NUEVA"), 4 sin clasificar (los banners inferiores de Matarazzo) |
| Segunda muestra (13 etiquetas) | 13 de 13, **pero se usaron para ajustar: no son independientes** |
| 7 "limpias" de la posición 0 que no se habían visto | **7 de 7 limpias de verdad** |

- Los errores que quedan van hacia el lado seguro: el falso positivo cuesta una imagen limpia, no entrega un banner.
- 69 de las 274 imágenes (25%) tienen el fondo no blanco y el detector no las puede clasificar.
- Las etiquetas las puso Claude mirando cada imagen: sin independencia. La muestra de comprobación es chica (7).
- **La cifra de 58% es una estimación con margen:** los 14 "solo banner" no se revisaron todos y pueden incluir falsos
  positivos, y de las 23 "limpias" solo se miraron 10 de la posición 0. Un rango razonable es 55 a 65%.

### Lo que sigue sin probarse

- **El recorte del panel** (hay `cropBox` calculado para unas 100 imágenes, no se aplicó ni se miró ninguno).
- Un modelo de visión como verificador: ahora tiene un trabajo mucho más acotado (¿es un frente limpio?).
- Más tiendas VTEX y otras fuentes para los 3 EAN que no están (Gaona y familia, Maní tubular, Don Nicanor).
- Los términos de uso de las APIs de VTEX y los derechos de las imágenes.
- Que la muestra sale de una sola distribuidora (dyssa) y de la primera página de 1000 artículos de su catálogo.

### Cómo repetirlo

```
node select-sample.mjs 20                                  # necesita out/dyssa-valid779.json (ver abajo)
VTEX_HOSTS="www.jumbo.com.ar,www.carrefour.com.ar,www.disco.com.ar,www.vea.com.ar,www.masonline.com.ar,www.josimar.com.ar,www.cordiez.com.ar" \
  OUT_NAME=vtex-40 node measure-vtex.mjs sample-dyssa-40.json
OUT_NAME=vtex-40 ALL_IMAGES=1 node download-store-images.mjs
node detect-banner.mjs out/vtex-40/images-by-store [labels-second.json]
OUT_NAME=vtex-40 node summarize-sample.mjs
```

`out/dyssa-valid779.json` sale de `origen/prueba-2026-11-05/dyssa-articulos.js` con una cantidad grande (por ejemplo
5000), que vuelca todos los EAN válidos; requiere la colección de Postman de dyssa y permiso de lectura. **No se versiona:**
es el catálogo de un cliente.
