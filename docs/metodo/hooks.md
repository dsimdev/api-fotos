# Los hooks

Las directivas de `CLAUDE.md` se pueden ignorar. Los hooks no. Esa es toda la razón por la que
existen: el método tiene cuatro o cinco reglas que, si se rompen, cuestan un deploy roto o datos
perdidos, y esas no pueden depender de que el modelo se acuerde.

> **Si `CLAUDE.md` y un hook se contradicen, gana el hook.** Es a propósito.

Viven en `.claude/settings.json`. **No se cargan hasta reiniciar la sesión** (o abrir `/hooks`):
es el gotcha que más tiempo cuesta de toda la instalación.

---

## Qué hay

| Hook | Evento | Qué hace |
|---|---|---|
| Chequeo de entorno | `SessionStart` | Avisa si falta git, si estás en detached, si `CLAUDE.md` tiene placeholders sin rellenar, o si la memoria del proyecto quedó huérfana |
| Gate de push | `PreToolUse` Bash | **Pide aprobación** ante cualquier push que toque `main` o un tag `vX.Y.Z` |
| Commit en main | `PreToolUse` Bash | **Bloquea** |
| Mención a Claude | `PreToolUse` Bash | **Bloquea** el commit si trae `Generated with Claude` o el trailer `Co-Authored-By: Claude` |
| PRs | `PreToolUse` Bash | Avisa que el proyecto no usa PRs |
| Auditoría vencida | `PreToolUse` Bash | Avisa al pushear si pasaron 10 minors desde `.claude/LAST_AUDIT` |
| Edición en main | `PreToolUse` Edit/Write | **Bloquea**, salvo changelogs, README, archivo de versión/lock y todo `.claude/` |

Tres niveles de fuerza, y conviene tenerlos claros:

- **`deny`** — no hay forma de seguir. Para lo que nunca está bien (commitear en main).
- **`ask`** — frena y te pregunta a vos. Para lo que está bien **solo si lo decidís vos ahora**:
  el push a producción.
- **`additionalContext`** — un recordatorio en el contexto del modelo. Para lo que necesita
  criterio, no barrera (la auditoría vencida).

El gate de push es `ask` y no un simple recordatorio por un motivo concreto: un recordatorio se
puede leer y seguir de largo. Como `ask`, el push no ocurre sin que vos lo apruebes, y eso es
exactamente lo que dice la regla del checklist — *un "sí" anterior no autoriza el próximo push*.
Para que funcione hace falta además la entrada `permissions.ask` con `git push`, porque un
`allow` global de `git push` en tu settings de usuario le saltearía el prompt.

---

## Cómo decide el gate de push

Lo ingenuo es matchear `git push origin main`. Eso se le escapa `git push -u origin main`,
`git push --force origin main` y `git push` a secas parado en main. El hook del kit, en cambio:

1. Ignora el comando si es un `git commit` (un mensaje de commit puede contener la palabra push).
2. Busca el segmento `git push …` incluso dentro de un comando compuesto (`git status && git push …`).
3. Saca los flags y se queda con los refs.
4. Si algún ref es `main` o matchea `vX.Y.Z` → **ask**.
5. Si hay un ref explícito y no es ninguno de esos (`git push origin feat/x`) → deja pasar,
   **aunque estés parado en main**. Empujar una rama de trabajo no toca producción.
6. Si no hay ref explícito (`git push`, `git push origin`) → mira la rama actual: si es `main`,
   **ask**.

---

## Dónde busca la versión

Para el aviso de auditoría, en este orden:

1. `.claude/VERSION` — el override explícito
2. `package.json`
3. Por regex sobre `pom.xml`, `pyproject.toml`, `Cargo.toml`, `build.gradle[.kts]`, `composer.json`

El orden importa: `.claude/VERSION` va primero para que puedas forzarlo cuando la detección
automática no sirve. El caso típico es un `pom.xml` con `<parent>`, donde la primera versión del
archivo es la del padre y no la del proyecto. Si tu stack no está en la lista, creá
`.claude/VERSION` y bumpealo en el paso 2 del checklist.

Si el proyecto usa un archivo de build **y** `.claude/VERSION`, los dos tienen que bumpear juntos
en el mismo commit. Es una fuente de verdad duplicada y nada la sincroniza sola.

---

## Los límites: cuándo NO protegen

Vale saberlos, porque el silencio de un hook se parece mucho a "está todo bien":

- **Sin repo git, ningún hook bloquea.** Todos salen por `exit 0`. El chequeo de `SessionStart`
  existe justamente para que no te enteres tarde.
- **En detached HEAD**, los hooks que miran la rama no bloquean: `git branch --show-current`
  devuelve vacío.
- **Una rama que no sea `main` no está protegida**, por diseño. Si tu producción sale de otra rama
  (`produccion`, `release/*`), hay que cambiar `'main'` en los hooks por el nombre real.
- **La carpeta contenedora de un multi-repo no tiene rama.** Los hooks puestos en la raíz de una
  carpeta que no es un repo no hacen nada; van en cada repo real.
- **Un `allow` global** en tu `~/.claude/settings.json` (del tipo `Bash(git push *)`) le saca el
  prompt a todo lo que no sea `ask` o `deny`. Las reglas de proyecto son el lugar para los
  permisos de un proyecto; el settings global es para lo que vale en todos.

---

## Adaptarlos

- **Otro nombre de rama de producción**: reemplazar `'main'` en los cuatro hooks que lo nombran.
- **El equipo usa PRs**: borrar el hook de PRs y la regla de la sección 2 de `CLAUDE.md`.
- **Otra cadencia de auditoría**: el `-lt 10` del hook de auditoría, y `metodo/auditoria-360.md`.
- **Otro manifiesto de versión**: agregarlo al array `$allow` del hook de edición en main, para
  poder bumpear la versión estando en main.

Después de tocar cualquier cosa acá: **reiniciar la sesión** y verificar con
`scripts/verificar.ps1`. Un hook con un error de sintaxis de PowerShell falla en silencio —
el `catch {}` del final se traga todo— y un hook que no bloquea se ve igual que uno que te deja
pasar porque corresponde.
