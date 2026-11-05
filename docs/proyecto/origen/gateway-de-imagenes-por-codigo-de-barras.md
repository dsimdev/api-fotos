# Gateway de imágenes por código de barras

> Escrito el 2026-11-05 desde `api-impuestos`, para arrancar el repo nuevo con lo ya investigado.
> Todo lo marcado **(verificado)** salió de una prueba o lectura hecha ese día; lo marcado
> **(no verificado)** es una hipótesis o algo que no se pudo leer. La decisión de tecnología y la
> de la verificación con modelo de visión **quedaron abiertas a propósito**: se toman en el repo nuevo.
>
> **Actualización del mismo día:** después de probar SerpApi, el usuario lo descartó ("hace web scraping
> y tarda, para eso lo hacemos nosotros") y la decisión pasó a **scraping propio**. Ver las secciones 2 y 3 bis.
> Lo medido de SerpApi se conserva como referencia y como posible plan B.

> Para arrancar el proyecto: [brief-proyecto-nuevo.md](brief-proyecto-nuevo.md). Scripts y veredictos de
> la prueba: [prueba-2026-11-05/](prueba-2026-11-05/README.md).

## 1. Qué se pidió

Un cliente de la tienda (`senderolaser`) no tiene imágenes de sus productos y no quiere buscarlas
a mano en Google. La idea: un servicio que reciba el **código de barras (EAN)** y devuelva la foto
del producto. Por ahora son pocos productos (la mayoría de las fotos ya las tiene la tienda).
`senderolaser` todavía no tiene los EAN cargados, así que las pruebas se hicieron con **dyssa**.

## 2. Decisiones

**Tomadas el 2026-11-05 (por el usuario):**

- Se obtienen las imágenes **con scraping propio de los resultados de Google**. Open Food Facts queda de respaldo. (Primero se había elegido SerpApi; el usuario lo descartó el mismo día por lento y porque "igual hace scraping". **La lentitud no se midió**, ni la de SerpApi ni la de la alternativa.)
- Es un **servicio nuevo, en un repo propio**. No va en `api-impuestos` (lo comercial está fuera de su alcance).
- **100% automático**: nadie elige entre candidatos.
- Ser **lo más independientes posible** de terceros.

**Abiertas, para el repo nuevo:**

- **Tecnología** del servicio.
- **Cómo se consulta Google sin SerpApi** (sección 3 bis), y desde dónde corre. Medir tiempo por búsqueda y tasa de bloqueos con los mismos 10 EAN de la sección 4 antes de construir nada.
- **Verificación con modelo de visión** (sección 6): se agregó como pendiente, no se decidió.
- **Dónde quedan las imágenes y cómo llegan al SQL de imágenes de senderolaser** (sección 5). Falta saber cómo guarda hoy la tienda las imágenes: binario, ruta a archivo o URL.
- Qué hacer con las imágenes que traen logo o banner de otro comercio (sección 4).
- Leer los términos de SerpApi y decidir si hace falta un plan con "Legal Shield" (sección 3).

## 3. Lo que se verificó de las fuentes

**SerpApi** (verificado en [serpapi.com/pricing](https://serpapi.com/pricing) y [la página del motor de imágenes](https://serpapi.com/google-images-api)):

- Plan gratis: 250 búsquedas por mes, 50 por hora. Starter: USD 25 por 1.000 búsquedas. Las búsquedas cacheadas por SerpApi no cuentan en la cuota.
- Cada resultado trae `original` (URL de la imagen grande), `thumbnail`, `title`, `source`, `link` (página del comercio), `original_width` y `original_height`.
- Se puede filtrar por país (`gl=ar`), idioma, tamaño, forma cuadrada y tipo de imagen.
- El campo `is_product` vino en `no` en **todos** los resultados probados: no sirve como filtro.
- "U.S. Legal Shield" (hasta USD 2M de cobertura): solo desde el plan Production (USD 150 al mes). No está en el gratis.
- **Los términos de uso no se pudieron leer** (las URLs `/terms`, `/terms-of-service` y `/legal-us` dieron 404). Leerlos antes de producción.

**Estado legal de SerpApi** (verificado, prensa especializada; sin consultar el expediente):

- Google lo demandó el 2025-12-19 (DMCA, por saltear su sistema SearchGuard). El 2026-07-20 el juez [desestimó los reclamos](https://www.searchenginejournal.com/court-dismisses-googles-dmca-claims-against-serpapi/583033/): los de contenido sin copyright, para siempre; los de contenido con copyright, con permiso para rehacerlos.
- Google [volvió a presentar la demanda el 2026-08-10](https://ppc.land/serpapi-faces-revived-google-scraping-claims-built-on-reddit-licensing-terms/) y SerpApi pidió desestimarla otra vez. **No se encontró resolución**: el caso sigue abierto al 2026-11-05.
- Implicancia: el servicio puede cambiar o cortarse. Por eso las fuentes tienen que ser intercambiables.

**Google directo:** su `robots.txt` tiene `Disallow: /search` y `/imgres` para todos (verificado). La Custom Search JSON API está [cerrada a clientes nuevos y se apaga el 2027-01-01](https://brave.com/learn/google-api-shutdown/) (no se confirmó si ese cierre incluye imágenes).

**Derechos:** las fotos son de los comercios y de las marcas. Que SerpApi las devuelva no cambia eso. El riesgo de usarlas en una tienda lo asume quien las publica; hay que acordar quién.

## 3 bis. Scraping propio de Google: lo que hay que saber antes

- **Google lo desalienta de entrada:** el `robots.txt` prohíbe `/search` y `/imgres` a todos (verificado, sección 3). Que lo hagamos nosotros no cambia eso.
- **Hay una barrera técnica activa.** Según la prensa sobre el juicio, Google puso en enero de 2025 un sistema llamado SearchGuard para impedir el acceso automático a sus resultados ([IPWatchdog](https://ipwatchdog.com/2025/12/26/google-sues-serpapi-parasitic-scraping-circumvention-protection-measures/)). Un pedido HTTP simple probablemente no alcance y haga falta un navegador real sin pantalla, que tampoco es rápido. **No se probó.**
- **El riesgo legal pasa de SerpApi a nosotros.** Lo que Google le reclama a SerpApi es justamente haber saltado SearchGuard (DMCA, secciones 3 y 4). Si lo hacemos nosotros, quien salta esa barrera somos nosotros. Esto es una lectura de la prensa, **no una opinión legal: consultarlo con un abogado antes de producción**.
- **Bloqueos:** una IP que hace búsquedas automáticas puede recibir CAPTCHA o quedar bloqueada para Google. No correrlo desde el servidor compartido de la tienda (sugerencia, no decidido).
- **La foto igual viene del comercio**: después de la búsqueda se descarga desde la página de cada tienda, así que valen los hallazgos de la sección 4 (variantes equivocadas, logos y banners).
- **Idea para evaluar, no verificada:** los resultados de Jumbo y Carrefour terminan en `/p`, un patrón típico de tiendas VTEX, algunas de las cuales permiten consultar el catálogo por EAN. Si funcionara, se evitaría el error de variante equivocada y se podría saltear a Google. No se probó.

## 4. La prueba con dyssa (2026-11-05)

**Método (verificado):**

1. Se leyeron 1.000 artículos de dyssa (`get-articulos` v4, una página, solo lectura). De ellos, 725 tenían un EAN-13 válido que empieza con 779.
2. Se eligieron 10 repartidos. El primero (`7790070035707`) es el mismo código que se había probado en Google.
3. Consulta a SerpApi: **EAN + nombre del artículo normalizado**, con `engine=google_images`, `gl=ar`, `hl=es`, `google_domain=google.com.ar`. El nombre de GESCOM trae el múltiplo de bulto pegado al peso (`400GRX30`, `77GX25X1`); se saca con `/(\d+(?:GR|G|ML|KG|L|CC))(?:X\d+)+$/i` → `$1` y se pasa a minúsculas.
4. Se tomaron los **3 primeros candidatos de páginas distintas**, descartando Instagram, Facebook, Pinterest, TikTok, YouTube, Twitter/X y Reddit.
5. Se bajaron las imágenes y **las miró una persona (Claude), una por una**. Es una revisión a ojo, no una medición automática.

Costo: **12 de las 250 búsquedas gratis**.

**Resultado por producto** (C = correcta y sin logo ni banner, B = correcta pero con logo o banner de otro comercio, X = producto o variante equivocada):

| EAN | Artículo (dyssa) | Candidato 1 | Candidato 2 | Candidato 3 |
| --- | --- | --- | --- | --- |
| 7790070035707 | Patitas de pollo picantes 400g | B (Super Coco, logo encima) | X (Logimol: era "Original 1100g") | X (Coto: era una galletita Risky-Dit) |
| 7790070104311 | Cocinero oliva puro 900ml | B (Jumbo, banner lateral) | X (Cordiez: mezcla girasol y oliva) | X (Carrefour: extra virgen) |
| 7790093527784 | Cafayate Terroir Torrontés 750ml | B (Espacio Vino, marca de agua tenue) | C (La Barra) | B (El Ahorro, banner y círculo amarillo) |
| 7790070337016 | Matarazzo lasagna 250g | C (Atomo) | C (Promoductos) | C (Angelita, con mucho margen blanco) |
| 7790070433022 | Exquisita polvo chocolatado 300g | B (Jumbo, banner) | C (Pricely, solo 256x256) | X (Mercado Libre: era Toddy 360g) |
| 7790070760746 | Emilia Chardonnay 750ml | C (Carrefour) | B (Buscavinos, recorte con banners) | C (Borrachines, pero etiqueta vieja "cosecha 2017") |
| 7798126073437 | Gaona Sabores de Vida chips DDL 100g | C (Mercado Libre, 500x249) | sin ver (no se pudo bajar) | X (Mercado Libre: era "avena y chips" de chocolate) |
| 7793913002447 | Tregar leche 0% 1L | B (Josimar, íconos y texto) | C (California) | C (Tregar oficial, fondo celeste) |
| 7790310984314 | Pep rueditas 74g | B (Josimar, banner lateral) | B (Carrefour, banner lateral) | B (Jumbo, banner lateral) |
| 7790310985502 | Lay's ketchup 77g | B (Punto de Venta, logo y marca de agua) | B (El Ahorro, banner y círculo) | B (Carrefour, banner lateral) |

La primera fila se buscó antes de armar el normalizador, con el texto escrito a mano (`7790070035707 patitas de pollo picantes 400g`).

**Números (sobre 30 candidatos; 29 vistos):**

- **Primer candidato correcto: 10 de 10.** Hay que tomarlo con cuidado: es una muestra chica de marcas grandes (alimentos y bebidas masivos); los productos de marcas chicas probablemente anden peor (no verificado).
- Correctos: 23. **Equivocados: 6 (20%).** Sin ver: 1.
- Primer candidato correcto **y limpio**: 3 de 10.
- Al menos uno correcto y limpio entre los 3: **6 de 10** (5 si no se cuenta la de 256x256).
- Sin ninguna limpia: 4 de 10. En Pep y Lay's las tres son correctas pero con banner lateral (los de las plantillas de Jumbo, Carrefour y otros); recortarlo podría servir, **no se probó**.

**Hallazgos que condicionan el diseño:**

1. **El título de la página no garantiza la imagen.** En 3 de los 6 errores el título era el correcto y la foto no (Coto, Logimol, Mercado Libre/Toddy). Los otros 3 (Cordiez, Carrefour/extra virgen, Mercado Libre/chips de chocolate) se habrían descartado exigiendo que el título contenga las palabras distintivas del artículo (`puro` en el aceite, `ddl` en las galletitas).
2. **El error típico es la variante equivocada**: mismo producto y marca, pero otro sabor, tamaño o línea.
3. **Agregar el nombre del artículo a la consulta hizo más relevantes las páginas**, pero solo se comparó con el primer EAN (`7790070035707`): buscando solo por código aparecían dentífricos y lavandina entre los primeros resultados, y con el nombre aparecieron páginas de ese producto (aunque dos de las tres tenían la foto equivocada). Los otros 9 se buscaron siempre con nombre, sin comparar.
4. **Formatos y tamaños muy variados**: JPG, PNG y WebP; de 256x256 a 2000x2000; cuadradas y apaisadas. Hace falta normalizar.
5. **Una descarga falló** porque el servidor respondió `application/octet-stream` aunque era una imagen. El descargador tiene que detectar el tipo por los primeros bytes y no confiar en el `Content-Type`.

**Open Food Facts como respaldo (verificado, 10 EAN):** encontró **5 de 10**, todos con imagen frontal. Pero esas imágenes son **fotos de usuarios en góndola, de 200 a 400 px**: la lasagna sirve (400x165); la de Gaona muestra un estante con el cartel de otro producto; Tregar, Pep y Lay's son fotos con una mano sosteniendo el paquete. Como foto de catálogo sirve **1 de 10**. Los nombres también son genéricos ("galletas avena"). Con este dato, Open Food Facts vale más para confirmar que el EAN existe que para dar la imagen.

## 5. Diseño sugerido (no decidido)

- **Fuentes intercambiables**, en orden: caché propia → scraping propio de Google → Open Food Facts (SerpApi como plan B si el scraping propio se bloquea). Cada una detrás de la misma interfaz, para poder cambiar una sin tocar el resto.
- **Caché por EAN**: se descarga la imagen a almacenamiento propio, no se enlaza la URL del comercio (esas URLs mueren). Una consulta por código, para siempre.
- **Respuesta con estado explícito**, nunca una imagen inventada: algo como `OK`, `CON_MARCA` (correcta pero con logo o banner) y `SIN_IMAGEN`, más el origen (página del comercio) y la confianza, para poder auditar. Es la misma idea de "traza" de MotorFiscal.
- **Reglas en datos, no en código**: la lista de dominios preferidos o descartados, el filtro de redes sociales y la normalización del nombre.
- **El pedido lleva el EAN y, opcional, el nombre del artículo.** Sin nombre la búsqueda parece peor (hallazgo 3, comparado en un solo EAN).
- **Entrega a la tienda**: se propone que el servicio guarde y exponga la imagen (`GET` por EAN y uno por lotes) y que el backend de la tienda la traiga y la guarde en su SQL. Así el servicio no queda atado al esquema de cada cliente. Para confirmar cuando se sepa cómo guarda senderolaser las imágenes.
- **Normalización de la imagen** (recorte de banners, tamaño, fondo): sin definir.

## 6. Verificación con modelo de visión — pendiente de decidir

**Qué es:** para cada candidato, preguntarle a un modelo con visión si la foto muestra ese producto (marca, variante, tamaño) y si trae logos, marcas de agua o banners encima. Con eso se elige la mejor y se devuelve `OK`, `CON_MARCA` o `SIN_IMAGEN`.

**Por qué se anota:** con el 100% automático no hay una persona que corrija. En la prueba, 3 de los 6 errores tenían el título correcto, así que ningún filtro de texto los habría atrapado.

**Lo que se sabe:**

- Agrega una dependencia más (la API del modelo) y un costo por imagen. **El costo por imagen no se calculó.**
- Cuántos candidatos revisar por EAN (3, 5, más) afecta el costo y la tasa de acierto. **Sin medir.**
- Alternativa sin modelo: aceptar el error (20% en esta prueba) y marcar siempre el origen para poder corregir después.

**Qué decidir en el repo nuevo**, junto con la tecnología: si se usa, con qué modelo, cuántos candidatos y qué umbral. Se puede medir contra esta misma muestra de 10 productos.

## 7. Lo que no se hizo

- No se leyeron los términos de uso de SerpApi.
- No se probó con senderolaser (todavía sin EAN) ni con productos de marcas chicas.
- La muestra salió de la primera página de dyssa (1.000 artículos ordenados por código), no de una selección al azar de todo el catálogo.
- No se probó recortar banners ni normalizar tamaños.
- No se calculó el costo de la verificación visual.
- No se probó el scraping propio: ni que Google devuelva resultados a un navegador automatizado, ni el tiempo por búsqueda, ni cuántas búsquedas seguidas aguanta antes de bloquear.
- No se midió el tiempo de respuesta de SerpApi.
- La key de SerpApi está en un `.env` de `api-impuestos` (ignorado por git). Si SerpApi queda de plan B, mover al repo nuevo; si no, borrarla. No copiarla en ningún doc.
