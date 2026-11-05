# Auditoría 360

Revisión sistemática de todo lo construido desde la última auditoría. **Cadencia: cada 10
minors.** Un hook lo recuerda solo al intentar pushear a `main`, comparando la versión del
proyecto con `.claude/LAST_AUDIT`.

El punto no es "revisar el código". Es revisar **las dimensiones que ninguna feature revisa por
su cuenta**, porque cada feature se mira a sí misma y nadie mira el conjunto.

---

## Alcance: incremental, no total

Auditar el delta, no el repo entero:

```bash
git log v<ultima-auditada>..HEAD --oneline           # los commits
git diff v<ultima-auditada>..HEAD --stat             # los archivos tocados
```

En un proyecto anterior un delta típico fueron 68 commits / 107 archivos. Eso es auditable en una
sesión. El repo entero no.

**Antes de empezar, medir la salud base**: tests verdes, build limpia, type-check sin errores,
auditoría de dependencias. Si la base ya está roja, eso es el primer hallazgo.

---

## Las dimensiones

Ir una por una. Saltearse una es cómo se acumula la deuda invisible.

| Dimensión | Qué se busca |
|---|---|
| **Seguridad** | Reglas de autorización en el backend (no solo en la UI). Cada endpoint: ¿quién puede llamarlo? Rate limiting en lo público. Secretos: ninguno hardcodeado ni trackeado. Paths de subida generados server-side, nunca con input del usuario. Whitelist de tipos de archivo |
| **Data-safety** | Toda escritura crítica lee del servidor, no del cache del cliente. Idempotencia en lo que se reintenta. Migraciones aditivas. Borrados: ¿cascadean bien? |
| **Correctitud** | Convenciones que se rompieron sin querer. El patrón: una función usa el helper *parecido* al correcto (`esGasto` en vez de `esGastoPuro`) y nadie lo nota porque compila |
| **Costo / performance** | Consultas por pantalla o por request, N+1, lecturas por sesión, tamaño de payloads, imágenes sin comprimir, índices faltantes |
| **Deps** | Auditoría de vulnerabilidades **y** revisar los `overrides`/pins propios: en un proyecto anterior los overrides pineaban versiones vulnerables, o sea que el fix manual era el agujero |
| **Lint** | Que el linter dé verde de verdad. Si hay 12 errores conocidos que se ignoran, el error 13 —el real— no se ve |
| **i18n** | Paridad exacta de claves entre idiomas. Contar y comparar |
| **Docs** | Comentarios que describen algo que ya no es cierto. Pasa siempre con los que explican "esta lectura la comparten X e Y" y después se le colgaron tres cosas más |
| **UX/a11y** | Warnings de consola en cada carga, contraste, targets táctiles |

**Sumar las dimensiones de los módulos opcionales que estén instalados** (offline, captura en
campo). Están en `modulos-opcionales/<mod>/auditoria.md`.

Y sumar las propias del dominio: si el proyecto tiene una regla dura que, rota, invalida el
producto, esa es una dimensión.

---

## Salida

1. **Los fixes**, en una rama, con test que fije cada uno donde aplique.
2. **Un release** que los cierre (patch, salvo que algo sea breaking).
3. **Una memoria** `auditoria-360-vX.Y.Z` con: hallazgos y su resolución, **lo revisado que salió
   sano** (igual de valioso: la próxima auditoría no lo re-revisa desde cero) y lo que se decidió
   no tocar, con el motivo.
4. **`.claude/LAST_AUDIT`** actualizado a la versión auditada.

El punto 3 es el que más rinde a largo plazo. Una auditoría que solo lista problemas obliga a
re-verificar todo la próxima vez.

---

## Regla de método que salió de una auditoría real

> **Al tocar layout, capturar antes y después. No confiar en el ojo.**

Un fix de un warning de aspect-ratio "obviamente inocuo" bajó el formulario de login 125px. Se
detectó por captura comparada, no mirando. Si el cambio toca tamaños, posición o flujo visual,
hay screenshot antes y después o no está verificado.
