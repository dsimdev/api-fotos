# api-fotos — Directivas para Claude Code

Herramienta local que recibe la planilla de un cliente (SKU y EAN) y arma una carpeta con **una foto por
producto, nombrada con el SKU**, sacada de los catálogos públicos de supermercados argentinos (VTEX) y sin el
panel de plantilla del proveedor. Primer cliente: la tienda `senderolaser`, que no tiene imágenes de sus
productos; el usuario corre la herramienta cuando el cliente pide las fotos y le entrega el resultado a la tienda
para que lo suba. (Nació como un servicio con API y panel; ese modelo quedó frenado, ver abajo.)

> **DECIDIDO (2026-11-06): esto es una herramienta local, no un servicio.** Corre en la máquina del usuario
> cuando un cliente le pide fotos: el cliente pasa una planilla con SKU y EAN, el usuario la **arrastra sobre el
> lanzador** (instalado en `C:\Dev\app-fotos`), y la herramienta arma, dentro de esa carpeta, una `Fotos <fecha>`
> con una foto por producto **nombrada con el SKU**, un ZIP, un reporte y una galería, que el usuario le entrega a la tienda para que la suba. Lo medido sigue valiendo.
> Lo que depende de hospedar un servicio (API con clave por cliente, SQL Server, login, deploy, alertas,
> `LOADING`) queda **en suspenso: no construirlo** salvo que el usuario vuelva a pedirlo. Qué se construyó, cómo
> se usa y qué límites tiene: `docs/proyecto/modelo-local.md`. **Las secciones de abajo que hablan de la API, la
> caché compartida o el servidor describen el modelo alojado y están en suspenso**; lo que sí rige para la
> herramienta es la seguridad (sección 6), el EAN validado, los datos (sección 5, salvo lo de la API) y el testing.

**Etapa actual (2026-11-06): v0.3.0.** Cuatro módulos Gradle: `fotos-core` (sin framework: planilla, recorte del
panel, elección de la foto, caché, entrega), `fotos-stores` (conector de las tiendas y descarga segura),
`fotos-tool` (la línea de comandos) y `fotos-app` (el servicio y el panel del modelo alojado). **`fotos-app` queda
frenado pero NO se saca** (decidido el 2026-11-06): se conserva por si hay que escalar a un servicio, sin construir
nada nuevo encima, y sigue compilando con sus tests. Cómo se usa: `README.md`. Cómo funciona y qué límites tiene:
`docs/proyecto/modelo-local.md`. Lo medido: `docs/proyecto/mediciones.md`. Lo investigado antes y la prueba con
10 productos de dyssa: `docs/proyecto/origen/`.

> **Núcleo portable — no tocar sin motivo real:** secciones 1, 2, 3, 4, 8, 10, 11, 12.
> **Por proyecto:** secciones 5, 6, 7, 9, 13.

---

## 1. DEPLOY CHECKLIST — obligatorio antes de todo push a main

Antes de `git push origin main`, completar en este orden **sin excepción**:

1. `./gradlew build` pasa sin errores (build + tests verdes; reconstruye también el panel si hay
   `node_modules`)
2. **Bump versión** en `.claude/VERSION` (arranca en `0.1.0`) — semver: patch=fix/tweak,
   minor=feature nueva, major=rediseño/breaking. El `version` del `build.gradle.kts` raíz se
   bumpea **junto** con `.claude/VERSION`, en el mismo commit (el build falla si difieren)
3. **`CHANGELOG.md`** — entrada técnica en inglés con todos los cambios del release
4. ~~`CHANGELOG_USER.md`~~ — **no se usa en este proyecto** (decisión 2026-11-05: un solo changelog,
   en inglés). Se conserva el número para no desalinear `docs/metodo/`
5. **`README.md`** — actualizar "Current Version" y cualquier dato que haya cambiado
6. `git tag vX.Y.Z && git push origin vX.Y.Z`
7. **Confirmación explícita del usuario** para ESTE push puntual

**Un "sí" anterior NO autoriza el próximo push. Cada deploy necesita su propia confirmación.**

Un hook pide aprobación al detectar un push que toca `main` o un tag `vX.Y.Z`. El hook es la
última barrera, no el checklist: los pasos 1–6 son tuyos.

**Solo si se retoma el servicio (`fotos-app`, hoy frenado): si el cambio agrega o toca algo que un navegador
carga directo** (el panel, Swagger, cualquier URL que la app arma para sí misma) y el servicio se publica detrás de un proxy con sub-ruta, como los
otros dos del servidor de la tienda: compilar con `-PpanelBasePath=/api/<servicio>/admin` y
probarlo **a través** de `scripts/servidor/simular-proxy-anidado.ps1` (a copiar de
`api-bonificaciones`), nunca solo contra `localhost`: probarlo directo no reproduce el bug. Ver
`docs/metodo/lecciones.md` #19. El panel se compila siempre con la ruta configurable
(`PANEL_BASE_PATH`, por defecto `/admin`), aunque todavía no se sepa dónde se publica.

**Si se retoma el servicio, lo consumen otros** (el backend de la tienda). La API nueva nunca rompe al cliente
viejo: campos nuevos opcionales, endpoints nuevos en vez de cambiar la firma de los existentes, y
el borrado de un campo va en un release posterior.

## 2. Flujo de trabajo

- Desarrollar en rama (`fix/…`, `feat/…`, `spike/…`) — NUNCA commitear directo a `main`
- Merge directo a `main` + push (NO crear PRs)
- Remoto: `https://github.com/dsimdev/api-fotos.git` (origin)
- **No hay servidor ni deploy.** Publicar es versión + changelog + tag + push, y después reinstalar la herramienta en
  la PC del usuario con `scripts/herramienta/instalar.ps1`. `main` se protege igual, por convención del kit. El
  rollback es volver a instalar la versión anterior (su tag). Si se retoma el servicio, se define acá su deploy
- Los cambios de esquema de la base (SQL Server) van con migración **Flyway** versionada, nunca a mano (solo si se
  retoma el servicio: hoy no hay base)

## 3. Antes de implementar — marco obligatorio para cada feature/fix

Para cada cosa que se pida, responder con este marco ANTES de codear:

1. **Verdad sobre lo que se solicita** — ¿sirve para el negocio? ¿lo entiendo bien? Cuestionar
   el pedido, no asumir. ¿Cómo impacta en el diseño/flujo/código?
2. **Opciones / alternativas** — al menos dos
3. **Complejidad y alcance** de cada opción
4. **Mejoras** — si es viable
5. **El entorno** — la PC del usuario (Windows, Java 21). Las consultas a las tiendas salen desde su IP: en las
   mediciones (`mediciones.md`) no hubo un solo 429 ni 403, pero solo se probó hasta 3 productos a la vez; con más ritmo
   no se midió, hay que medirlo antes de subirlo. Si algún día corriera en el servidor de la tienda, ver
   `C:\dev\docs\servidor\el-servidor-de-la-tienda.md` (reloj en UTC−12, entregas que pisan ajustes
   manuales). **El tamaño del servidor no es restricción**
   (decisión 2026-11-05: cuando haga falta se invierte en infra, y bonificaciones ya alerta por
   memoria); medir memoria y CPU para dimensionar, no para descartar. Pensar costos, encaje y
   viabilidad
6. **Resumen y opinión** — resumen, plan, opinión

Esperar elección del usuario; recién entonces implementar. **Esto se cumple sobre todo cuando el
pedido parece obvio**: ahí es donde más seguido se construye lo que no era.

## 4. Regla de mínimo cambio

Cuando el pedido es simple ("guardá X", "cambiá Y"), hacer el **mínimo cambio posible**. No
interpretar como oportunidad de rediseñar el sistema. No agregar endpoints, campos ni flujos que
no se pidieron explícitamente. No reemplazar lógica existente sin pedido explícito.

## 5. Reglas duras de datos

Las tres primeras son portables y no se negocian:

- **Ninguna operación crítica decide qué escribe a partir de un snapshot viejo o parcial** (cache,
  lo que mande el llamador). Para responder sirve; para escribir, se consulta la fuente de verdad
  primero. *(Esto corrompió datos reales en un proyecto anterior — ver `docs/metodo/lecciones.md`.)*
- **Toda escritura que se pueda reintentar es idempotente.** En la herramienta: volver a correr la misma planilla
  reutiliza lo ya bajado y nunca pisa una entrega anterior (cada corrida es una carpeta nueva); el mismo EAN no se
  consulta dos veces, ni entre corridas (la caché) ni dentro de una (varias filas con el mismo EAN). La clave
  natural es el EAN.
- **Las migraciones son aditivas.** Quitar o renombrar va en dos releases, nunca junto con dejar
  de usar el campo. (Solo si se retoma el servicio: hoy no hay base.)

Propias del dominio. Lo que dice "servicio" o "API" es del modelo alojado, en suspenso:

- **Nunca entregar una imagen inventada.** Si no hay candidato aceptable, el estado es explícito (`SIN_IMAGEN`);
  jamás una imagen de relleno ni "la más parecida". Estados de la entrega: `OK` (original de la tienda),
  `OK_RECORTADA` (se le quitó el panel), `SIN_IMAGEN`, `ERROR` (una tienda no respondió: se vuelve a correr) y
  `DATOS_INVALIDOS` (la fila se ignora, ver abajo).
- **Cada foto entregada dice de dónde salió.** `reporte.csv` trae la tienda, si se recortó, el tamaño y los avisos
  (borde, borrosa, baja resolución). La página del comercio y la URL de la imagen quedan en la caché
  (`entry.properties`), **no** en el reporte, y el reporte **no dice por qué se eligió** esa foto entre las
  candidatas: es una mejora posible.
- **Siempre se contesta desde NUESTRA caché; las tiendas solo se consultan si el EAN no está.** La caché son archivos
  (una carpeta por EAN), compartida entre clientes (las imágenes son del producto, no del cliente), con la imagen
  guardada: no se enlaza la URL del comercio, esas URLs mueren. Las entradas **nunca vencen**; "no lo vende nadie" y
  "sin foto utilizable" solo se guardan si **todas** las tiendas respondieron (un error nunca es una respuesta) y solo
  se vuelve a consultar con `--refresh`. Decidido el 2026-11-06. El molde conceptual es el caché de
  `api-bonificaciones`; el diseño del servicio alojado, en suspenso, está en `docs/proyecto/cache.md`.
- **Se entrega un frente verificado, no cualquier imagen de la tienda.** Solo la posición 0 de cada tienda (las
  posteriores pueden ser del reverso del envase, como pasó con Hesperidina) y sin el panel de plantilla: se recorta
  si es un objeto separado del producto, se **verifica la salida**, y si no se puede, esa imagen no es candidata.
  Nunca se corta el producto. Una foto rebanada por la fuente o borrosa pasa al fondo, nunca se descarta (los umbrales
  son provisorios, de un solo catálogo).
- **La herramienta elige UNA foto por producto**, por capas (`CandidatePicker`); la persona la cambia reemplazando el
  archivo. En el modelo alojado se devolvía además la lista de candidatos (`docs/proyecto/contrato-api.md`).
- **Lo que está mal se ignora** (decidido el 2026-11-06). Una fila sin EAN, con EAN inválido, sin SKU, con un SKU
  que no sirve de nombre de archivo o con un SKU repetido con otro EAN no se consulta, no se imprime fila por fila y no
  sale en la galería: se cuenta por tipo en el resumen y se explica en `reporte.csv`.
- **La herramienta NO detecta marcas ajenas** (logos o sellos de comercios, textos agregados). El usuario pidió que las
  fotos no las tengan; en el catálogo medido una revisión independiente no encontró ninguna, pero la galería es la
  revisión que importa antes de entregar. Decirlo siempre.

## 6. Seguridad — no negociable

- **SSRF.** La URL de la imagen sale de la respuesta de una tienda, o sea de un tercero. Antes de
  descargar: solo `http`/`https`; bloquear IPs privadas, loopback y link-local (también después de
  una redirección); límite de tamaño y de tiempo; **detectar el tipo de imagen por los primeros
  bytes**, no por el `Content-Type` (un servidor respondió `application/octet-stream` con una imagen
  de verdad). **Pedir `Accept: image/jpeg,image/png`:** seis de las siete tiendas lo honran y sirven
  JPG, pero **Josimar lo ignora y sirve WebP** (79 de 791 imágenes en la medición de los 130
  productos). Java no lee WebP sin una librería extra, así que el descargador detecta WebP por los
  bytes y **o la lee (librería) o descarta esa imagen y sigue con otra**, nunca falla la consulta.
- **El EAN es input externo.** Se valida (solo dígitos, largo y dígito verificador) antes de usarlo
  en una consulta, una ruta de archivo o un log. Las rutas de caché las genera la herramienta, nunca
  con texto de la planilla, y solo se guardan tipos de imagen en whitelist.
- **La planilla es input externo** (viene de un cliente). El xlsx se lee con DTD y entidades externas apagados y un
  tope de tamaño por entrada del zip (un XML puede hacer leer un archivo local; un zip chico puede inflarse a
  gigas). El SKU termina siendo un nombre de archivo, así que se **valida y nunca se "limpia"** (cambiarlo haría que
  la foto deje de coincidir con el SKU de la tienda). Los textos que van al reporte y a la galería se escapan (HTML) y
  se neutralizan para una planilla (una celda que empieza con `=` es una fórmula).
- **Ningún secreto hardcodeado ni trackeado en git.** Key de SerpApi (si queda de plan B), webhook
  de Slack y credenciales de GESCOM: solo variables de entorno o archivos ignorados. Nunca en un
  doc ni en un mensaje de commit.
- *(Modelo alojado, en suspenso.)* **Autenticación de la API: una `x-api-key` atada al cliente (tenant)**, como
  `api-bonificaciones` (a confirmar al construirla). **El panel usa usuarios con BCrypt y sesión**, no una clave
  compartida. El gating real es siempre del servidor.
- *(`fotos-app`, frenado.)* **La consulta de demostración del panel (`fotos.demo.enabled`) NO tiene autenticación**:
  apagada por defecto, solo en localhost, nunca desplegada, y se niega a arrancar si el servidor no escucha en
  localhost. Se reemplaza por la API autenticada del panel.

## 7. Diseño de la API (modelo alojado, EN SUSPENSO)

**Nada de esto está construido ni rige hoy: la herramienta local no tiene API.** Se conserva por si se retoma el
servicio (`fotos-app`). La API no tiene UI propia (el panel es aparte). Convenciones provisorias, a confirmar:

- Un endpoint por EAN y **otro por lotes**. El pedido lleva solo el EAN: la coincidencia es por EAN
  exacto y el nombre del artículo no se usa para decidir (con Google hacía falta; con VTEX no).
- Respuesta con **estado explícito, una imagen recomendada y la lista de candidatos con su traza**
  (sección 5), nunca solo una URL. Las imágenes se sirven desde nuestra copia. Borrador en
  `docs/proyecto/contrato-api.md`.
- Errores con **código de dominio**, no solo el HTTP status.
- Las reglas que cambian seguido (la lista y el orden de las tiendas, los criterios para elegir la
  recomendada) van en datos, no en código. (En la herramienta, las tiendas están en `stores.txt`; los criterios de
  elección todavía son código, en `CandidatePicker`.)

## 8. Código

- **Rutas, archivos, clases, funciones y variables en INGLÉS**, salvo los términos del negocio.
  **Los textos que ve una persona van en español (AR)**: el panel, y en la herramienta la consola, el reporte y la
  galería, sin i18n por ahora; los mensajes de la API y de los logs, en inglés.
- Sin comentarios en el código salvo que el WHY sea no obvio
- **Términos del negocio, respetarlos tal cual**: EAN y los nombres de sistemas y clientes (GESCOM,
  dyssa, senderolaser). La lista se completa cuando aparezcan más.

## 9. Testing

Herramienta: JUnit 5 (más Spring Boot Test solo en `fotos-app`), y **WireMock** (un servidor HTTP de verdad como stub,
no mocks en proceso) para los conectores de las tiendas y la descarga de imágenes. Tres capas, en
`docs/metodo/testing.md`. La regla corta:

- Lógica pura (validación del EAN, lector de planillas, recorte del panel, elección de la foto, detección del tipo
  por bytes) → unit test
- **Feature que toca datos → testear el SERVICIO que orquesta** (en la herramienta, `PhotoFinder` y `BatchRun`:
  buscar → descargar → filtrar → elegir → cachear) **con las tiendas y la red reemplazadas**, no solo las funciones
  puras. El bug vive en el ensamblado. Casos obligatorios: la tienda devuelve cero resultados, la imagen trae el
  panel y no se puede recortar, la descarga falla a mitad, el servidor responde `application/octet-stream`, la
  imagen es WebP
- **Las imágenes de los tests se dibujan en memoria**: ninguna foto de terceros entra al repo
- **Una entrega se revisa completa y por alguien que no sabe qué esperás ver** (lección 20 de
  `docs/metodo/lecciones.md`): un agente independiente sobre todas las fotos, no una muestra propia
- Todo bug que llega a producción se cierra con un test que lo fija
- **Los tests automáticos no pegan a las tiendas ni a otros terceros reales** (riesgo de bloqueo
  de IP y de abuso). Las mediciones contra terceros son scripts aparte, con pocas consultas y pausas
- La vara para medir cualquier método nuevo es `docs/proyecto/origen/prueba-2026-11-05/veredictos-dyssa.json`
  (10 productos, 30 candidatos). Ojo: los veredictos los puso una persona mirando cada imagen, no es
  una medición automática, y la muestra es de marcas grandes

Los tests viven en `src/test` de cada módulo. Los que pegan a tiendas reales llevan un tag excluido
por defecto (como `erp` en `api-bonificaciones`) y se corren con una propiedad de Gradle, para que
`./gradlew build` siga verde en cualquier máquina y sin red.

## 10. Commits y mensajes

- NO incluir "Generated with Claude Code" ni ninguna mención a Claude/Anthropic en commits,
  PR bodies ni descripciones
- Formato de commit: `tipo(scope): descripción breve`
- Sin emojis salvo pedido explícito

## 11. Respuestas

- Respuestas cortas y directas
- **Verificar contra el código, nunca contra la memoria.** Antes de afirmar que algo está hecho,
  pendiente o roto: grep, read, git log. La memoria es una foto del pasado
- Cuando algo no se verificó, decirlo — no adivinar

## 12. Auditoría

Auditoría 360 cada 10 minors (`docs/metodo/auditoria-360.md`). El contador vive en
`.claude/LAST_AUDIT` y un hook avisa al pushear a `main` cuando el delta llega a 10. Al cerrar
una auditoría, actualizar ese archivo con la versión auditada.

## 13. Contexto del proyecto

**Arquitectura**: **Java 21, Gradle multi-módulo (Kotlin DSL)**. `fotos-core` (sin framework: lector de planillas xlsx y
csv sin librerías, detector y recortador del panel con `java.awt`, elección de la foto, caché en carpeta, escritura de
la entrega), `fotos-stores` (conector VTEX por EAN con ritmo, reintento y cortacircuito, y descarga segura de
imágenes), `fotos-tool` (la línea de comandos: `Main`, `PhotoFinder`, `BatchRun`) y `fotos-app` (Spring Boot + panel
Next.js embebido: el servicio del modelo alojado, **frenado, no se saca**). La herramienta corre con `java`, sin Spring
ni base de datos: la caché son archivos (`%LOCALAPPDATA%\api-fotos\cache`, una carpeta por EAN) y se instala en
`C:\Dev\app-fotos` con `scripts/herramienta/instalar.ps1`.

**Fuente**: los catálogos VTEX de siete tiendas (Jumbo, Carrefour, Masonline, Josimar, Cordiez, Disco y Vea),
consultados por EAN con coincidencia exacta; la lista y el orden están en `fotos-tool/src/main/resources/stores.txt`.
Detalle y cifras en `docs/proyecto/mediciones.md`. **En el catálogo real de senderolaser (130 productos): 125 están en
alguna tienda (96%) y se entregaron 124 fotos (95%).** El 95% de las imágenes que sirven las tiendas trae el panel de
plantilla, así que el recorte es la ruta principal y no un extra. (Las cifras de calidad de la fase A4 son inválidas, las
reemplaza la A5.) Una corrida de 130 productos tarda unos 2 minutos con 3 a la vez.

**Restricción dominante: independencia de terceros.** La herramienta es automática y lo más independiente posible de
terceros. Cada tienda puede cambiar o cortar su API (la búsqueda por EAN de VTEX no está documentada) y hay EAN que
existen en una sola tienda: por eso se consultan varias tiendas por EAN y la lista es un dato, no código.

**Decididas (2026-11-05)**: repo propio; **VTEX por EAN como fuente principal** (el usuario lo aceptó después de
medirlo). **Descartado por medición**: el scraping propio de Google, que bloquea a un navegador automatizado en la
primera request y no se esquiva (ver `docs/proyecto/mediciones.md`). SerpApi y Open Food Facts quedaron de respaldo
posible y **no están implementados**.

**Decididas (2026-11-06)**: **herramienta local de arrastrar y soltar en vez de servicio** (no se agrega otro servicio al
servidor de la tienda); programa en `C:\Dev\app-fotos` y cada corrida crea ahí una `Fotos <fecha>` (nunca junto a la
planilla); el instalador no crea accesos directos (los hace el usuario); la entrega es `<SKU>.jpg` o `.png` según lo que
la foto realmente es, sin convertir, más un zip, `reporte.csv` y `galeria.html`; las columnas de la planilla se buscan por
nombre de encabezado (el export de artículos de Axum se arrastra tal cual: `codigo` es el SKU y `codbarra` el EAN); la
caché nunca vence; lo que está mal se ignora; los derechos de las imágenes los asume el usuario; `fotos-app` queda
frenado y se conserva.

**Abiertas / pendientes**: los **términos de uso de las APIs de VTEX** (sin consultar); **detección automática de marcas
ajenas** (hoy se revisa a ojo en la galería); un aviso en el reporte cuando las tiendas discrepan entre sí (hoy manda la
mayoría, sin avisar); **guardar todos los candidatos en la caché** (hoy solo la elegida, así que cambiar las reglas de
elección exige `--refresh`); fuentes para los EAN que ninguna tienda tiene (marca propia de las cadenas: 4 de 130); los
umbrales de borde y nitidez salen de un solo catálogo; no se midió cómo responden las tiendas con más de 3 productos a la
vez; y traer los artículos directo de la API de Axum en vez de exportarlos (el gateway documentado en agosto no traía
código de barras; sin reverificar).

**Permisos**: no aplican a la herramienta (no hay usuarios ni login). Si se retoma el servicio, por definir.

**Fuera de alcance**: cargar las imágenes en el SQL del cliente (la tienda las sube desde el zip) y los derechos de uso de
las fotos (son de los comercios y las marcas; el riesgo de publicarlas lo asume el usuario, decidido el 2026-11-06).
