# Prueba del 2026-11-05: scripts y veredictos

Lo que se usó para la prueba con dyssa descrita en la sección 4 de
[../gateway-de-imagenes-por-codigo-de-barras.md](../gateway-de-imagenes-por-codigo-de-barras.md).
Son scripts de prueba, no código de producción: sirven de referencia y para repetir la medición.

## El archivo que más sirve: `veredictos-dyssa.json`

Los 10 productos de dyssa con los 3 candidatos de cada uno y el veredicto de la revisión visual
(`limpia`, `con_marca`, `equivocada`, `sin_ver`). Es la **referencia para medir** cualquier método
nuevo contra la misma muestra. Totales: 30 candidatos, 10 limpias, 13 con marca, 6 equivocadas, 1 sin ver.

Ojo: los veredictos los puso una persona (Claude) mirando cada imagen el 2026-11-05. No es una medición
automática, y la muestra es de marcas grandes. Las URLs de las imágenes no se guardaron; solo la página
del comercio.

## Los scripts

Requieren Node 18 o más (usan `fetch` y `AbortSignal.timeout`). Ninguno trae secretos adentro: la key
y las credenciales se pasan por **ruta de archivo**, y ninguno las imprime.

| Script | Qué hace | Depende de |
| --- | --- | --- |
| `dyssa-articulos.js` | Pide un token de Keycloak y lee **una página** de `get-articulos` v4 de dyssa (solo lectura); elige artículos con EAN-13 válido que empiece con 779 y los guarda en un JSON | Credenciales de la colección Postman de dyssa. **Hay que pedir permiso de nuevo** en cada sesión |
| `dyssa-seleccion.json` | Salida de lo anterior: los 10 artículos usados | — |
| `lote-serpapi.js` | Por artículo: busca "EAN + nombre normalizado", descarta redes sociales y páginas repetidas, baja los 3 primeros candidatos y escribe `resumen.json` | SerpApi (descartado, queda de plan B). Lo reutilizable es la normalización del nombre y los filtros |
| `serpapi-prueba.js` | Una o varias consultas sueltas a SerpApi, imprime los 12 primeros resultados | SerpApi |
| `bajar-imagenes.js` | Baja la imagen original de posiciones puntuales de un resultado guardado | — |
| `off-lote.js` | Consulta Open Food Facts por EAN (con pausa de 1,5 s) | — |
| `off-imagenes.js` | Baja la imagen frontal de Open Food Facts | — |

## Defectos conocidos

- El descargador acepta solo respuestas con `Content-Type: image/*`. Un servidor que responde
  `application/octet-stream` aunque sea una imagen se descarta (pasó con 1 de 30). Hay que detectar el
  tipo por los primeros bytes.
- El descargador no valida el destino de la URL: **en un servicio real hay que bloquear IPs privadas y
  esquemas raros** (la URL sale de un resultado de búsqueda, o sea de un tercero).
- Las imágenes bajadas son de terceros: no se guardan en esta carpeta ni se versionan.
