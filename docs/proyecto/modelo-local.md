# Modelo local

> **Estado (2026-11-06): decidido y construido.** El usuario propuso este cambio de rumbo y confirmó la forma de
> operarlo (arrastrar y soltar, sección 6). **La sección 7 describe lo que ya anda y sus límites**; las secciones 1 a
> 6 son el análisis que llevó a la decisión y se conservan como registro. **No se construye nada que dependa de
> hospedar un servicio** (API con clave por cliente, SQL Server, login del panel, deploy) salvo pedido nuevo. Lo medido
> (`mediciones.md`) y las reglas de la elección de la imagen siguen valiendo.

## 1. Qué se propuso

> "No queremos agregar un servicio más al servidor para cargarlo: lo haríamos local en mi máquina cuando el
> cliente solicita las fotos. Nos pasan los EAN, le ponemos de nombre a la foto el código de SKU y después le
> paso a la tienda la descarga para que la suba."

Es decir: **una herramienta que corre en la máquina del usuario, por lotes y a pedido**, no un servicio.

## 2. El proceso

1. El cliente pasa una **planilla** con descripción, SKU y EAN (como `eans danone.xlsx`).
2. La herramienta busca cada EAN **primero en la caché local** y solo consulta a las tiendas VTEX si no está.
3. Baja las imágenes con las reglas de seguridad, quita el banner cuando se puede y elige una por producto.
4. Arma una **carpeta (o un ZIP) con una foto por producto nombrada con el SKU** (`<SKU>.jpg`) y un **reporte** con lo
   que no se encontró o hay que revisar.
5. El usuario le entrega eso a la tienda, que **sube** las fotos. Si a alguien no le gusta una foto, la cambia
   por otra, propia o de las alternativas.

## 3. Qué cambia, qué se mantiene y qué es nuevo

| | |
|---|---|
| **Se mantiene** | Todo lo medido: VTEX por EAN con coincidencia exacta, solo la posición 0, el recorte del banner, las 7 tiendas y su orden. El conector con ritmo, reintento, cortacircuito y manejo del 429 (`fotos-app`, ya probado). La validación del EAN. La descarga segura (las URLs vienen de terceros aunque corra local). El principio **caché primero**: ahora una carpeta local. Las reglas de la recomendada. |
| **Se va (en suspenso)** | Desplegar en el servidor de la tienda (WinSW, IIS, simulador de proxy, scripts de entrega, monitoreo externo, alertas de Slack). La API con `x-api-key` por cliente y el contrato `GET /v1/{tenant}/images/{ean}`, los lotes, `LOADING` y `refresh`. SQL Server + Flyway. El login del panel. |
| **Es nuevo** | Leer la planilla (EAN y SKU). Escribir la entrega: una foto por SKU y el reporte. Decidir el formato y el tamaño que pide la tienda para subir. |

## 4. Opciones

- **A. Línea de comandos + galería.** Un comando que recibe la planilla y deja la carpeta `entrega/` con las fotos por
  SKU, el `reporte.csv` y una `galeria.html` para mirarlas. Quien quiera cambiar una foto reemplaza el archivo. Es lo
  más chico que ya resuelve el pedido, y **ya existe como prototipo** (`make-gallery.mjs`).
- **B. Aplicación local con el panel.** Lo mismo, pero con una pantalla en `localhost` para cargar la planilla, ver
  las fotos, cambiar una por otra alternativa y descargar el ZIP. Respeta la regla de que todo lleva un panel Next
  embebido, y no necesita login porque solo escucha en tu máquina. Es bastante más trabajo.
- **C. Mantener el servicio alojado.** Descartada por el usuario: es lo que se quiere evitar.

**Recomendación: A primero, B si se usa seguido.** El núcleo (buscar, recortar, elegir, escribir la entrega) es el
mismo para las dos, así que A no se tira cuando llegue B.

## 5. Respuestas del usuario (2026-11-06)

1. **Formato:** JPG o PNG, el que tenga la foto. El archivo se llama `<SKU>.jpg` o `<SKU>.png` según lo que sea de
   verdad (no se convierte). Una imagen WebP (solo la sirve Josimar) no se entrega tal cual: se elige otra, y si no
   hay otra se convierte o se reporta. De tamaño no hay un pedido: hoy se entrega la original o la recortada, sin
   normalizar (entre 667 y 2300 px, fondo blanco).
2. **Cómo se revisa y se cambia una foto:** depende de cómo se opere (sección 6), que sigue abierta.
3. **Quién lo corre:** solo el usuario, en esta PC, por ahora. Se diseña para poder crecer: el núcleo (leer la
   planilla, buscar, recortar, elegir, escribir la entrega) separado de la forma de usarlo (un acceso directo, una
   pantalla o un servicio), con la caché, las tiendas y las carpetas configurables.
4. **La planilla:** no se sabe qué traen los clientes, así que **se les va a pedir un formato fijo** (descripción,
   SKU y EAN). La herramienta busca las columnas por el nombre del encabezado (sin distinguir mayúsculas ni tildes)
   y no por la posición, y si falta una avisa cuál.
5. **Derechos:** los asume el usuario. A cambio, **las fotos no pueden tener marcas ajenas al producto** (logos o marcas
   de agua de comercios, sellos de oferta, paneles, textos agregados). Es un criterio de calidad de la herramienta y
   se revisa a ojo antes de entregar (revisión de las 122 fotos del catálogo de la tienda: `mediciones.md`, fase A4).
6. **Persistencia local:** una carpeta de caché con archivos y un índice, no SQL Server. Con 130 productos, o unos
   miles, no hace falta una base.

## 6. Cómo se opera (decidido el 2026-11-06: A1, arrastrar y soltar)

El usuario pidió algo **fácil** y confirmó la opción A1 ("Sí, vamos con arrastrar y soltar"). Las formas posibles
que se evaluaron, de menos a más trabajo:

- **A1. Arrastrar y soltar.** Un acceso directo en el escritorio: se arrastra la planilla encima, la herramienta
  trabaja y al terminar abre la carpeta con las fotos y la galería. Sin escribir comandos. Necesita Java, que ya está.
- **A2. Un comando en la terminal.** Lo mismo, tipeando. Más simple de construir, menos cómodo.
- **B. Pantalla en el navegador, en tu máquina.** Se abre con un doble clic; se carga la planilla, se ven las fotos,
  se cambia una por otra alternativa y se descarga el ZIP. Es la más cómoda de usar y la que más trabajo lleva.
- **C. Programa de escritorio con ventana propia e instalador.** Más trabajo que B sin ganar nada sobre ella.

**Decidido: A1 primero.** Además de las fotos, deja un **ZIP listo para pasarle a la tienda** (el pedido es "le paso
la descarga"), el `reporte.csv` y la galería. Cambiar una foto es reemplazar el archivo en la carpeta. Si se usa
seguido y eso molesta, se construye B encima del mismo núcleo.

Lo que **todavía no se decidió** y se resuelve al construir: qué pasa con el código del modo servicio que ya existe
(la consulta de demostración y el panel de `fotos-app`): se conserva intacto mientras no estorbe, y se decide su
destino cuando la herramienta ande.

## 7. Qué se construyó (2026-11-06)

### Cómo se usa

1. Una vez: `scripts/herramienta/instalar.ps1` (compila, copia el programa a `%LOCALAPPDATA%\api-fotos\herramienta` y
   crea el acceso directo **Fotos por SKU** en el escritorio). Se puede correr de nuevo para actualizar.
2. Cada vez que un cliente manda su lista: **arrastrar la planilla (.xlsx o .csv, con columnas SKU y EAN) sobre el
   acceso directo.** Se abre una ventana con el avance fila por fila.
3. Al terminar se abre la carpeta `<planilla> - fotos`, junto a la planilla:
   - `fotos\` una foto por SKU, `<SKU>.jpg` o `<SKU>.png` según lo que la foto realmente es (no se convierte);
   - `fotos.zip` las mismas fotos, listas para pasarle a la tienda;
   - `reporte.csv` una línea por fila de la planilla: estado, tienda de donde salió, tamaño y **avisos**;
   - `galeria.html` lo mismo para mirar, con los avisos marcados.
4. Cambiar una foto es **reemplazar el archivo** en `fotos\` (y volver a armar el zip). Correr de nuevo la misma
   planilla no pisa nada: arma `... - fotos (2)`.

Estados del reporte: `OK` (original de la tienda, sin tocar), `OK_RECORTADA` (se le quitó el panel de plantilla),
`SIN_IMAGEN` (ninguna tienda lo vende, o ninguna tiene una foto utilizable: el detalle lo dice), `ERROR` (una tienda
no respondió: **volver a correr la misma planilla**, solo se piden esas), `DATOS_INVALIDOS` (EAN mal, SKU faltante,
SKU que no sirve de nombre de archivo, SKU repetido con otro EAN).

Opciones por consola: `--refresh` (vuelve a consultar las tiendas por los EAN de esa planilla), `--cache <carpeta>`,
`--no-abrir`. La caché está en `%LOCALAPPDATA%\api-fotos\cache` (o en `FOTOS_CACHE`) y **sobrevive entre planillas**:
el mismo producto en la lista de otro cliente no se vuelve a pedir.

### Cómo decide

Por cada EAN, primero la caché; si no está, las **siete tiendas en paralelo** (cada una con su propio ritmo: 250 ms
entre consultas, un reintento, cortacircuito, como en el servicio). De cada tienda se mira **solo la primera imagen**
(las siguientes pueden ser el dorso). Cada imagen es candidata solo si sirve: limpia, o recortada sin su panel; si
trae el panel y no se pudo recortar, si el fondo no es blanco o si es WebP (solo Josimar, que Java no lee), no es
candidata y nunca se entrega "la más parecida". Entre los candidatos, **por capas**:

1. no tocar el borde de la foto (un producto rebanado por la fuente es lo único que un recorte no arregla);
2. no estar borrosa (una miniatura agrandada);
3. **la mayoría**: si las tiendas muestran fotos distintas, la que más tiendas repiten (todas votan, solo puede ser
   elegida la que pasó las dos capas anteriores; Jumbo, Disco y Vea sirven el mismo archivo y cuentan como un voto);
4. al menos 500 px, luego el original de la tienda sobre el recortado, luego el más grande, luego el orden de
   `stores.txt`.

Las dos primeras capas **no descartan**: solo pasan al candidato al fondo. Un cartón de base plana pegado al marco y
uno rebanado se miden igual, así que si no hay otra foto, se entrega con un aviso en el reporte y no se esconde.

**Qué recuerda la caché:** una foto encontrada, para siempre (salvo que sea dudosa y alguna tienda no haya
respondido: entonces no se guarda y la próxima corrida lo reintenta); "no lo vende nadie" o "sin foto utilizable",
**solo si todas las tiendas respondieron**. Un error nunca es una respuesta.

### Límites conocidos (decirlos, no taparlos)

- **Marcas ajenas (logos de comercios, marcas de agua, sellos de oferta, textos agregados):** la herramienta **no las
  detecta**. En el catálogo medido (130 productos) una revisión independiente de las 124 fotos no encontró ninguna,
  porque la fuente son fotos de catálogo de cadenas con la plantilla del proveedor, pero con otro catálogo o
  otras tiendas puede pasar. La galería es la herramienta para mirarlo antes de entregar.
- **Qué diseño del envase es "el bueno":** la mayoría de las tiendas, que es lo más probable pero no una
  verificación. En 22 de 124 productos las tiendas discrepan (otra edición, un pack viejo, otro ángulo) y manda la
  mayoría. No hay aviso en el reporte cuando hubo discrepancia: es una mejora posible.
- **Los umbrales de borde (0,25) y de borrosa (15) salen de un solo catálogo** y de 124 fotos. Solo reordenan.
- **La caché guarda la foto elegida, no todos los candidatos.** Si las reglas de elección cambian, las entradas viejas
  siguen (no vencen): hay que correr con `--refresh`. Guardar todos los candidatos permitiría volver a elegir sin
  volver a consultar; no se hizo todavía.
- La descripción de la planilla no se usa para decidir (la coincidencia es por EAN exacto): una descripción que no
  coincide con el envase (otro peso, otra variante) no se detecta.
- **Términos de uso de las APIs de VTEX y derechos de las imágenes:** sin consultar. Los derechos los asume el
  usuario (decidido el 2026-11-06).
- `fotos-app` (servicio, panel, consulta de demostración) sigue en el repositorio, intacto y en suspenso; su destino
  se decide cuando la herramienta se use un tiempo.
