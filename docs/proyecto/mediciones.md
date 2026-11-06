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

- ~~El recorte del panel~~: probado en la fase A3, más abajo.
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

## Fase A3 — recorte del banner (2026-11-05)

`spikes/measure-sources/crop-banner.mjs`. Para las imágenes que el detector marcó con panel (107):

1. Segmenta la imagen en objetos conectados (blanco = fondo, sin rellenar huecos).
2. El producto es el objeto más grande. El panel es **otro objeto separado**: alto y a la derecha, o ancho y abajo, más
   la insignia ("400 Gr") que lo acompaña.
3. Copia **solo el rectángulo que contiene al producto**, desde los píxeles originales, a un cuadrado blanco con 8% de
   margen. No agranda: la resolución es la original.

**Regla de seguridad:** si el panel no es un objeto separado (toca al producto), no recorta y deja la imagen como estaba
(`no-separate-panel`). Como solo se copia la caja del producto, el producto nunca se corta.

### Resultado

| | |
|---|---|
| Imágenes con panel | 107 |
| Recortadas | 79 |
| Sin tocar (panel pegado al producto) | 28 |
| EAN "solo banner" con al menos un recorte posible | **12 de 14** |
| Esos 12, revisados a ojo (original y recorte lado a lado) | **12 de 12**: el panel desaparece, el producto queda entero y centrado |
| Tamaño de salida de los 79 recortes | mínimo 667 px, mediana 1073, máximo 2334 |

- Los 2 EAN que no se pueden recortar son Valenciano Masita (solo está en Josimar, con el panel pegado) y Canale Tirabuzón.
- **Hesperidina tampoco se puede recortar:** su panel inferior está pegado a la botella. Su frente sigue con banner y la
  única imagen sin panel es la contraetiqueta.
- **El detector automático no sirve para verificar los recortes:** asume que el producto no llega a la franja derecha, y en
  un recorte el producto ocupa el 84% del cuadrado, así que marca el envase como panel (58 de 79). Se verificó solo a ojo.
- Quien revisó a ojo fue Claude: sin independencia. 12 casos es una muestra chica.

### Estimación final de la muestra de 40

| | EAN |
|---|---|
| Imagen limpia en la posición 0 (por el detector) | 21 |
| Con banner, recortada y revisada a ojo | 12 |
| **Frente sin banner utilizable** | **33 (83%)**, 34 si se admite la segunda imagen de Lira (es un frente limpio) |
| Con banner que no se puede recortar (Hesperidina, Valenciano, Canale) | 3 |
| No encontrados en ninguna tienda | 3 |
| Imagen "limpia" que en realidad es el reverso (Hesperidina) | cuenta como no utilizable |

**Rango razonable: 80 a 88%.** Los 21 de la posición 0 dependen del detector (10 miradas a ojo hasta ahora), y la muestra
sale de una sola distribuidora. Con la regla de usar **solo la posición 0** se entregaría un frente sin banner en el 83%
de los EAN, y en el 92% de los que alguna tienda tiene.

### Cómo repetirlo

```
node detect-banner.mjs out/vtex-40/images-by-store
node crop-banner.mjs out/vtex-40/images-by-store out/vtex-40/banner-detect.json out/vtex-40/cropped
node contact-sheet.mjs <pares.json> out/vtex-40/sheet 6       # original y recorte lado a lado, para revisar a ojo
```

Ojo: `detect-banner.mjs` pisa el `banner-detect.json` de la carpeta anterior si se lo corre sobre otra carpeta; usar
`DETECT_OUT=<archivo>` para no perder el resultado.

## Fase A4 — el catálogo real de la tienda: 130 productos (2026-11-06)

La planilla de la tienda (`eans danone.xlsx`: descripción, SKU y EAN; **130 EAN, todos de 13 dígitos, todos con
dígito verificador válido, sin repetidos**; familias de lácteos: yogures, postres, quesos untables y leches). La
planilla es un dato del cliente y no se versiona (`.gitignore` ignora las planillas).

**Método:** las 7 tiendas a la vez por cada EAN y una pausa de 1 s: cada tienda recibe unas 0,7 consultas por
segundo. La medición se detiene sola si una tienda contesta 401, 403 o 429, y no insiste (no pasó).

| | Resultado |
|---|---|
| Consultas | 910, **todas con HTTP 200** (ningún 429 ni 403) |
| Latencia | mediana 274 ms, p95 549 ms, máxima 1261 ms |
| EAN encontrados en al menos una tienda | **125 de 130 (96%)** |
| Por tienda | Carrefour 125, Jumbo 122, Disco 122, Vea 122, Masonline 116, Josimar 108, Cordiez 76 |
| Tiendas por EAN encontrado | 6,3 en promedio |

**Los 5 que no están:** 4 son **marca propia de La Anónima** (batido FFS 120 g frutilla y vainilla, sachet 900 g
vainilla y frutilla) y el quinto es un Casancrem Sachetón de 4 kg (formato institucional). Tiene sentido: la marca
propia de una cadena solo la vende esa cadena.

> **Corrección (2026-11-06): las cifras de calidad de esta fase son inválidas.** Salieron de la primera versión del
> recortador, que dejó el panel puesto en 8 de las fotos entregadas y recortó de más el producto en unas 40. La
> frase "12 de 12 recortes bien, revisado a ojo" venía de una muestra chica propia que no vio el fallo. Lo que vale
> es la fase A5, más abajo. La cobertura (los 125 de 130 encontrados) y las consultas **sí** son válidas.

**Calidad de la imagen (posición 0 de cada tienda), con la primera versión del recortador (superada por la A5):**

| | |
|---|---|
| Imágenes bajadas | 791, de las cuales **749 traen el panel de banner (95%)** |
| EAN con alguna imagen limpia | 29 de 130 (22%) |
| Imágenes con panel que se pudieron recortar | 700 de 750 |
| EAN con banner y recortable | 93 de 96 |
| **EAN con un frente utilizable (limpio o recortado)** | **122 de 130 (94%)**, 98% de los encontrados |
| Sin recorte posible | Casancrem Sachetón 2 kg, y LS Sachet Entero 1300 g vainilla y frutilla (el panel toca al envase) |

- **En esta marca la plantilla con banner es casi universal:** el recorte no es un extra, es el camino principal.
- ~~Revisado a ojo por Claude (sin independencia): 12 de 12 recortes bien~~ **Incorrecto**: la muestra no incluía
  ninguno de los 8 casos con el panel puesto. Ver la fase A5.
- **WebP:** solo **Josimar** ignora el `Accept` y sirve WebP (79 de 791 imágenes); las otras seis sirven JPG.
  Ningún EAN depende únicamente de una imagen WebP.
- Limitaciones: una sola marca, productos refrigerados, y el recorte quedó validado a ojo en una muestra.

**Prototipo de la entrega** (`spikes/measure-sources/make-gallery.mjs`, salida en `out/danone/`, ignorada por git):
una carpeta `entrega/` con fotos JPEG nombradas con el SKU, un `reporte.csv` con el estado de cada producto y una
`galeria.html` para mirarlas de corrido. Es el antecedente de la herramienta real (fase A5); su recortador tenía el
defecto de arriba.

### Cómo repetirlo

```
VTEX_HOSTS="www.jumbo.com.ar,www.carrefour.com.ar,www.masonline.com.ar,www.josimar.com.ar,www.cordiez.com.ar,www.disco.com.ar,www.vea.com.ar" \
  OUT_NAME=danone PARALLEL_HOSTS=1 SKIP_FIRST_IMAGE=1 node measure-vtex.mjs out/danone-130.json
OUT_NAME=danone CONCURRENCY=6 PAUSE_MS=200 node download-store-images.mjs
node detect-banner.mjs out/danone/images-by-store
node crop-banner.mjs out/danone/images-by-store out/danone/banner-detect.json out/danone/cropped
OUT_NAME=danone node make-gallery.mjs
```

## Fase A5 — del prototipo a la herramienta: lo que falló y lo que se midió después (2026-11-06)

Esta fase **reemplaza las cifras de calidad de la A4**. Se hizo en tres vueltas: una revisión independiente del
prototipo encontró un defecto serio, se corrigió y se portó a Java comparando contra lo medido, y una segunda revisión
independiente de la entrega de la herramienta real cerró el ciclo.

### 1. El defecto del recortador (revisión independiente de la entrega del prototipo)

Un agente que no vio mis conclusiones revisó las 122 fotos del prototipo con criterios explícitos. Encontró **8 con
el panel de nombre y peso todavía puesto** y el producto recortado de más en unas 40. **Causa:** el recortador tomaba
"el bloque más grande" como el producto, pero un envase blanco sobre fondo blanco se parte en varios pedazos y el
panel, sólido, quedaba como el bloque más grande: lo conservaba a él y descartaba el producto. La versión 2 busca el
panel por **lo que es** (una franja sólida, gruesa, con todo el resto del contenido de un solo lado) y **verifica la
salida**: si queda un panel, la imagen se informa y no se entrega. Los 8 casos quedaron bien y los 5 que pasaron de
"recortada" a "sin panel separado" tienen la misma foto en otras tiendas.

### 2. El port a Java contra lo medido

Se corrió el detector y el recortador de Java sobre las **791 imágenes** de la A4 y se compararon con los resultados del
prototipo: **711 de 712 clasificaciones iguales** y **670 de 671 resultados de recorte iguales** (las 79 que no se
comparan son WebP, que el JDK no lee y solo sirve Josimar). Las cajas del producto difieren unos píxeles de borde en 23
casos (hasta 30 px sobre 1000); en tres revisadas a ojo, la caja de Java abraza al envase y no corta nada, y se le
agregó una celda de holgura para no afeitar el borde pálido de un envase blanco.

### 3. Lo que la revisión pidió y cómo se resolvió

La revisión de la entrega del prototipo (v2 del recortador, 124 fotos) dejó **5 fotos que no se debían entregar y 15
leves**: tres con el envase cortado por la fuente (610021, 610055, 939841), una miniatura agrandada a 2000 px (640017) y
un envase de otro diseño (964107). Todas eran el **original de una tienda** que la regla "el original le gana al
recortado" había preferido. Medido sobre el catálogo:

- **Borde:** el contacto del producto con el borde de la foto va de 0 a 0,42. Los 3 cortes reales dan 0,33 a 0,42, pero
  tres cartones de base plana que el revisor vio completos dan 0,40 a 0,42: **una foto rebanada y un envase completo
  pero justo de marco se miden igual**. Por eso el borde (umbral 0,25) solo manda la foto al fondo, no la descarta.
- **Nitidez:** 640017 da 6 y la siguiente más baja 30 (umbral 15). Pero **no es comparable entre tiendas**: una imagen
  de 2000 px, achicada para medir, da menos que la de 1000 px de la misma foto. Comparar "la más nítida" cambiaba 27
  fotos más (aparte de 640017) por versiones más chicas sin ganancia real, así que no se hace.
- **Mayoría entre tiendas:** la huella visual del producto separa bien "misma foto" (0 a 6 bits de 64, aun con otro
  tamaño o recorte) de "otra foto" (17 a 31). Cuando las tiendas muestran fotos distintas, gana la que más repiten
  (Jumbo, Disco y Vea sirven el mismo archivo y cuentan como un voto; todas votan, pero solo puede ser elegida la que no
  toca el borde ni está borrosa). **Cambia 22 de las 124 elecciones**, entre ellas los casos marcados (los dos Silk, el
  Casancrem que mostraba otra variante y los siete originales de Cordiez con mucho aire). Lo que reemplaza es casi
  siempre el diseño actual del envase frente a uno viejo de Cordiez (el Danonino de Peppa, el Yogurísimo de 14 g).

### 4. La corrida real de la herramienta (planilla de 130 productos)

| | |
|---|---|
| Tiempo (primera vez, sin caché, 7 tiendas) | 238 s |
| Filas con foto | **124 de 130 (95%)**: 111 recortadas y 13 originales |
| Sin foto | 6: 5 que ninguna tienda vende (4 de marca propia de La Anónima y el Casancrem de 4 kg) y el Casancrem Sachetón de 2 kg, cuya foto trae el panel y no se pudo recortar |
| Tamaño de las fotos | mínimo 784 px, mediana 1643 px, ninguna bajo 500 px |
| Tienda de origen | Carrefour 88, Jumbo 15, Masonline 12, Cordiez 6, Josimar 3 |
| Con aviso en el reporte | 3 (640010, 958510, 966512: fotos blandas) |
| Elecciones distintas de la entrega revisada antes | 39 de 124 |
| Con 3 productos a la vez (`--paralelo 3`) | 127 s en vez de 238 s, y **las mismas 130 filas** (estado, tienda, tipo, tamaño y avisos); ninguna advertencia de tienda |

### 5. Revisión independiente de la entrega final (124 fotos)

Otro agente, sin saber qué había cambiado, revisó las 124: hojas de contacto, 14 hojas propias con cada producto
ampliado, franjas de los bordes y unas 25 fotos a tamaño completo, más medidas por script de márgenes y fondo.

- **Panel: 0. Marca ajena: 0. Producto cortado: 0. Packs que no corresponden: 0.** Fondos blancos puros.
- **1 "bloquea" y 12 leves; 111 limpias.** Los "bloquea" y los dudosos por producto (610036, 610043 a 610045) se
  resolvieron mirando lo que cada tienda dice del EAN: **las tiendas coinciden entre sí y con la foto**; la diferencia
  está en la descripción de la planilla (peso 162 g contra 166 g en el mismo producto; "+FORTI ENT" es un nombre interno).
  No son defectos de la foto. Queda por confirmar con el cliente si esa descripción es la que quiere.
- Los leves restantes son fotos chicas dentro del lienzo y de baja resolución (138701, 610027, 656001, 656002), un vaso
  con poco margen (610022) y las blandas avisadas (640010, 966512, 958510, 610055).
- Las promociones impresas en el envase ("pack ahorro", "4x3", "edición limitada") son parte del envase y no se
  recortan; si la tienda no tiene esa promoción vigente, pueden confundir.

### Límites de esta medición

- Los revisores son **agentes, no una persona**: no abrieron cada foto a tamaño completo. Para marcas ajenas, la galería
  sigue siendo la revisión que importa antes de entregar.
- Una sola marca, productos refrigerados, un solo cliente: los umbrales de borde y borrosa salen de acá.
- Que las tiendas coincidan entre sí **no verifica** que la foto sea el producto real del EAN; es lo más probable.
- No se midió la caché con un segundo cliente ni la corrida con una tienda bloqueando.

### Cómo repetirlo

```
./gradlew :fotos-tool:installDist
fotos-tool/build/install/fotos-tool/bin/fotos-tool.bat "eans danone.xlsx" --refresh --no-abrir
```
