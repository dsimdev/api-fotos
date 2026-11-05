# Contrato de la API — BORRADOR

> **Estado: borrador del 2026-11-05.** Lo que está decidido es el proceso (sección 1). Todo lo demás es
> una propuesta a revisar antes de escribir código; lo que falta decidir está en la sección 7.
> Las convenciones (tenant en la ruta, `x-api-key`, errores con código de dominio, fechas ISO 8601 con
> zona, lotes obligatorios) salen de `api-bonificaciones`, que es lo que el consumidor ya conoce.

## 1. El proceso (decidido el 2026-11-05)

1. El backend de la tienda (por ahora `senderolaser`) pide la imagen de un EAN.
2. El servicio consulta las tiendas VTEX (o su caché), baja las imágenes, quita el banner cuando se puede
   y arma la lista de **candidatos**.
3. El servicio elige **una recomendada** y devuelve la recomendada **y** los demás candidatos, cada uno con
   su traza.
4. La tienda **guarda los bytes** de la recomendada en su SQL. Si no le gusta, elige otro candidato y guarda
   ese. No se guarda una URL: las de los comercios mueren.
5. Si ningún candidato es aceptable, el estado es explícito (sin imagen) y no hay recomendada. Nunca se
   devuelve una imagen de relleno.

El caso normal es automático: nadie elige. La posibilidad de cambiarla es una válvula, no un paso obligatorio.

## 2. Endpoints (propuesta)

Todos bajo `/v1/{tenant}/`, con `tenant` = el cliente (hoy `senderolaser`) y el header `x-api-key` atado a
ese tenant.

| Método y ruta | Qué hace |
|---|---|
| `GET /images/{ean}` | El resultado de un EAN: estado, recomendada y candidatos (JSON, sin bytes) |
| `GET /images/{ean}/candidates/{candidateId}` | Los **bytes** de un candidato (JPEG o PNG), desde nuestra copia |
| `POST /images/batch` | Lo mismo para una lista de EAN (`{"eans": [...]}`), con un tope de tamaño por pedido a definir |
| `PUT /images/{ean}/selection` | **Opcional, ver 7.1.** Registra qué candidato eligió la tienda |

## 3. Respuesta de `GET /images/{ean}`

```json
{
  "ean": "7790070337016",
  "status": "OK",
  "consultedAt": "2026-11-05T16:44:21-03:00",
  "recommended": { "candidateId": "masonline-0-original", "reason": "CLEAN_ORIGINAL" },
  "selected": null,
  "candidates": [
    {
      "candidateId": "masonline-0-original",
      "stores": ["masonline"],
      "pageUrl": "https://www.masonline.com.ar/...",
      "productName": "Lasagna Matarazzo 250 G",
      "position": 0,
      "width": 1000,
      "height": 1000,
      "contentType": "image/jpeg",
      "bannerStatus": "NONE",
      "imageUrl": "/v1/senderolaser/images/7790070337016/candidates/masonline-0-original"
    },
    {
      "candidateId": "carrefour-0-cropped",
      "stores": ["carrefour"],
      "bannerStatus": "REMOVED",
      "...": "..."
    }
  ]
}
```

- **`status`**: `OK` (hay recomendada) o sin imagen. **Los nombres finales están por definir** (el brief usa
  `OK`, `CON_MARCA`, `SIN_IMAGEN`).
- **`stores` es una lista** porque varias tiendas sirven el mismo archivo (Jumbo, Disco y Vea tienen el mismo
  hash): se agrupan en un solo candidato en vez de repetirlo tres veces.
- **`bannerStatus`**: `NONE` (la imagen original no tenía panel), `REMOVED` (se recortó) o `PRESENT` (tiene
  panel y no se pudo recortar: **nunca se recomienda**; si se lista o no es la pregunta 7.3).
- **`position`**: solo los candidatos de la posición 0 de cada tienda. Las posteriores pueden ser el
  reverso del envase (la segunda imagen de Hesperidina), y no se ofrecen como candidatas.
- **`selected`**: lo que la tienda eligió, si usa la opción 7.1; si no, `null`.
- `candidateId` identifica la variante, no solo la tienda: `<tienda>-<posición>-<original|cropped>`.

## 4. Cómo se elige la recomendada (propuesta, a validar con más muestras)

En este orden, entre los candidatos elegibles (`NONE` o `REMOVED`):

1. **Preferir `NONE` sobre `REMOVED`**: la imagen original sin tocar es más fiel que una recortada.
2. **Preferir mayor resolución**, con un piso (se propone 500 px de lado menor: la imagen de Jamaica Gold Rum
   de 170×170 no debería ganar nunca).
3. **Empate: el orden de las tiendas de la configuración** (primero las que más cubren).

`reason` dice cuál regla decidió: `CLEAN_ORIGINAL`, `BANNER_REMOVED`. Los umbrales y el orden van en
configuración, no en código.

## 5. Errores

Con código de dominio, no solo HTTP, para que el integrador distinga cada caso. Candidatos (a refinar):

| Código | HTTP | Cuándo |
|---|---|---|
| `INVALID_EAN` | 400 | No es un EAN válido (largo, dígitos o dígito verificador) |
| `NOT_AUTHORIZED` | 401 | Falta la clave o es inválida |
| `WRONG_TENANT` | 401 | La clave no es de ese cliente (sin decir de cuál es) |
| `BATCH_TOO_LARGE` | 400 | Más EAN que el tope por lote |
| `SOURCES_UNAVAILABLE` | 503 | Ninguna tienda pudo contestar y no hay copia propia. Reintentable |
| `TOO_MANY_REQUESTS` | 429 | La clave pasó su límite |

Un EAN que ninguna tienda tiene **no es un error**: es `200` con estado sin imagen.

## 6. Lo que ya existe y lo que falta

**Existe:** el conector VTEX con coincidencia exacta de EAN, ritmo por tienda, reintento y cortacircuito
(`fotos-app`, 67 tests), y una pantalla de demostración en el panel que muestra los candidatos de todas las
tiendas lado a lado (`/admin/v1/lookup`, apagada por defecto y sin autenticación).

**Falta:** descarga segura de las imágenes (SSRF, tipo por bytes, pedir JPEG/PNG), detección y recorte del
banner **en Java** (hoy está en los scripts de Node de `spikes/measure-sources`), la elección de la
recomendada, la caché y la base, la API de arriba con su clave y los lotes, el login del panel y las
alertas.

## 7. Preguntas abiertas

1. **¿Quién cambia la imagen, y dónde?** Si la corrige una persona de la tienda o el sistema de la tienda con
   una regla. Mi sugerencia, que no exige nada de su lado: la persona elige en **nuestro panel** (que ya
   muestra los candidatos) y la API devuelve eso como `selected`; el backend de la tienda toma
   `selected` si existe y, si no, `recommended`. Eso necesita el `PUT .../selection` y la base. Quien prefiera
   resolverlo en su propio sistema puede usar solo los candidatos.
2. **¿Se registra qué candidato elige la tienda?** Aunque nadie lo use en pantalla, "cuántas veces se cambió la
   recomendada" es la mejor medida de la calidad de la elección, y sale gratis si hay `PUT`.
3. **¿Se listan los candidatos con banner que no se pudieron recortar** (marcados, nunca recomendados) o se
   descartan? Listarlos le da a la tienda más opciones, pero también la chance de elegir una imagen con
   banner.
4. **Nombres finales de los estados** y tope de EAN por lote.
5. **Derechos de las imágenes y términos de uso de VTEX** (pendiente desde el principio): mandar todos los
   candidatos significa que la tienda maneja más imágenes de terceros. Cada una viaja con su página de
   origen, pero falta acordar quién asume el riesgo de publicarlas.
