# api-fotos

Herramienta local que convierte la planilla de un cliente (columnas **SKU** y **EAN**) en una carpeta con **una foto
por producto, nombrada con el SKU**, lista para que la tienda la suba. Las fotos salen de los catálogos públicos de
supermercados argentinos (VTEX), consultados por código de barras.

**Versión actual:** 0.2.0

## Cómo se usa

1. Una vez, instalarla: `scripts/herramienta/instalar.ps1` (necesita Java 21). Crea el acceso directo **Fotos por SKU**
   en el escritorio. Se puede correr de nuevo para actualizar.
2. Cada vez que un cliente manda su lista: **arrastrar la planilla (.xlsx o .csv) sobre el acceso directo.**
3. Al terminar se abre la carpeta `<planilla> - fotos`, junto a la planilla:
   - `fotos\` una foto por SKU (`<SKU>.jpg` o `<SKU>.png`),
   - `fotos.zip` las mismas fotos, listas para pasarle a la tienda,
   - `reporte.csv` una línea por producto: estado, tienda de origen y avisos,
   - `galeria.html` lo mismo para mirar. **Mirarla antes de entregar**: la herramienta no detecta marcas ajenas.

Cambiar una foto es reemplazar el archivo en `fotos\` (y volver a armar el zip). Si el reporte dice `ERROR`, volver a
correr la misma planilla: lo ya bajado queda guardado y solo se piden esos. Por consola:
`fotos-tool <planilla> [--refresh] [--cache <carpeta>] [--no-abrir]`.

## Qué hay en el repositorio

| Módulo | Qué es |
|---|---|
| `fotos-core` | Núcleo sin framework: lector de planillas, recorte del panel de plantilla, elección de la foto, caché en carpeta, escritura de la entrega. |
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

- `docs/proyecto/modelo-local.md`: cómo funciona, qué recuerda la caché y los **límites conocidos**.
- `docs/proyecto/mediciones.md`: lo medido (cobertura de las tiendas, calidad de las fotos, revisiones independientes).
- `CLAUDE.md`: reglas del proyecto (seguridad, datos, testing, flujo de trabajo).
- `CHANGELOG.md`: cambios por versión.
