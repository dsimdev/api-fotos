# docs/proyecto/ — esqueleto

Acá vive **todo lo específico del proyecto**. El kit (método, hooks, memorias semilla) se
mantiene libre de dominio; esta carpeta es la contraparte y es la que cambia siempre.

Archivos sugeridos, en el orden en que conviene escribirlos:

| Archivo | Qué contiene | Cuándo se escribe |
|---|---|---|
| `analisis-requerimientos.md` | Lo pedido, con las **contradicciones y preguntas para negocio** marcadas explícitamente. Prioridades declaradas vs. prioridades reales | antes de estimar nada |
| `arquitectura.md` | Piezas, cómo se hablan, y **la decisión más cara** con su justificación y sus alternativas descartadas | antes de la primera línea de código |
| `modelo-de-datos.md` | Entidades, relaciones, y los términos del negocio tal cual se llaman | con arquitectura |
| `plan-de-fases.md` | Qué se construye y en qué orden. Cuál es el MVP real que sale de las prioridades | después de cerrar las preguntas de negocio |
| `decisiones-abiertas.md` | Lo que falta decidir, **cada una con una recomendación**, no solo la pregunta | vivo, se vacía a medida que se cierran |

Dos reglas que hacen que esto sirva:

1. **Las contradicciones de los requerimientos se escriben, no se resuelven por asunción.** Si
   una funcionalidad es prioridad alta pero todo lo que la alimenta es prioridad baja, eso es un
   hallazgo para negocio, no un problema técnico.
2. **Una decisión cerrada se mueve a `MEMORY.md` → "Temas CERRADOS"** con la fecha y el motivo.
   Si no, se vuelve a discutir dentro de tres sesiones.
