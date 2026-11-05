// Uso: node dyssa-articulos.js <coleccion-postman> <archivo-salida> [cantidad]
// Solo LECTURA: pide un token de Keycloak y hace un GET a get-articulos v4 (una pagina). No imprime credenciales.
const fs = require('fs');
const [coleccion, salida, cantidadArg] = process.argv.slice(2);
const cantidad = Number(cantidadArg || 10);
const col = JSON.parse(fs.readFileSync(coleccion, 'utf8'));
const vars = {};
(col.variable || []).forEach((v) => { vars[v.key] = v.value; });

function resolver(valor) {
  if (typeof valor !== 'string') return valor;
  return valor.replace(/\{\{([^}]+)\}\}/g, (_, k) => (k in vars ? vars[k] : `{{${k}}}`));
}

let pedidoToken = null;
(function buscar(items) {
  for (const it of items || []) {
    if (it.item) buscar(it.item);
    const url = it.request && it.request.url && (it.request.url.raw || '');
    if (!pedidoToken && url.includes('openid-connect/token')) pedidoToken = it.request;
  }
})(col.item);
if (!pedidoToken) { console.error('No encontre el request del token en la coleccion'); process.exit(1); }

const campos = {};
((pedidoToken.body && pedidoToken.body.urlencoded) || []).forEach((p) => { if (!p.disabled) campos[p.key] = resolver(p.value); });
if (!campos.client_id || /\{\{/.test(campos.client_id)) {
  console.log('client_id sin resolver en la coleccion (era:', JSON.stringify(campos.client_id), ') -> uso el documentado: gcw-web-api');
  campos.client_id = 'gcw-web-api';
}
const urlToken = resolver(pedidoToken.url.raw);
const faltan = ['client_id', 'username', 'password', 'grant_type'].filter((k) => !campos[k] || /\{\{/.test(campos[k]));
console.log('url del token:', urlToken.replace(/\?.*/, ''));
console.log('campos del token presentes:', Object.keys(campos).join(', '), faltan.length ? `| SIN RESOLVER: ${faltan.join(', ')}` : '');
if (faltan.length) process.exit(1);

const host = (urlToken.match(/realms\/gcw-([a-z0-9]+)\//i) || [])[1];
if (host !== 'dyssa') { console.error('El realm no es dyssa, no sigo'); process.exit(1); }

(async () => {
  const rt = await fetch(urlToken, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(campos).toString() });
  const jt = await rt.json().catch(() => ({}));
  if (!rt.ok || !jt.access_token) { console.error('Token: HTTP', rt.status, jt.error || ''); process.exit(1); }
  console.log('token: ok (expira en', jt.expires_in, 's)');

  const url = 'https://dyssa.gescom.online/data/cmd/inventario/api/v4/get-articulos?pagesize=1000&pagestoskip=0&pagestotake=1';
  const ra = await fetch(url, { headers: { Authorization: 'Bearer ' + jt.access_token } });
  const arts = await ra.json().catch(() => null);
  if (!ra.ok || !Array.isArray(arts)) { console.error('get-articulos: HTTP', ra.status, JSON.stringify(arts).slice(0, 300)); process.exit(1); }
  console.log('articulos en la pagina:', arts.length);
  console.log('claves del primer articulo:', Object.keys(arts[0]).join(', '));

  const ean = (a) => String(a.codigoBarras || '').trim();
  const valido13 = (s) => {
    if (!/^\d{13}$/.test(s)) return false;
    const suma = [...s.slice(0, 12)].reduce((acc, d, i) => acc + Number(d) * (i % 2 ? 3 : 1), 0);
    return (10 - (suma % 10)) % 10 === Number(s[12]);
  };
  const validos = arts.filter((a) => valido13(ean(a)));
  const con779 = validos.filter((a) => ean(a).startsWith('779'));
  console.log(`con codigoBarras no vacio: ${arts.filter((a) => ean(a)).length} | EAN-13 valido: ${validos.length} | de Argentina (779): ${con779.length}`);

  const paso = Math.max(1, Math.floor(con779.length / cantidad));
  const elegidos = [];
  for (let i = 0; i < con779.length && elegidos.length < cantidad; i += paso) elegidos.push(con779[i]);
  const out = elegidos.map((a) => ({ codigo: a.codigo, descripcion: a.descripcion, ean: ean(a), codigoRubro: a.codigoRubro, codigoMarca: a.codigoMarca }));
  fs.writeFileSync(salida, JSON.stringify(out, null, 2));
  out.forEach((o) => console.log(`${o.ean} | ${o.codigo} | ${o.descripcion}`));
})();
