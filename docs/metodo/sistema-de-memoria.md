# Sistema de memoria

Claude Code guarda memoria en archivos, en
`~/.claude/projects/<slug-del-proyecto>/memory/`. Cada archivo = **un hecho**. `MEMORY.md` es el
índice que se carga en contexto al empezar cada sesión.

El sistema funciona. Lo que lo arruina es dejarlo crecer sin podarlo.

---

## Formato

```markdown
---
name: <slug-kebab-case>
description: <una línea — con esto se decide si la memoria es relevante>
metadata:
  type: user | feedback | project | reference
---

<el hecho. Para feedback/project, seguir con **Why:** y **How to apply:**>
<enlazar memorias relacionadas con [[su-name]]>
```

**Los tipos:**

- `user` — quién es el usuario (rol, expertise, preferencias)
- `feedback` — cómo quiere que trabajes. Correcciones y también enfoques confirmados. **Siempre
  con el porqué**: una regla sin su motivo se aplica mal en cuanto el contexto cambia
- `project` — trabajo en curso, objetivos, restricciones que NO se deducen del código ni del
  git log. Fechas relativas convertidas a absolutas ("el mes que viene" no significa nada
  releído en marzo)
- `reference` — punteros a recursos externos (URLs, dashboards, tickets)

**En `MEMORY.md`** va una línea por memoria: `- [Título](archivo.md) — gancho`. Nunca contenido.

---

## La regla que hace que todo esto sirva

> **La memoria es una foto del pasado, no estado vivo.**

Antes de afirmar que algo está hecho, pendiente o roto: **abrir el código**. grep, read, git log.
Nunca responder desde la memoria ni desde un changelog viejo.

En un proyecto anterior esto se aprendió a los golpes. El backlog se desfasó 7 versiones y se
ofrecieron como "features nuevas" cosas que ya estaban en producción hacía meses. Textual del
usuario: *"la guía ya está, lo vimos mil veces, mil veces actualizaste memoria y seguís diciendo
de la guía"*.

Las memorias de estado (backlog, auditorías, planes) se leen como **hipótesis a confirmar**, no
como verdad.

---

## Higiene: las tres cosas que la pudren

**1. El pendiente zombi.** Cuando el usuario corrige un ítem, hay que borrarlo de **TODAS** las
memorias, no solo de la que estaba abierta. Un pendiente muerto sobrevive en una auditoría vieja
que nadie limpió y reaparece tres sesiones después como si fuera nuevo. El procedimiento es
`grep -rn <tema>` sobre la carpeta entera y limpiar cada ocurrencia.

**2. El duplicado.** Antes de crear un archivo, buscar si ya existe uno que cubre el tema.
Actualizar el existente, no crear el hermano.

**3. Lo que no debería estar.** No guardar lo que el repo ya registra: estructura del código,
fixes pasados, historia de git, contenido de `CLAUDE.md`. Tampoco lo que solo importa en esta
conversación. Si el usuario pide recordar algo de eso, preguntar qué fue lo **no obvio** y
guardar eso.

---

## Sección "temas CERRADOS"

Vale la pena mantener en `MEMORY.md` una sección explícita de **cosas que ya se decidieron que
NO se hacen**. Sin ella, cada tanto se vuelve a ofrecer lo mismo que ya se descartó, y para el
usuario es la señal más clara de que no lo estás escuchando.

Formato: qué era, cuándo se cerró, y si fue por "ya está hecho" o por "se decidió que no".

---

## Cuándo escribir memoria

- El usuario te corrige → memoria `feedback` **con el porqué**
- Se toma una decisión de arquitectura o de producto que no queda escrita en el código →
  `project`
- Se cierra una auditoría → `project` con los hallazgos y su resolución
- Se descubre un gotcha caro (algo que costó una corrida, un deploy o un bug en prod) → `feedback`

**Cuándo NO:** después de cada feature. El changelog ya es eso. La memoria es para lo que el
código no dice.

---

## Cuando una lección trasciende al proyecto

Si la lección aplicaría igual en cualquier proyecto, además de la memoria local **volvé a
subirla al kit** (`metodo/lecciones.md` y, si merece ser regla permanente, una memoria en
`memoria-semilla/`). Es lo que hace que el kit siguiente arranque más arriba que este.

---

## Dónde vive la carpeta, y por qué se pierde al mudar el proyecto

La carpeta de memoria **no está en el repo**: está en tu perfil, y su nombre sale de la **ruta**
del proyecto, con los separadores reemplazados por `-` y la letra de unidad en minúscula:

```
C:\Dev\mi-proyecto   ->   ~/.claude/projects/c--Dev-mi-proyecto/memory/
```

De ahí se siguen tres cosas que cuestan caro si se descubren tarde:

**1. La carpeta no existe hasta que abrís una sesión en el proyecto.** Por eso la instalación de
las memorias semilla va después de abrir la primera sesión, no antes.

**2. Mover o renombrar la carpeta del proyecto huerfana toda su memoria.** El slug cambia, Claude
crea una carpeta nueva y vacía, y el proyecto arranca en blanco aunque la memoria siga ahí al
lado. No hay aviso: simplemente no se carga nada. En un caso real, mudar los proyectos del
Escritorio a `C:\Dev` y renombrarlos en el camino dejó **95 archivos de memoria huérfanos
repartidos en 5 carpetas**, y los proyectos siguieron trabajando como si nunca hubieran tenido
historia.

**3. La caja con la que abrís la sesión queda fijada en el nombre.** Si la primera sesión se abrió
desde `C:\dev\x`, la carpeta se llama `c--dev-x` para siempre. En Windows esto **no** duplica
nada —el filesystem es case-insensitive, no pueden coexistir las dos variantes— pero sí explica
por qué el nombre en disco a veces no coincide con la caja que usás hoy.

### El procedimiento para mudar un proyecto

En este orden:

1. **Cerrar las sesiones de Claude Code** del proyecto.
2. **Anotar la ruta actual exacta** antes de tocar nada. El slug sale de ahí y después no hay
   forma de deducirlo.
3. **Decidir el nombre final una sola vez.** Cada rename posterior vuelve a huerfanar.
4. Mover o renombrar la carpeta.
5. Abrir una sesión en la ruta nueva (crea la carpeta de memoria vacía).
6. **Migrar**: `scripts/migrar-memoria.ps1 -Proyecto <ruta nueva>`. Detecta la memoria vieja por
   coincidencia de nombre, corre en dry-run por defecto y nunca borra el origen.
7. Abrir el `MEMORY.md` del destino y **unificar el índice**. Las líneas migradas entran en una
   sección marcada, y se leen como hipótesis a confirmar: pueden nombrar archivos o decisiones
   que ya cambiaron.

El hook de `SessionStart` que trae el kit detecta este caso solo y avisa al abrir la sesión, así
que si te olvidás del paso 6 te lo va a recordar.
