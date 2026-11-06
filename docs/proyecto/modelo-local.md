# Modelo local — BORRADOR

> **Estado: borrador del 2026-11-06.** El usuario propuso este cambio de rumbo y todavía no está decidido en
> detalle. Hasta que lo esté, **no se construye nada que dependa de hospedar un servicio** (API con clave por
> cliente, SQL Server, login del panel, deploy). Lo medido (`mediciones.md`) y las reglas de la elección de la
> imagen siguen valiendo.

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
