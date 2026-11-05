'use client';

import { useEffect, useState } from 'react';
import { apiUrl } from '../lib/api';
import { EXAMPLES } from '../lib/examples';
import type { Health, LookupResponse, StoreResult } from '../lib/types';

export default function HomePage() {
  return (
    <main>
      <h1>Fotos</h1>
      <p className="muted">Imágenes de productos por código de barras</p>
      <LookupCard />
      <HealthCard />
    </main>
  );
}

/**
 * Asks every store for one EAN and shows what each one answered, side by side. It is here so a
 * person can judge whether the VTEX source is useful before the real pipeline exists: the images
 * are shown exactly as each store serves them (no banner removal, no verification yet).
 */
function LookupCard() {
  const [ean, setEan] = useState('');
  const [loading, setLoading] = useState(false);
  const [response, setResponse] = useState<LookupResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function search(code: string) {
    const trimmed = code.trim();
    if (!trimmed) return;
    setLoading(true);
    setError(null);
    setResponse(null);
    try {
      const r = await fetch(apiUrl(`/admin/v1/lookup?ean=${encodeURIComponent(trimmed)}`), {
        headers: { Accept: 'application/json' },
      });
      if (r.status === 400) throw new Error('Código de barras inválido: revisá los dígitos y el dígito verificador.');
      if (r.status === 404) throw new Error('La consulta no está habilitada en este servidor (FOTOS_DEMO_ENABLED).');
      if (!r.ok) throw new Error(`El servidor contestó HTTP ${r.status}.`);
      setResponse((await r.json()) as LookupResponse);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  const found = response?.stores.filter((s) => s.status === 'FOUND').length ?? 0;

  return (
    <section className="card">
      <h2>Consultar un código de barras</h2>
      <form
        className="row"
        onSubmit={(e) => {
          e.preventDefault();
          void search(ean);
        }}
      >
        <input
          value={ean}
          onChange={(e) => setEan(e.target.value)}
          placeholder="Ej: 7790070337016"
          inputMode="numeric"
          aria-label="Código de barras"
          autoFocus
        />
        <button type="submit" disabled={loading || !ean.trim()}>
          {loading ? 'Consultando…' : 'Consultar'}
        </button>
      </form>

      <div className="chips" aria-label="Ejemplos">
        {EXAMPLES.map((x) => (
          <button
            key={x.ean}
            type="button"
            className="chip"
            title={x.note}
            disabled={loading}
            onClick={() => {
              setEan(x.ean);
              void search(x.ean);
            }}
          >
            {x.label}
          </button>
        ))}
      </div>

      {error && (
        <p className="bad" role="alert" data-testid="lookup-error">
          {error}
        </p>
      )}

      {response && (
        <div data-testid="lookup-result">
          <p className="summary">
            <strong>{found}</strong> de {response.stores.length} tiendas lo tienen
            <span className="muted"> · código {response.ean}</span>
          </p>
          <div className="stores">
            {response.stores.map((s) => (
              <StoreCard key={s.store} result={s} />
            ))}
          </div>
          <p className="muted small">
            Las imágenes se muestran tal cual las sirve cada tienda: todavía sin quitar banners ni verificar. Jumbo,
            Disco y Vea comparten los mismos archivos. La primera imagen de cada tienda es la principal; las demás
            pueden ser el reverso del envase.
          </p>
        </div>
      )}
    </section>
  );
}

function StoreCard({ result }: { result: StoreResult }) {
  const [main, ...others] = result.imageUrls;
  return (
    <article className={`store ${result.status.toLowerCase()}`} data-testid={`store-${result.store}`}>
      <header>
        <strong>{result.store}</strong>
        <span className="muted small">{result.millis} ms</span>
      </header>

      {result.status === 'FOUND' && (
        <>
          <span className="badge ok">Lo tiene</span>
          {main ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img className="main" src={main} alt={result.productName ?? result.store} referrerPolicy="no-referrer" />
          ) : (
            <p className="muted">Sin imagen</p>
          )}
          <p className="name">{result.productName}</p>
          {result.brand && <p className="muted small">{result.brand}</p>}
          {others.length > 0 && (
            <div className="thumbs" title="Otras imágenes de la tienda (pueden ser del reverso)">
              {others.map((url) => (
                // eslint-disable-next-line @next/next/no-img-element
                <img key={url} src={url} alt="" referrerPolicy="no-referrer" loading="lazy" />
              ))}
            </div>
          )}
          {result.pageUrl && (
            <a href={result.pageUrl} target="_blank" rel="noopener noreferrer" className="small">
              Ver en la tienda ↗
            </a>
          )}
        </>
      )}

      {result.status === 'NOT_FOUND' && <span className="badge muted-badge">No lo vende</span>}

      {result.status === 'ERROR' && <span className="badge bad-badge">No respondió: {result.error}</span>}
    </article>
  );
}

function HealthCard() {
  const [health, setHealth] = useState<Health | null>(null);
  const [error, setError] = useState<string | null>(null);
  const url = apiUrl('/health');

  useEffect(() => {
    fetch(url, { headers: { Accept: 'application/json' } })
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<Health>;
      })
      .then(setHealth)
      .catch((e: Error) => setError(e.message));
  }, [url]);

  return (
    <section className="card" aria-live="polite">
      <h2>Estado del servicio</h2>
      {health && (
        <dl data-testid="health">
          <dt>Estado</dt>
          <dd className={health.status === 'ok' ? 'ok' : 'bad'}>{health.status}</dd>
          <dt>Versión</dt>
          <dd>{health.version}</dd>
          <dt>Commit</dt>
          <dd>{health.commit}</dd>
          <dt>Arrancó</dt>
          <dd>
            {health.startedAt} (hace {health.upFor})
          </dd>
        </dl>
      )}
      {error && (
        <p className="bad" role="alert" data-testid="health-error">
          No se pudo leer el estado: {error}
        </p>
      )}
      {!health && !error && <p className="muted">Consultando…</p>}
      <p className="muted small">
        Pedido a <code data-testid="health-url">{url}</code>
      </p>
    </section>
  );
}
