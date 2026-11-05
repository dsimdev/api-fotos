# Brief para arrancar el proyecto de fotos por código de barras

> Escrito el 2026-11-05 desde `api-impuestos`. Es para la primera sesión del proyecto nuevo (nombre
> propuesto: `api-fotos`, carpeta `C:\Dev\api-fotos`). **Desde `api-impuestos` no se creó nada:** ni la
> carpeta, ni el repo, ni la instalación del kit. Eso se hace desde el proyecto nuevo.
>
> Lo técnico y lo investigado está en
> [gateway-de-imagenes-por-codigo-de-barras.md](gateway-de-imagenes-por-codigo-de-barras.md). Esto es
> el resto: cómo arrancar, qué hay que decidir y qué no olvidar de un proyecto nuevo.

## 1. En una frase

Un servicio que recibe un **código de barras (EAN)** y devuelve la foto del producto, 100% automático,
para un cliente de la tienda (`senderolaser`) que no tiene imágenes. Las pruebas se hicieron con los
productos de dyssa porque senderolaser todavía no tiene los EAN cargados.

## 2. Estado de las decisiones

**Tomadas por el usuario:** servicio nuevo en repo propio; 100% automático; lo más independiente posible
de terceros; **scraping propio de Google** (SerpApi se probó y se descartó, queda de plan B); Open Food
Facts de respaldo.

**Abiertas, se deciden en la primera sesión:**

1. **La tecnología.** Condiciona el build, los tests, la versión y las migraciones de `CLAUDE.md`.
2. **Cómo se consulta Google sin SerpApi** y desde dónde corre. Medir antes de construir (sección 5).
3. **La verificación de la imagen con un modelo de visión.** Para decidir junto con la tecnología.
4. **Cómo guarda senderolaser las imágenes en su SQL** (binario, ruta o URL) y por lo tanto cómo se las
   entregamos.
5. Qué hacer con las fotos correctas pero con logo o banner de otro comercio.
6. **El riesgo legal del scraping propio.** Google demandó a SerpApi por saltar su barrera anti-bots; si
   lo hacemos nosotros, esa posición es nuestra. Es una lectura de prensa, no una opinión legal.

## 3. Crear el proyecto con el kit

El kit es un repo git con scripts. En disco hay **dos carpetas**:

- `C:\Dev\claudio-kit` — la vigente: repo git, v1.1.0, tiene `scripts\instalar.ps1`, `verificar.ps1`,
  `actualizar.ps1` y `migrar-memoria.ps1` (verificado el 2026-11-05).
- `C:\Dev\Setup Claudio multiporject` — una copia más vieja (modificada el 2026-08-25): sin git, sin
  scripts, sin VERSION. Todas sus rutas de archivo también están en `claudio-kit`. No se comparó el
  contenido, así que no se sabe si sus hooks están al día.

El usuario dijo que el kit "se llama setup multi project ahora". En disco no hay ninguna carpeta con ese
nombre salvo la vieja. **Antes de instalar, confirmar con el usuario cuál es la vigente.** No instalar desde
la vieja: no tiene scripts. Si el kit se renombró o se mudó de carpeta, ojo con las rutas que tenga
escritas (`actualizar.ps1` guarda el origen en `.claude/KIT_VERSION`).

Pasos, según `INSTALAR.md` del kit (leído el 2026-11-05):

1. **Crear la carpeta y `git init -b main` primero.** Sin repo los hooks no bloquean nada y no avisan.
   `instalar.ps1` exige que la carpeta ya exista.
2. `cd C:\Dev\claudio-kit` y correr `.\scripts\instalar.ps1 -Proyecto C:\Dev\api-fotos -DryRun`; revisar y
   correr sin `-DryRun`. **Nunca sobreescribe**: lo que ya existe lo saltea y lo reporta. Módulo a decidir:
   `agentes` ("siempre que haya código propio"). Los otros no aplican.
3. **Rellenar `CLAUDE.md`.** El script lo deja con `{{PLACEHOLDERS}}`, y uno sin rellenar es **peor** que
   no tenerlo. Lo que ya se sabe está en la sección 4. Lo que depende de la tecnología no se rellena hasta
   decidirla; mientras tanto, escribir "por definir" explícito en vez de dejar el placeholder.
4. **Rellenar el bloque Estado de `MEMORY.md`** (una o dos líneas: qué es, en qué etapa está, dónde está la
   doc viva).
5. **Reiniciar la sesión.** Los hooks nuevos no se cargan hasta reiniciar.
6. `.\scripts\verificar.ps1 -Proyecto C:\Dev\api-fotos`, y probar los 4 comportamientos de los hooks
   parado en `main` (editar código bloquea, editar `README.md` deja, `git commit` bloquea, `git push origin
   main` pide aprobación).

**Una sola cosa de la memoria:** la carpeta de memoria sale de la ruta del proyecto y **no existe hasta
abrir una sesión ahí** (o hasta que la crea el script). Elegir el nombre de la carpeta una sola vez:
cambiarlo después deja la memoria huérfana. Abrir siempre desde la misma ruta.

**Decisiones que el kit asume y hay que confirmar** (`ADAPTAR.md`): push a `main` = producción (¿hay
deploy?); sin PRs; auditoría cada 10 minors; dos changelogs (¿hay usuario final que lea notas?, la tienda
consume esto); código en inglés; una carpeta = un repo.

## 4. `CLAUDE.md`: lo que ya se sabe y lo que no

| Placeholder / sección | Valor |
| --- | --- |
| `{{PROYECTO}}` | `api-fotos` (confirmar) |
| Descripción | servicio que recibe un EAN y devuelve la foto del producto; lo consume la tienda |
| `{{CMD_BUILD}}`, `{{CMD_TEST}}`, `{{ARCHIVO_VERSION}}`, `{{HERRAMIENTA_MIGRACIONES}}`, `{{STACK Y PIEZAS}}` | **por definir** (depende de la tecnología). Si no hay manifiesto, `.claude/VERSION` |
| `{{ENTORNO}}` | **por definir**. Si corre en el servidor de la tienda: Windows, sin Docker, reloj en UTC-12 que no se cambia (ver `C:\dev\docs\servidor\el-servidor-de-la-tienda.md`). Para la consulta a Google se sugirió **no** usar ese servidor (riesgo de bloqueo de IP) |
| `{{IDIOMA_BASE}}` | no hay UI: la regla se reduce a "todo en inglés salvo los términos del negocio" |
| Sección 5, reglas duras | candidatas: **nunca devolver una imagen inventada** (estado explícito `SIN_IMAGEN`); **cada resultado lleva su origen** (página del comercio) y su confianza; caché por EAN. Parecen las de la "traza" de MotorFiscal |
| Sección 6, seguridad | la URL de la imagen sale de un resultado de búsqueda, o sea de un tercero: **riesgo de SSRF** (bloquear IPs privadas, esquemas raros, limitar tamaño y tiempo, detectar el tipo por los bytes). Secretos (key de SerpApi si es plan B) fuera de git |
| Sección 7 | convenciones de API (no es UI): un endpoint por EAN y **uno por lotes**, errores con código de dominio |
| Fuera de alcance | cargar las imágenes en el SQL del cliente (se propone que el backend de la tienda las traiga); los derechos de uso de las fotos |

## 5. Primera tarea sugerida

Antes de construir, **medir** el scraping propio con los mismos 10 productos de dyssa: si Google devuelve
resultados a un navegador automatizado, cuánto tarda cada búsqueda y cuántas aguanta antes de bloquear.
Comparar contra [prueba-2026-11-05/veredictos-dyssa.json](prueba-2026-11-05/veredictos-dyssa.json). El
tiempo de SerpApi tampoco se midió: la lentitud que motivó descartarlo es una observación del usuario.

Y, por el marco de `CLAUDE.md` (sección 3): opciones, complejidad y opinión antes de implementar.

## 6. Qué decirle a Claude en la primera sesión

> Leé `CLAUDE.md` y `docs/metodo/` completo. Después leé
> `C:\dev\docs\imagenes\brief-proyecto-nuevo.md` y
> `C:\dev\docs\imagenes\gateway-de-imagenes-por-codigo-de-barras.md`. Cuando termines, decime qué
> entendiste del alcance, qué decisiones siguen abiertas y qué preguntas te quedan. No codees nada todavía.

## 7. Secretos y permisos

- **Key de SerpApi:** una línea de 64 caracteres en `C:\dev\api-impuestos\.env` (ignorado por git).
  Moverla al proyecto nuevo solo si SerpApi queda de plan B; si no, borrarla. Nunca copiarla a un doc.
- **Credenciales de dyssa (GESCOM):** están en la colección Postman
  `OneDrive\Escritorio\Axum\Postman\Collections\[GESCOM] Dyssa.postman_collection.json`. El permiso de
  esta sesión fue solo para leer `get-articulos` y **no pasa a la sesión nueva**: hay que pedirlo de nuevo.
- Los scripts de `prueba-2026-11-05/` reciben esas rutas por argumento y no imprimen nada sensible.

## 8. Otros docs que pueden servir

- `C:\dev\docs\servidor\` — el servidor de la tienda, el reloj en UTC-12, y los viajes de ida y vuelta con
  `api-bonificaciones`.
- `C:\dev\docs\servidor\alertas-por-slack.md` — por si se quiere avisar cuando el scraping falle o Google
  bloquee. El webhook de Slack es un secreto: solo por variable de entorno.
