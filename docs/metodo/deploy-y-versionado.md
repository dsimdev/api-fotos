# Deploy y versionado

El corazón del método. Nada de esto es burocracia: cada paso existe porque su ausencia costó
algo concreto en un proyecto real.

---

## El checklist

Antes de todo `git push origin main`:

```
1. build pasa sin errores + tests verdes
2. Bump de versión (semver) en el archivo de versión del proyecto
3. CHANGELOG.md        — técnico, inglés
4. CHANGELOG_USER.md   — usuario, español (solo si el cambio se ve)
5. README.md           — "Current Version" y cualquier dato que cambió
6. git tag vX.Y.Z && git push origin vX.Y.Z
7. Confirmación explícita del usuario PARA ESTE PUSH
```

### Por qué cada paso

**1. Build + tests antes de todo.** Push a `main` = producción automática. No hay staging, no hay
"lo veo en el PR". Si la build rompe, rompe en vivo. El orden importa: build primero, porque es
lo único que te dice si el deploy va a existir.

**2. Bump.** La versión es lo que te permite hacer rollback (`git reset --hard vX.Y.Z`) y lo que
alimenta el contador de auditorías. Sin bump no hay tag, sin tag no hay vuelta atrás rápida.

Semver aplicado sin filosofía:
- **patch** — fix, tweak, cambio interno, ajuste visual chico
- **minor** — feature nueva, sección nueva, cambio de comportamiento visible
- **major** — rediseño o breaking change

**3–4. Los dos changelogs.** No es duplicación, son dos lectores distintos:

| | `CHANGELOG.md` | `CHANGELOG_USER.md` |
|---|---|---|
| Lector | vos dentro de 6 meses, y Claude en la próxima auditoría | el usuario final |
| Idioma | inglés | español |
| Contenido | qué archivo, qué función, por qué, qué test lo fija | qué cambió para él y por qué le conviene |
| Si el cambio es 100% interno | va igual, con detalle | "arreglos menores" y listo |

El técnico es el que hace posible la auditoría incremental: sin él, revisar el delta de 10
versiones es arqueología. Escribí **el porqué**, no solo el qué — "cambié X a Y" no sirve dentro
de seis meses; "usaba X, que incluía las compras de divisa y disparaba falsos positivos" sí.

**5. README.** Es lo primero que lee cualquiera que llega al repo. Un README que miente sobre la
versión o el stack es peor que no tenerlo.

**6. Tag.** El rollback y el `git log vX..HEAD` de la auditoría dependen de que los tags existan.

**7. La confirmación.** La regla es literal: **un "sí" anterior no autoriza el próximo push.**
Cada deploy pide su propia confirmación. Es la última barrera antes de producción y la única
que no se puede automatizar.

---

## Varios repos (frontend + backend, o servicios separados)

Cada repo tiene su propio versionado y su propio checklist. La regla de orden:

> **Si un cambio toca los dos, el backend se deploya primero.**

Porque durante la ventana entre un deploy y el otro conviven *cliente viejo + backend nuevo*.
Eso solo es seguro si la API nueva es retrocompatible. La API **nunca** rompe al cliente viejo:
campos nuevos opcionales, endpoints nuevos en vez de cambiar la firma de los existentes, y el
borrado de un campo va en un release posterior, cuando ya nadie lo pide.

Esto se agrava si el cliente puede tener **escrituras encoladas de hace días** (app offline,
integraciones batch, webhooks con reintento). El backend tiene que aceptar el formato anterior
por al menos un release completo. Es la diferencia entre un deploy y perder datos.

---

## Migraciones de base

Nunca a mano, nunca desde una consola. Herramienta de migración versionada (Flyway, Liquibase,
Alembic, Prisma Migrate…), en el repo, aplicada por el pipeline. Regla de oro para no tener
downtime:

- Una migración que **agrega** (columna nullable, tabla, índice) es segura y va sola.
- Una migración que **quita o renombra** va en dos releases: primero se deja de usar, después se
  borra. Nunca las dos cosas en el mismo deploy.

---

## Rollback

1. `git reset --hard vX.Y.Z` + push forzado, **o** redeploy de la build anterior desde la consola.
2. Si hubo migración de base en el medio, el rollback de código **no revierte la base**. Por eso
   la regla de arriba: si las migraciones son solo aditivas, el código viejo sigue funcionando
   contra el esquema nuevo y el rollback es seguro. Si no lo son, no hay rollback rápido.
