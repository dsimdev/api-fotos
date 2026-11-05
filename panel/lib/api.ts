/**
 * Every request to the API is built here, never written as a bare '/something' in a component.
 *
 * NEXT_PUBLIC_API_URL is the prefix with which the BROWSER sees the API: empty without a nested
 * proxy, '/api/fotos' behind one. It is baked in at build time from PANEL_BASE_PATH (see
 * next.config.mjs). A request written as '/health' would go to the ROOT of the domain behind the
 * proxy, which is outside this service: the panel would load and then fail on its first call.
 */
export const API_BASE: string = process.env.NEXT_PUBLIC_API_URL ?? '';

export function apiUrl(path: string): string {
  return `${API_BASE}${path}`;
}
