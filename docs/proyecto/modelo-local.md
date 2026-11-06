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

## 5. Preguntas abiertas

1. **¿Qué formato y tamaño necesita la tienda para subir las fotos?** (JPG o PNG, lado en píxeles, peso máximo,
   fondo blanco). Hoy se entregan JPEG cuadrados de entre 667 y 2300 px con fondo blanco, sin normalizar.
2. **¿Cómo se revisa y se cambia una foto?** Opción A: reemplazando el archivo de la carpeta. Opción B: en la pantalla.
3. **¿Lo corre solo el usuario o también otras personas?** Cambia lo que hay que instalar (Java) y cómo se entrega.
4. **La persistencia local:** una carpeta de caché con archivos y un índice (la propuesta) en lugar de SQL Server. Con
   130 productos, o unos miles, no hace falta una base.
5. **¿Una planilla trae siempre las mismas columnas** (descripción, SKU, EAN)? Si cambia el orden o el nombre, hay que
   saber cómo reconocer cuál es cuál.
6. **Derechos de las imágenes:** ahora el usuario las descarga y las entrega, así que la pregunta de quién asume el
   riesgo de publicarlas sigue en pie, y es de él.
