# api-fotos

Herramienta local que convierte la planilla de un cliente (columnas **SKU** y **EAN**) en una carpeta con **una foto
por producto, nombrada con el SKU**, lista para que la tienda la suba. Las fotos salen de los catálogos públicos de
supermercados argentinos (VTEX), consultados por código de barras.

**Versión actual:** 0.4.0

## Cómo se usa

1. Una vez, instalarla: `scripts/herramienta/instalar.ps1` (necesita Java 21). La deja en `C:\Dev\app-fotos`; no crea
   accesos directos, esos los arma cada persona. Se puede correr de nuevo para actualizar: reemplaza solo el programa y
   no toca las entregas.
2. Cada vez que un cliente manda su lista: **arrastrar la planilla (.xlsx o .csv) sobre `Arrastrar la planilla aca.bat`**
   (o sobre un acceso directo a ese archivo).
3. Al terminar se abre la carpeta `Fotos <fecha>` (por ejemplo `Fotos 2026-11-06`), **dentro de la carpeta del programa**
   y no donde estaba la planilla. Si se corre dos veces el mismo día, la segunda es `Fotos 2026-11-06 (2)`:
   - `fotos\` una foto por SKU (`<SKU>.jpg` o `<SKU>.png`),
   - `fotos.zip` las mismas fotos, listas para pasarle a la tienda,
   - `reporte.csv` una línea por **fila de la planilla**: estado, tienda de origen, avisos y, si la fila tenía algo mal, qué,
   - `galeria.html` lo mismo para mirar. **Mirarla antes de entregar**: la herramienta no detecta marcas ajenas.

**Lo que está mal se ignora.** Una fila sin código de barras, con un EAN inválido, sin SKU o con un SKU que no sirve de
nombre de archivo no se consulta ni se muestra una por una: la pantalla las cuenta al final ("239 sin código de barras") y
cada una queda explicada en `reporte.csv`, que es donde se arreglan en el sistema de origen.

Cambiar una foto es reemplazar el archivo en `fotos\` (y volver a armar el zip). Si el reporte dice `ERROR`, volver a
correr la misma planilla: lo ya bajado queda guardado y solo se piden esos. Por consola:
`fotos-tool <planilla> [--refresh] [--cache <carpeta>] [--salida <carpeta>] [--paralelo <1-8>] [--no-abrir]` y `fotos-tool --banco`.

**El banco de fotos.** Cada foto que se baja queda guardada **por EAN** en `banco-fotos`, dentro de la carpeta del programa (el instalador
no la toca), junto con la foto original sin recortar y de dónde salió: la próxima planilla que pida ese producto la saca de ahí sin
consultar a nadie, y el SKU nunca se guarda en el banco. **Para sumar una foto propia**, dejá `<EAN>.jpg` o `<EAN>.png` en esa carpeta:
se reconoce en la próxima corrida o con `fotos-tool --banco`, y `--refresh` no la reemplaza. `indice.csv` lista todo el banco; es lo
único que se versiona en git (las fotos no).

## Qué hay en el repositorio

| Módulo | Qué es |
|---|---|
| `fotos-core` | Núcleo sin framework: lector de planillas, recorte del panel de plantilla, elección de la foto, banco de fotos en carpeta, escritura de la entrega. |
| `fotos-stores` | Conector de las tiendas VTEX (ritmo, reintento, cortacircuito) y descarga segura de imágenes. |
| `fotos-tool` | La herramienta de arrastrar y soltar. |
| `fotos-app` | Servicio y panel de administración del modelo alojado. **Frenado, no se usa**; se conserva por si hay que escalar. |

## Desarrollo

```
./gradlew build                     # compila y corre todos los tests (sin red)
./gradlew :fotos-tool:installDist   # arma el programa en fotos-tool/build/install
./gradlew test -PincludeLiveTests   # los pocos tests que sí consultan tiendas reales
```

## Documentación

- `docs/proyecto/modelo-local.md`: cómo funciona, qué recuerda el banco de fotos y los **límites conocidos**.
- `docs/proyecto/mediciones.md`: lo medido (cobertura de las tiendas, calidad de las fotos, revisiones independientes).
- `CLAUDE.md`: reglas del proyecto (seguridad, datos, testing, flujo de trabajo).
- `CHANGELOG.md`: cambios por versión.
