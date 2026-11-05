# Testing

Tres capas. La del medio es la que se suele saltear y la que evita los desastres.

---

## Capa 1 — Unit (funciones puras)

La lógica de negocio pura: cálculos, agrupaciones, validaciones, transformaciones. Rápidos, sin
mocks, sin red.

Barata y necesaria. **Pero no alcanza como prueba de que la feature funciona.**

## Capa 2 — Servicio (con las dependencias mockeadas) ← LA IMPORTANTE

> **Si una feature toca datos, hay que testear el SERVICIO que orquesta la operación, no solo
> las funciones puras que usa.**

**La historia que la justifica**: en un proyecto anterior, un rename de categorías se escribió
con 13 tests de lógica pura, todos verdes. Type-check, tests y build pasaban los tres. El bug no
vivía en la lógica: vivía en el servicio, que recibía los datos del cache del cliente. Si el
cache estaba frío llegaba `[]`, la migración se salteaba, pero el rename de la config sí corría.
Resultado: registros históricos colgados de una categoría inexistente, **en producción**, datos
del usuario corrompidos. Textual: *"se rompió la app, esto no puede pasar, no pueden llegar
estos bugs"*.

Se habían verificado las piezas. No el ensamblado.

**Qué tiene que cubrir el test de servicio:**
- qué se escribe y en qué orden
- **el camino donde la entrada llega vacía o parcial** — el que rompió
- el rollback / qué queda si falla a mitad de camino

La pregunta que hay que hacerse antes de dar algo por listo: *"¿qué NO ejecutan mis tres
verificaciones (type-check, tests, build)?"*. La respuesta casi siempre es: el flujo real contra
la base y el estado de carga del cliente.

## Capa 3 — Extremo a extremo

Flujos reales contra el sistema levantado. La forma depende del producto:

- **App con UI** → navegador real (Playwright), en el viewport que realmente usa el producto.
- **API / servicio** → tests de integración contra la app levantada y una base real
  (contenedor efímero), no mocks.

Reglas que se pagaron caro:

- **Datos y usuario de prueba dedicados y aislados.** Nunca la cuenta real ni la base real.
- **Sin paralelismo mientras el estado sea compartido** (`fullyParallel: false`, 1 worker). Con
  un solo usuario de prueba, en paralelo los specs se pisan entre sí.
- **Limpiar SIEMPRE lo que se carga, en un `finally`** — que el cleanup corra aunque la aserción
  falle. Si no, los datos basura se acumulan y en dos semanas los tests fallan por ruido.
- **Después de navegar, esperar algo real visible** antes de interactuar. Sin eso el runner
  puede tocar elementos de la página anterior a medio hidratar: el test "pasa" habiendo tocado lo
  que no era. Es el falso verde más peligroso.
- Contar niveles de `xpath=ancestor::div[N]` leyendo el código fuente **no coincide** con el DOM
  real. Resolverlo empíricamente.
- Borrar capturas y specs de debug antes de commitear.

### Qué NO forzar a la capa 3

Cuando un escenario necesita estado histórico que la UI no puede fabricar (datos de períodos
pasados, series largas), o dispararía efectos reales (mails, push a usuarios de verdad), **el
estándar es quedarse con el unit test de la función pura** y documentar por qué. Forzarlo
ensucia datos históricos y no prueba más.

---

## La regla de oro

Los tests no prueban que el código sea correcto. Prueban que **el caso que rompió una vez no
vuelve a romper**. Todo bug que llega a producción se cierra con un test que lo fija — si no, es
cuestión de tiempo que vuelva.

---

> Si el proyecto tiene cola offline o sync, sumar los tests del módulo
> `modulos-opcionales/offline-first/` (idempotencia, recarga, token vencido, cuota). Son los que
> cubren el riesgo real de ese tipo de app.
