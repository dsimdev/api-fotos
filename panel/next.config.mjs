/**
 * Static export: `next build` leaves plain HTML+JS in out/, with no Node runtime.
 *
 * That is what lets Gradle package it inside the jar and Spring serve it at /admin, in the same
 * origin as the API: no CORS, no mixed content, and a single artifact to deploy. With server
 * components or API routes a Node process would have to run next to it, which on an on-premise
 * server with IIS in front is exactly what we do not want.
 *
 * Spring ALWAYS serves the built panel at /admin (fixed, see copyPanel in fotos-app/build.gradle.kts).
 * But basePath is what the HTML tells the BROWSER where to ask for its chunks, and that depends on
 * where the browser enters, not on where the file lives:
 *
 *   - Direct access to Spring (local, or production without a nested proxy): the browser enters
 *     through /admin and basePath has to be '/admin'. That is the default below.
 *   - Behind an IIS that hangs this under a path that already exists (e.g. yourdomain.com/api/fotos):
 *     the browser enters through /api/fotos/admin. IIS strips the prefix before forwarding, so
 *     Spring still sees /admin/... as always, but the HTML has to ask for chunks at the FULL path the
 *     browser sees (/api/fotos/admin/_next/...) or it asks for them at the domain root and the panel
 *     is BLANK with 404s in the console.
 *
 * In api-impuestos that bug reached production THREE times. So basePath comes from an environment
 * variable at build time (PANEL_BASE_PATH) and is not fixed: the jar for a nested-proxy deploy is
 * built differently from the one tested locally. See fotos-app/build.gradle.kts (buildPanel).
 *
 * The same goes for requests to the API (panel/lib/api.ts): fetches written as absolute paths
 * (`/health`) are NOT touched by basePath, which is page routing, not hand-written fetch(). Without
 * NEXT_PUBLIC_API_URL, behind the nested proxy the panel would ask `/health` at the domain ROOT. It
 * comes from PANEL_BASE_PATH minus the trailing '/admin' (they are the same nested path) and is ''
 * when there is no proxy.
 */
const panelBasePath = process.env.PANEL_BASE_PATH || '/admin';
const apiBaseUrl = panelBasePath.replace(/\/admin$/, '');

const nextConfig = {
  output: 'export',
  basePath: panelBasePath,
  env: {
    NEXT_PUBLIC_API_URL: apiBaseUrl,
  },
  images: { unoptimized: true },
  trailingSlash: true,
};

export default nextConfig;
