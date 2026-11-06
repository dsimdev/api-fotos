# Caché — BORRADOR

> **Estado: borrador del 2026-11-06.** El principio (sección 1) lo decidió el usuario. El diseño de las
> secciones 3 y 4 es una propuesta mía, armada leyendo el código de `api-bonificaciones` (no de memoria), y
> las decisiones que faltan están en la sección 5.

## 1. El principio (decidido el 2026-11-06)

**Siempre se contesta desde nuestra caché, o se intenta. VTEX se consulta solo si el EAN no está** (o su entrada
venció, o alguien pidió refrescar). Consecuencias directas:

- Un EAN ya cargado **no genera ningún pedido a las tiendas**. Es la mejor respuesta a la pregunta del 429: los
  stores no publican un límite para este endpoint y nosotros no queremos averiguarlo a golpes.
- La barrida de la tienda (que recorre todos sus códigos) cuesta pedidos a VTEX **una sola vez por EAN**, no cada
  vez que recorre.
- La pantalla de demostración del panel (`/admin/v1/lookup`) consulta VTEX directo y sin caché. Es una excepción
  de demostración, apagada por defecto, y se reemplaza.

## 2. Cómo lo resuelve `api-bonificaciones` (lo que se reusa)

Leído en `bonif-app/.../gescom/CatalogoGescom`, `RefrescadorDeCaches`, `EstadoDeCaches`, `MedidorDeDesalojos`,
`VigilanteDeCaches` y `chess/CatalogoChess`, `CargaEnSegundoPlano`.

| Patrón | Cómo está hecho allá | Para api-fotos |
|---|---|---|
| **Dos tiempos y un tope** | Caffeine con `maximumSize` (60 distribuidoras), `expireAfterAccess` (30 h: memoria) y un TTL de refresco aparte (24 h: cuánto se sirve sin consultar la fuente). `recordStats()` y un `evictionListener` | Mismo esquema para la capa de memoria de metadatos |
| **Servir lo viejo si la fuente falla** | Si refrescar falla y hay una entrada, se sirve esa y se **avisa por Slack** (enfriamiento de 6 h). Solo se falla si no hay nada cacheado | Igual. Servir lo viejo es lo correcto pero es silencioso: alguien tiene que enterarse |
| **Una sola carga por clave** | `enVuelo` (un `Set`) o un candado por clave: N pedidos en frío disparan UNA descarga | Una sola consulta a las tiendas por EAN, por más pedidos que lleguen |
| **El request no espera una descarga larga** | `criteriosEnMemoria` devuelve lo que hay (o null) y calienta en segundo plano. En Chess, contesta `CATALOGO_CARGANDO` (503 + `Retry-After`) y la carga sigue sola. Salió de un caso real: 23 s contra un timeout de 10 s de la tienda, y 46 s contra uno de 45 s | Mismo patrón para EAN nuevos y para lotes: `LOADING` + `Retry-After` |
| **Enfriamiento tras un fallo** | `CargaEnSegundoPlano`: 30 s sin reintentar una carga que falló, para que una caída no se vuelva un reintento continuo. Un servir-lo-viejo también cuenta como fallo | Igual, por EAN |
| **Recargar en el lugar** | `recargar` no tira lo que hay hasta tener lo nuevo; si falla, lo viejo se queda | Igual: refrescar no deja a nadie sin imagen |
| **Refresco programado con cron y zona** | `0 0 5,10 * * *` en hora de Argentina (el servidor está en UTC−12), solo lo que tuvo tráfico en 72 h, en paralelo con un pool por fuente (4 y 2 hilos) | Solo si hace falta (ver 4.5) |
| **El botón del panel usa el mismo camino** | `refrescarAhora`. "Un refresco programado que hace menos que el botón es una diferencia que nadie recuerda" | Igual |
| **Medir y avisar** | `EstadoDeCaches.Uso` (entradas, aciertos, fallos, % de acierto) y `MedidorDeDesalojos`, que **separa los desalojos por falta de lugar de los que vencieron por TTL**: mezclados, un fin de semana sin tráfico encendía un cartel rojo pidiendo más `-Xmx`. `VigilanteDeCaches` avisa cuando el contador sube | Igual, con el prefijo `*[Fotos]*` en el canal compartido |
| **Un resultado vacío no se cachea a ciegas** | GESCOM: se acepta pero se avisa. Chess: se rechaza y se conserva lo anterior, porque una lista vacía tenía la firma de una falla | "No está en ninguna tienda" **sí** es un resultado válido, pero distinto de "ninguna tienda contestó": ver 4.3 |

## 3. Dónde api-fotos es distinto, y por qué no se copia tal cual

- **Allá la caché vive solo en memoria**, así que un reinicio deja todo frío. De ahí el "refresco al arrancar", que
  lee la tabla de tráfico porque los contadores en memoria arrancan vacíos. Para nosotros eso contradice el
  principio: si la caché fuera solo memoria, **cada reinicio volvería a consultar VTEX por todo**. La nuestra tiene
  que **persistir**.
- **Allá la clave es la distribuidora** (decenas o cientos, entradas de varios MB). **Acá es el EAN** (miles, tal
  vez decenas de miles), con metadatos chicos y un archivo de imagen por candidato.
- **Allá el dato cambia** (promociones: TTL de horas). **Acá una imagen de producto casi no cambia**: el
  vencimiento puede ser larguísimo o manual. Pero cambia a veces (un envase nuevo), y por eso existe el botón.
- **Allá se cachea JSON y se descarta.** Acá hay bytes de terceros que hay que descargar con cuidado (SSRF, tipo
  por bytes, pedir JPEG/PNG) antes de guardarlos.

## 4. Diseño propuesto

### 4.1 Capas

1. **Memoria (Caffeine):** metadatos calientes por EAN, con tope de entradas, `recordStats()` y el medidor de
   desalojos separado por causa, como allá. Se llena sola desde la base.
2. **SQL Server (Flyway):** una fila por EAN con su estado (`READY`, `NOT_FOUND`), cuándo se cargó, cuándo se pidió
   por última vez, la recomendada y los candidatos con su traza.
3. **Disco:** los bytes de cada imagen, nombrados por el hash del contenido, así que Jumbo, Disco y Vea (que sirven
   el mismo archivo) ocupan lugar una sola vez. La ruta la genera el servidor, nunca con texto del llamador.

### 4.2 Lectura

- **Acierto:** se responde sin tocar VTEX.
- **Falta:** una sola carga por EAN (como `enVuelo`): consultar las tiendas, descargar con las reglas de seguridad,
  quitar el banner, elegir la recomendada, persistir y responder.
- **Si la carga tarda más que un plazo (propuesta: 8 s) o es un lote:** responder `LOADING` con `Retry-After` y
  seguir cargando en segundo plano. Es el patrón de `CATALOGO_CARGANDO`: la tienda consumidora tiene su propio
  timeout y no hay que hacerla esperar.

### 4.3 Qué se guarda cuando algo sale mal

- **Hay entrada y la recarga falla:** se sirve la que hay y se avisa (como `avisarDatosViejos`).
- **No hay entrada y ninguna tienda contesta** (todas con error): **no** se guarda como "no está". Se responde
  `SOURCES_UNAVAILABLE`, con 30 s de enfriamiento por EAN. Guardar un "no está" por una caída de red sería mentirle
  a la tienda durante días.
- **Todas las tiendas contestaron y ninguna lo tiene:** sí es un resultado: `NOT_FOUND`. **Nosotros no lo
  reintentamos solos** (decidido el 2026-11-06): lo reintenta la tienda cuando quiere, con una operación explícita
  de refresco (ver `contrato-api.md`). Si alguna tienda falló y las otras dijeron que no, no sabemos si está: no se
  guarda como `NOT_FOUND`, igual que una caída total.

### 4.4 La barrida de la tienda

La tienda recorre todos sus códigos: no se modela como un trabajo con su propio estado. El lote responde, por
cada EAN, lo que ya está (`READY` con su recomendada) o `LOADING`, y **encola el resto**. La tienda vuelve a pedir
los `LOADING` después del `Retry-After`. El ritmo hacia las tiendas lo ponen el limitador por tienda y los pools
por fuente que ya existen (`StoreRateLimiter`, `StoreResilience`) más un tope de concurrencia, como
`bajarEnParalelo`.

### 4.5 Refrescar

- **Botón en el panel**, por EAN (y por lote), que usa el mismo camino que cualquier recarga y recarga en el lugar.
- **Ningún refresco programado.** Una imagen `READY` **nunca vence** y un `NOT_FOUND` no se reintenta solo
  (decidido el 2026-11-06): cada recarga cuesta pedidos a un tercero, y quien sabe que algo cambió (un envase nuevo,
  un producto recién cargado) es la tienda. Quien quiera refrescar usa la operación explícita (la API para la tienda,
  el botón del panel para nosotros), con un enfriamiento por EAN para que un bucle del consumidor no le pegue a VTEX.
- **No hay refresco al arrancar:** la caché persiste, no hay nada que calentar.

### 4.6 Medir y avisar

Las mismas métricas que allá (entradas, aciertos, fallos, % de acierto, desalojos por falta de lugar contra
vencidas) más las propias: cuántos EAN están en `LOADING`, la antigüedad de la entrada más vieja y cuántos
`NOT_FOUND` hay. Avisos por Slack con el prefijo `*[Fotos]*` y el enfriamiento de 6 h. Y la regla del proyecto
hermano: **cada cambio que toque el camino de una consulta lleva un número antes y después.**

## 5. Preguntas abiertas

1. **Decidido (2026-11-06): una imagen `READY` nunca vence.**
2. **Decidido (2026-11-06): un `NOT_FOUND` no lo reintentamos nosotros;** lo reintenta la tienda con una operación
   explícita.
3. **Respondido: la barrida son 130 productos** (la planilla de la tienda: 130 EAN, todos válidos y sin repetidos).
   `LOADING` alcanza de sobra y la carga completa cabe en minutos.
4. **Respondido: la imagen la guardamos nosotros y también la tienda** (sección 6).
5. **Plazo antes de responder `LOADING`** (propongo 8 s) y el `Retry-After` (propongo 15 s, como Chess).
6. **¿La caché se comparte entre clientes?** Propongo que sí: la imagen es del producto. Lo que sería por cliente es,
   si se usa, el registro de qué eligió cada uno.
7. **Tope de disco y qué pasa al llenarse.** Propongo alertar y no desalojar solo: una imagen desalojada es un pedido
   más a VTEX.
8. **¿Dónde en disco y con qué respaldo?** La caché es persistente, así que perder el disco es volver a consultar a
   VTEX por todo. Son pocos datos (sección 6), así que un respaldo simple alcanza.

## 6. ¿Quién guarda la imagen? Las dos partes, cada una por su motivo

- **Nosotros guardamos una copia** (la caché persistente). Sin ella no se cumple el principio de la sección 1: cada
  barrida, cada reinicio y cada refresco volverían a pedirle a VTEX lo que ya bajamos, y las URLs de los comercios
  mueren. También es lo que hace estable la respuesta: el mismo EAN devuelve la misma imagen mañana.
- **La tienda guarda la suya** (sus bytes, en su SQL), una vez que una persona del cliente la confirmó. Es la foto
  de su producto, la que publica, y no puede depender de que nuestro servicio esté disponible para mostrarla.

**Cuánto ocupa lo nuestro**, medido con la muestra de 40 EAN: unas 3,3 imágenes distintas por EAN en la posición 0 de
las tiendas, de 144 KB en promedio, más la versión recortada de las que traen banner. **Unos 0,7 MB por EAN**, o sea
**unos 90 MB para los 130 productos de la tienda**. El costo en disco no es un argumento para no guardarlas.

**Derechos:** guardar una copia en nuestro servidor es el mismo riesgo que la tienda tiene al publicarla, y sigue sin
estar resuelto quién lo asume (pregunta 5 del contrato).
