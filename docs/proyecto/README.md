# docs/proyecto/

Todo lo específico del proyecto. El kit (método, hooks, memorias semilla) vive en `docs/metodo/` y no se mezcla con esto.

**Por dónde empezar:** `modelo-local.md` (cómo funciona la herramienta, cómo se usa y qué límites tiene) y el
`README.md` de la raíz (cómo instalarla).

| Archivo | Qué es | Estado |
|---|---|---|
| `modelo-local.md` | La herramienta local de arrastrar y soltar: el análisis que llevó a la decisión (secciones 1 a 6) y **lo que se construyó, cómo decide y los límites conocidos (sección 7)**. | Vigente |
| `mediciones.md` | Lo medido, con fecha: de dónde salen las fotos, cobertura de las siete tiendas, calidad, y las dos revisiones independientes. La fase A5 reemplaza las cifras de calidad de la A4. | Vigente (registro) |
| `origen/` | Lo investigado antes de empezar (el brief, la prueba con 10 productos de dyssa y su vara de medición). | Registro |
| `contrato-api.md` | Contrato de la API del modelo alojado. | **En suspenso** |
| `cache.md` | Diseño de la caché del modelo alojado. El principio sí rige y está implementado en la herramienta. | **En suspenso** (salvo el principio) |

`fotos-app` (el servicio y el panel que corresponden a los dos últimos) se conserva en el repo, frenado y sin tocar, por si
hay que escalar a un servicio más adelante.

Dos reglas que hacen que esto sirva:

1. **Las contradicciones se escriben, no se resuelven por asunción.** Una cifra que resultó mal se marca como inválida
   donde estaba (como las de la fase A4 de `mediciones.md`), no se pisa en silencio.
2. **Una decisión cerrada se anota con su fecha y su motivo** en el documento donde vive y en `MEMORY.md`, para que no se
   vuelva a discutir.
