# Lecciones

Reglas que salieron de errores reales a lo largo de ~2 años y 109 versiones de un proyecto en
producción. Están generalizadas: sirven acá aunque el stack sea otro. Las que tienen historia la
incluyen, porque **una regla sin su porqué se aplica mal en cuanto el contexto cambia**.

---

## Datos

**1. Las escrituras leen del servidor, nunca del snapshot del cliente.**
Una operación que modifica datos (migración, batch, borrado, recategorización) tiene que consultar
al servidor lo que va a tocar. El cache del cliente puede estar vacío, viejo o parcial: para
renderizar sirve, para decidir qué se escribe no.
*El olor exacto*: una función de servicio que recibe `items: Item[]` por parámetro y después
escribe. Costó datos corrompidos en producción.

**2. Todo lo que se reintenta necesita idempotencia.**
UUID generado por el cliente, el servidor deduplica. Sin esto, una cola de sync con reintentos
—o un webhook, o un job con retry— duplica registros.

**3. Marcar "ya procesado" solo si el envío tuvo éxito.**
Patrón que se rompió con las notificaciones: se marcaba el dedup antes de confirmar el envío, así
que un fallo de red significaba que el aviso no salía nunca. La marca va **después** del `ok`, y
con reintento.

**4. Chequear el caso "datos todavía no cargados".**
Antes de dar por lista cualquier feature que lee de una fuente async: ¿qué muestra o qué escribe
si el array llega `[]` porque todavía no cargó? No mostrar un 0 falso ni escribir en silencio.

---

## Código

**5. El helper parecido no es el helper correcto.**
Bug real: una función usaba `esGasto` (que incluye compras de divisa) donde correspondía
`esGastoPuro`. Compilaba, pasaba los tests, y marcaba una anomalía falsa cada vez que el usuario
compraba dólares. Cuando existen dos helpers con nombres parecidos, la convención va documentada
**en el helper**, y quien lo usa la lee.

**6. Rutas, archivos, componentes y variables en inglés.**
Solo los strings de UI van por i18n. Los términos del negocio que no tienen traducción buena se
respetan tal cual. El legacy no se renombra por las tuyas.

**7. Los pins y `overrides` de dependencias envejecen y se vuelven el agujero.**
Un pin puesto para arreglar una vulnerabilidad queda clavado en una versión que después resulta
vulnerable. Revisarlos en cada auditoría, no solo correr el audit del gestor de paquetes.

**8. Los scripts one-off también son código.**
Si `scripts/` está fuera del lint o del repo, un script que genera un asset que sí se despliega
existe solo en una máquina. Decidirlo a conciencia, no por omisión.

**9. Verificar el artefacto final compilado, no el output intermedio.**
Un build multi-módulo puede producir un artefacto vacío o mal armado con el mismo nombre que el
real, en otra carpeta (un plugin aplicado de más en el proyecto raíz empaquetó un jar de 261
bytes llamado igual que el de verdad — costó 4 iteraciones de un deploy roto hasta que alguien
abrió el zip). Cuando lo que importa es el binario que se va a distribuir (jar, build de
frontend, imagen de contenedor), la verificación es abrir **ese** archivo puntual y confirmar su
contenido — no confiar en que el paso intermedio (`npm run build`, `compileJava`) haya salido
bien porque no tiró error.

---

## UI

**10. Menos texto: diferenciar con color y forma, no agregando labels.**
Íconos, color y forma como protagonistas; los números como heroes visuales. Cada label que
agregás es una decisión de diseño que no tomaste.

**11. Card centrada por defecto; bottom sheet solo para listas deliberadamente largas.**
"El contenido es de largo variable" NO justifica un sheet: la card scrollea igual.

**12. Al tocar layout, capturar antes y después.**
Un fix de un warning "obviamente inocuo" bajó un formulario 125px. Se detectó por captura
comparada, no a ojo. Si el cambio toca tamaño, posición o flujo, hay screenshot o no está
verificado.

**13. Si la info cabe inline, va inline.**
No abrir un modal para mostrar poca información.

---

## Trabajo

**14. Verificar contra el código, nunca contra la memoria.**
Antes de afirmar que algo está hecho, pendiente o roto: grep, read, git log. La memoria es una
foto del pasado y se desfasa rápido. Cada vez que se confió en la memoria en vez de leer el
código, hubo que corregir.

**15. Una conclusión de "no se puede" basada en un tercero es condicional, no permanente.**
Si "esto no es viable" depende de que un sistema externo no expone algo hoy, esa condición tiene
que quedar escrita explícitamente (y de qué depende) — no como verdad de proyecto. Un caso real:
se documentó "el proveedor nunca expone lectura" como hecho cerrado; meses después el proveedor
agregó soporte, y cinco lugares distintos (código, docs, memoria) seguían afirmando lo contrario
hasta que alguien lo notó por accidente. La corrección de una limitación externa no avisa sola.

**16. Cuando el usuario corrige un ítem, borrarlo de TODAS las memorias.**
No solo de la que tenías abierta. Un pendiente muerto se reinyecta desde notas viejas y vuelve a
aparecer sesiones después como si fuera nuevo.

**17. Regla de mínimo cambio.**
Cuando el pedido es simple ("guardá X", "cambiá Y"), hacer el mínimo cambio posible. Un pedido
chico no es una invitación a rediseñar el sistema, agregar UI que nadie pidió ni reemplazar
lógica existente.

**18. Antes de codear, el marco.**
Verdad sobre lo que se pide (cuestionarlo, no asumirlo) → al menos dos opciones → complejidad de
cada una → mejoras si las hay → encaje y costo en el entorno → resumen y opinión. Esperar la
elección. **Esto se cumple sobre todo cuando el pedido parece obvio**: ahí es donde más seguido
se construye lo que no era.

---

## Sobre los hooks

Los hooks de `.claude/settings.json` **bloquean de verdad** y le ganan a `CLAUDE.md`. Es a
propósito: una directiva escrita se puede ignorar, un hook no.

Gotchas que cuestan tiempo:

- Al editar un hook, **la versión vieja sigue cargada en la sesión** hasta abrir `/hooks` o
  reiniciar.
- El hook de Bash matchea sobre el **comando completo**: si el texto que estás escribiendo
  contiene un string prohibido, lo bloquea aunque sea documentación. Partir esos strings.
- Cuando un hook deniega una edición, el archivo **no se escribe**. Para escribir fuera del repo
  con un hook viejo activo, el shell (here-doc) no dispara el matcher `Edit|Write`.
- Sin `git init`, los hooks que consultan la rama salen en silencio y no bloquean nada.

---

## Cómo crece esta lista

Una lección entra acá cuando **aplicaría igual en otro proyecto**. Si es específica del dominio,
va en el `CLAUDE.md` de ese repo o en una memoria local, no acá. Ese es el único criterio.
