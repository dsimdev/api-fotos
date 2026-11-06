# api-fotos — Directivas para Claude Code

Servicio que recibe un código de barras (EAN) y devuelve una imagen recomendada del producto, más los
demás candidatos. Primer cliente: la tienda `senderolaser`, que no tiene imágenes de sus productos;
lo consume el backend de la tienda, que recorre todos sus códigos, y una persona del cliente confirma
o cambia cada imagen en el sistema de la tienda. Es una API con un panel de administración embebido.

> **DECIDIDO (2026-11-06): esto es una herramienta local, no un servicio.** Corre en la máquina del usuario
> cuando un cliente le pide fotos: el cliente pasa una planilla con SKU y EAN, el usuario la **arrastra sobre un
> acceso directo**, y la herramienta arma una carpeta con una foto por producto **nombrada con el SKU**, un ZIP,
> un reporte y una galería, que el usuario le entrega a la tienda para que la suba. Lo medido sigue valiendo.
> Lo que depende de hospedar un servicio (API con clave por cliente, SQL Server, login, deploy, alertas,
> `LOADING`) queda **en suspenso: no construirlo** salvo que el usuario vuelva a pedirlo. Qué se construyó, cómo
> se usa y qué límites tiene: `docs/proyecto/modelo-local.md`. **Las secciones de abajo que hablan de la API, la
> caché compartida o el servidor describen el modelo alojado y están en suspenso**; lo que sí rige para la
> herramienta es la seguridad (sección 6), el EAN validado, los datos (sección 5, salvo lo de la API) y el testing.

**Etapa actual (2026-11-06): la herramienta local anda de punta a punta** (rama `feat/skeleton`, módulo
`fotos-tool`; cuatro módulos Gradle: `fotos-core` sin framework, `fotos-stores` con el conector de las tiendas,
`fotos-tool` con la línea de comandos y `fotos-app` con el servicio y el panel del modelo alojado, intacto). Falta
publicarla (versión, changelog, merge a `main` con la confirmación del usuario) y decidir qué se hace con
`fotos-app`. Las mediciones están en `docs/proyecto/mediciones.md`; lo investigado y la prueba con 10 productos
de dyssa, en `docs/proyecto/origen/`.

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
5. **`README.md`** — actualizar "Current Version" y cualquier dato que haya cambiado (todavía no
   existe: se crea en el primer release)
6. `git tag vX.Y.Z && git push origin vX.Y.Z`
7. **Confirmación explícita del usuario** para ESTE push puntual

**Un "sí" anterior NO autoriza el próximo push. Cada deploy necesita su propia confirmación.**

Un hook pide aprobación al detectar un push que toca `main` o un tag `vX.Y.Z`. El hook es la
última barrera, no el checklist: los pasos 1–6 son tuyos.

**Si el cambio agrega o toca algo que un navegador carga directo** (el panel, Swagger, cualquier URL
que la app arma para sí misma) y el servicio se publica detrás de un proxy con sub-ruta, como los
otros dos del servidor de la tienda: compilar con `-PpanelBasePath=/api/<servicio>/admin` y
probarlo **a través** de `scripts/servidor/simular-proxy-anidado.ps1` (a copiar de
`api-bonificaciones`), nunca solo contra `localhost`: probarlo directo no reproduce el bug. Ver
`docs/metodo/lecciones.md` #19. El panel se compila siempre con la ruta configurable
(`PANEL_BASE_PATH`, por defecto `/admin`), aunque todavía no se sepa dónde se publica.

**Este servicio lo consumen otros** (el backend de la tienda). La API nueva nunca rompe al cliente
viejo: campos nuevos opcionales, endpoints nuevos en vez de cambiar la firma de los existentes, y
el borrado de un campo va en un release posterior.

## 2. Flujo de trabajo

- Desarrollar en rama (`fix/…`, `feat/…`, `spike/…`) — NUNCA commitear directo a `main`
- Merge directo a `main` + push (NO crear PRs)
- Remoto: `https://github.com/dsimdev/api-fotos.git` (origin)
- **Todavía no hay servidor ni deploy.** `main` se protege igual, por convención del kit. Cuando
  haya deploy, se define acá qué pasa al pushear a `main` y cómo se hace el rollback
- Los cambios de esquema de la base (SQL Server) van con migración **Flyway** versionada, nunca a
  mano

## 3. Antes de implementar — marco obligatorio para cada feature/fix

Para cada cosa que se pida, responder con este marco ANTES de codear:

1. **Verdad sobre lo que se solicita** — ¿sirve para el negocio? ¿lo entiendo bien? Cuestionar
   el pedido, no asumir. ¿Cómo impacta en el diseño/flujo/código?
2. **Opciones / alternativas** — al menos dos
3. **Complejidad y alcance** de cada opción
4. **Mejoras** — si es viable
5. **El entorno** — por definir: no hay servidor. Si corriera en el servidor de la tienda, ver
   `C:\dev\docs\servidor\el-servidor-de-la-tienda.md` (reloj en UTC−12, entregas que pisan ajustes
   manuales). Las consultas a las tiendas salen desde la IP del servicio: no se midió cuántas
   aguanta cada una antes de limitar, hay que medirlo antes de escalar. **El tamaño del servidor no es restricción**
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
- **Toda escritura que se pueda reintentar es idempotente.** El endpoint por lotes y los reintentos
  del consumidor no pueden duplicar descargas ni registros: la clave natural es el EAN.
- **Las migraciones son aditivas.** Quitar o renombrar va en dos releases, nunca junto con dejar
  de usar el campo.

Propias del dominio (**provisorias**: se confirman al definir el contrato de la API):

- **Nunca devolver una imagen inventada.** Si no hay candidato aceptable, el estado es explícito
  (el brief lo llama `SIN_IMAGEN`); jamás una imagen de relleno ni "la más parecida".
- **Cada candidato lleva su traza**: tienda y página del comercio de donde salió, posición en la
  tienda, resolución y si se le quitó el banner; y la recomendada dice por qué se eligió. Sirve para
  auditar y para que quien la cambie decida con información. Es la idea de "traza" de MotorFiscal.
- **Siempre se contesta desde NUESTRA caché; VTEX solo se consulta si el EAN no está.** Las entradas
  **nunca vencen** y un EAN sin imagen no se reintenta solo: solo se vuelve a consultar si la tienda
  (operación explícita de la API) o el panel piden refrescar. Decidido el 2026-11-06. La caché es por EAN, compartida entre
  clientes (las imágenes son del producto, no del cliente), con la imagen en almacenamiento propio:
  no se enlaza la URL del comercio, esas URLs mueren. **El molde es el caché de `api-bonificaciones`**
  (una sola carga por clave, no hacer esperar un request por una descarga larga, servir lo viejo si
  la fuente falla y avisar, medir desalojos). Diseño y preguntas abiertas: `docs/proyecto/cache.md`.
- **Se devuelve un frente verificado, no cualquier imagen de la tienda.** Solo la posición 0 de
  cada tienda (las posteriores pueden ser del reverso del envase, como pasó con Hesperidina) y sin
  el panel de banner de la plantilla: se recorta si es un objeto separado del producto, y si no se
  puede, esa imagen no se recomienda nunca. Nunca se corta el producto.
- **El servicio recomienda UNA imagen y devuelve además los demás candidatos** (decidido el
  2026-11-05). La tienda guarda la recomendada o la cambia por otro candidato: el caso normal es
  automático y quien quiera corrige. Si no hay ningún candidato aceptable el estado es explícito
  (sin imagen) y no hay recomendada. Borrador del contrato: `docs/proyecto/contrato-api.md`.

Nombres finales de los estados (el brief usa `OK`, `CON_MARCA`, `SIN_IMAGEN`): por definir.

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
  en una consulta, una ruta de archivo o un log. Las rutas de caché las genera el servidor, nunca
  con texto del llamador, y solo se guardan tipos de imagen en whitelist.
- **Ningún secreto hardcodeado ni trackeado en git.** Key de SerpApi (si queda de plan B), webhook
  de Slack y credenciales de GESCOM: solo variables de entorno o archivos ignorados. Nunca en un
  doc ni en un mensaje de commit.
- **Autenticación de la API: una `x-api-key` atada al cliente (tenant)**, como `api-bonificaciones`
  (a confirmar al construirla). **El panel usa usuarios con BCrypt y sesión**, no una clave
  compartida. El gating real es siempre del servidor.
- **La consulta de demostración del panel (`fotos.demo.enabled`) NO tiene autenticación**: apagada
  por defecto, solo en localhost, nunca desplegada. Se reemplaza por la API autenticada del panel.

## 7. Diseño de la API

La API no tiene UI propia (el panel es aparte). Convenciones provisorias, a confirmar:

- Un endpoint por EAN y **otro por lotes**. El pedido lleva solo el EAN: la coincidencia es por EAN
  exacto y el nombre del artículo no se usa para decidir (con Google hacía falta; con VTEX no).
- Respuesta con **estado explícito, una imagen recomendada y la lista de candidatos con su traza**
  (sección 5), nunca solo una URL. Las imágenes se sirven desde nuestra copia. Borrador en
  `docs/proyecto/contrato-api.md`.
- Errores con **código de dominio**, no solo el HTTP status.
- Las reglas que cambian seguido (la lista y el orden de las tiendas, los criterios para elegir la
  recomendada) van en datos, no en código.

## 8. Código

- **Rutas, archivos, clases, funciones y variables en INGLÉS**, salvo los términos del negocio.
  **Los textos que ve una persona en el panel van en español (AR)**, sin i18n por ahora, como los
  otros paneles; los mensajes de la API y de los logs, en inglés.
- Sin comentarios en el código salvo que el WHY sea no obvio
- **Términos del negocio, respetarlos tal cual**: EAN y los nombres de sistemas y clientes (GESCOM,
  dyssa, senderolaser). La lista se completa cuando aparezcan más.

## 9. Testing

Herramienta: JUnit 5 + Spring Boot Test, y **WireMock** (un servidor HTTP de verdad como stub, no
mocks en proceso) para los conectores de las tiendas. Tres capas, en `docs/metodo/testing.md`. La
regla corta:

- Lógica pura (normalización del nombre, validación del EAN, filtros de dominio, detección del tipo
  por bytes) → unit test
- **Feature que toca datos → testear el SERVICIO que orquesta** (buscar → filtrar → descargar →
  validar → cachear) **con las fuentes y el almacenamiento mockeados**, no solo las funciones
  puras. El bug vive en el ensamblado. Casos obligatorios: la fuente devuelve cero resultados,
  todos los resultados son redes sociales, la descarga falla a mitad, el servidor responde
  `application/octet-stream`
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

**Arquitectura**: **Java 21 + Spring Boot 3.4, Gradle multi-módulo (Kotlin DSL)**: un núcleo sin
framework (conectores a las tiendas, pipeline de imágenes) y una app que lo expone, como
`api-impuestos` y `api-bonificaciones`. Más un **panel de administración Next.js (export estático)
embebido en el jar y servido en `/admin`: todos los servicios llevan uno**, y se arma con el mismo
molde que esos dos. Persistencia: **SQL Server + Flyway** como los hermanos (usuarios del panel,
claves de API, historial de llamadas, índice de la caché por EAN); las imágenes van a disco, y
dónde queda por definir. Fuente principal, ya medida: los **catálogos VTEX de supermercados
argentinos consultados por EAN** (Jumbo, Carrefour, Disco, Vea, Masonline y tiendas
regionales como Josimar y Cordiez): coincidencia exacta de EAN, ~0,5 s por consulta, imágenes de
1000 a 2000 px. Detalle y cifras en `docs/proyecto/mediciones.md`. **En el catálogo real de
senderolaser (130 productos): 96% está en alguna tienda y 94% tiene un frente utilizable; el 95%
de las imágenes trae el panel de banner, así que el recorte es la ruta principal y no un extra.**
Idea sugerida, **no decidida**:
fuentes intercambiables detrás de la misma interfaz, en orden caché propia → VTEX por EAN →
respaldo por definir (Open Food Facts, SerpApi). Ver `docs/proyecto/origen/` y, cuando exista,
`docs/proyecto/arquitectura.md`.

**Restricción dominante: independencia de terceros.** Está decidido que el servicio sea automático
(recomienda una imagen sin que nadie elija; la tienda puede cambiarla) y lo más independiente
posible de terceros. Cada tienda puede cambiar o cortar su API, y
hay EAN que existen en una sola tienda: por eso las fuentes tienen que ser intercambiables y se
consultan varias tiendas por EAN.

**Decididas (2026-11-05)**: servicio nuevo en repo propio; automático por defecto, con **una
imagen recomendada y los demás candidatos para que la tienda pueda cambiarla** (la tienda recorre todos sus códigos y una persona del cliente
confirma o cambia cada imagen, incluso por una propia, en el sistema de la tienda: entendido el
2026-11-06, a confirmar); **siempre desde nuestra caché, VTEX solo si no está**; **SQL Server +
Flyway**; **Java 21 + Spring Boot con un panel Next embebido**; **VTEX por EAN como
fuente principal** (el usuario lo aceptó después de medirlo). **Descartado por medición**: el
scraping propio de Google, que bloquea a un navegador automatizado en la primera request y no se
esquiva (ver `docs/proyecto/mediciones.md`). SerpApi queda de plan B y Open Food Facts de respaldo.
**Abiertas**: **la política de la caché** (vencimientos, EAN sin imagen, cómo responde a una barrida
de la tienda: `docs/proyecto/cache.md`); dónde se guardan las imágenes en disco; si un candidato con banner que no
se pudo recortar se lista (marcado, nunca recomendado) o se descarta; qué se devuelve cuando ninguna
tienda tiene una imagen sin banner (el recorte funcionó en los 12 casos revisados, pero falta un
verificador); verificación con
modelo de visión, ahora acotada a "frente limpio del producto correcto"; fuentes para los EAN que
ninguna tienda tiene; cómo guarda `senderolaser` las imágenes en su SQL; y los **términos de uso de
las APIs de VTEX y los derechos de las imágenes** (consultar antes de producción).

**Permisos**: por definir.

**Fuera de alcance**: cargar las imágenes en el SQL del cliente (se propone que el backend de la
tienda las traiga del servicio) y los derechos de uso de las fotos (son de los comercios y las
marcas; el riesgo de publicarlas lo asume quien las publica, y hay que acordar quién).
