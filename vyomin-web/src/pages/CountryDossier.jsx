import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Panel } from '../components/design/Panel';
import { COUNTRY_CODE_TO_NAME } from '../data/countries';
import { WindowCard } from '../components/analysis/windowSummary';

// Same defaults EventStudyAnalysis.jsx uses (WINDOWS/basket/date range) - the dossier is meant to
// give an at-a-glance read with zero configuration, so it doesn't expose a form.
const DEFAULT_DATE_FROM = '2026-02-01';
const DEFAULT_DATE_TO = '2026-08-01';
const DEFAULT_BASKET = 'LMT,RTX,NOC,GD,BA';

// Ranked horizontal-bar list for byEventType - hand-rolled to match the rest of the app's charts
// (CoverageBar/DivergingBar in windowSummary.jsx), not Recharts (unused anywhere in this codebase
// despite being a listed dependency - see EventStudyAnalysis.jsx's own note on this).
function EventTypeBars({ rows }) {
  const max = rows.length ? Math.max(...rows.map((r) => r.count)) : 0;
  return (
    <div className="flex flex-col gap-2">
      {rows.map((r) => (
        <div key={r.eventType} className="flex items-center gap-3 text-sm">
          <div className="w-44 shrink-0 truncate" style={{ color: 'var(--text-dim)' }} title={r.eventType}>
            {r.eventType}
          </div>
          <div className="flex-1 h-3 relative" style={{ background: 'var(--panel-2)' }}>
            <div
              className="absolute top-0 bottom-0 left-0"
              style={{ width: `${max > 0 ? (r.count / max) * 100 : 0}%`, background: 'var(--accent)' }}
            />
          </div>
          <div className="w-16 shrink-0 text-right font-mono-data text-xs" style={{ color: 'var(--text-faint)' }}>
            {r.count.toLocaleString()}
          </div>
        </div>
      ))}
    </div>
  );
}

// List item layout mirrors FinanceDashboard.jsx's "Related Conflict Signals" Panel (full-width
// row, bottom border between rows, hover highlight) - each row links out to sourceUrl when present.
function RecentEventsList({ events }) {
  if (events.length === 0) {
    return (
      <div className="px-3 py-3 text-sm" style={{ color: 'var(--text-faint)' }}>
        No recent events in this range.
      </div>
    );
  }
  return (
    <>
      {events.map((ev, i) => {
        const Row = ev.sourceUrl ? 'a' : 'div';
        const rowProps = ev.sourceUrl ? { href: ev.sourceUrl, target: '_blank', rel: 'noreferrer' } : {};
        return (
          <Row
            key={i}
            {...rowProps}
            className="flex items-center justify-between gap-3 px-3 py-2 border-b last:border-b-0 text-left transition-colors hover:bg-[var(--panel-2)]"
            style={{ borderColor: 'var(--hairline)' }}
          >
            <div className="min-w-0 flex-1">
              <div className="text-sm truncate" style={{ color: 'var(--text)' }}>
                {ev.eventType}
                <span style={{ color: 'var(--text-faint)' }}>
                  {' '}
                  — {ev.actor1 || '—'} ↔ {ev.actor2 || '—'}
                </span>
              </div>
            </div>
            <div className="flex items-center gap-3 shrink-0">
              {typeof ev.severityScore === 'number' && (
                <span className="font-mono-data text-xs" style={{ color: 'var(--text-faint)' }}>
                  sev {ev.severityScore.toFixed(0)}
                </span>
              )}
              <span className="font-mono-data text-xs" style={{ color: 'var(--text-faint)' }}>
                {ev.eventDate}
              </span>
            </div>
          </Row>
        );
      })}
    </>
  );
}

function LoadingState() {
  return (
    <div className="space-y-4">
      <div className="h-6 w-64 rounded bg-slate-800/60 animate-pulse" />
      <div className="h-4 w-40 rounded bg-slate-800/60 animate-pulse" />
      <div className="h-48 rounded bg-slate-800/40 animate-pulse mt-6" />
    </div>
  );
}

// Extracts the eventType the dossier's marketRelevance was actually tested against, from the
// backend's note string ("Based on all <eventType>-type events involving <code>, ..."), so
// WindowCard's modal title can show it without the backend needing a separate structured field.
function eventTypeFromNote(note) {
  const m = /Based on all (.+)-type events/.exec(note || '');
  return m ? m[1] : null;
}

export default function CountryDossier() {
  const { countryCode } = useParams();
  const navigate = useNavigate();
  const code = (countryCode || '').toUpperCase();
  const countryName = COUNTRY_CODE_TO_NAME.get(code) || code;

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [data, setData] = useState(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setData(null);

    const url = `/api/analysis/country-dossier?code=${encodeURIComponent(code)}&dateFrom=${DEFAULT_DATE_FROM}&dateTo=${DEFAULT_DATE_TO}&basket=${encodeURIComponent(DEFAULT_BASKET)}`;
    fetch(url)
      .then((res) => {
        if (!res.ok) throw new Error(`Request failed (${res.status})`);
        return res.json();
      })
      .then((json) => {
        if (!cancelled) setData(json);
      })
      .catch((err) => {
        if (!cancelled) setError(err.message || 'Failed to load country dossier');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [code]);

  const topEventTypes = useMemo(
    () => (data?.eventSummary?.byEventType || []).slice(0, 8),
    [data]
  );

  const marketRelevance = data?.marketRelevance;
  const allSaturated = useMemo(
    () => !!marketRelevance && marketRelevance.summary.every((w) => w.bootstrapStatus === 'INVALID_SATURATED'),
    [marketRelevance]
  );
  const testedEventType = useMemo(() => eventTypeFromNote(marketRelevance?.note), [marketRelevance]);

  return (
    <div className="h-full overflow-y-auto">
      <div className="flex items-center justify-between mb-4">
        <button
          type="button"
          onClick={() => navigate(-1)}
          className="font-mono-data text-xs border px-3 py-1.5 transition-colors"
          style={{ borderColor: 'var(--hairline)', color: 'var(--text-dim)' }}
          onMouseEnter={(e) => (e.currentTarget.style.color = 'var(--accent)')}
          onMouseLeave={(e) => (e.currentTarget.style.color = 'var(--text-dim)')}
        >
          ← Back
        </button>
        <button
          type="button"
          onClick={() => navigate('/home')}
          className="font-mono-data text-xs border px-3 py-1.5 transition-colors"
          style={{ borderColor: 'var(--hairline)', color: 'var(--text-dim)' }}
          onMouseEnter={(e) => (e.currentTarget.style.color = 'var(--accent)')}
          onMouseLeave={(e) => (e.currentTarget.style.color = 'var(--text-dim)')}
        >
          ✕ Close
        </button>
      </div>

      <Panel className="p-5 mb-6">
        {loading && <LoadingState />}

        {!loading && error && (
          <div
            className="p-3 border text-sm"
            style={{ background: 'rgba(255,92,108,0.1)', borderColor: 'var(--negative)', color: 'var(--negative)' }}
          >
            {error}
          </div>
        )}

        {!loading && !error && data && (
          <>
            <div className="flex items-baseline justify-between gap-4 flex-wrap">
              <h2 className="text-lg font-semibold" style={{ color: 'var(--text)' }}>
                {countryName} <span className="font-mono-data text-sm" style={{ color: 'var(--text-faint)' }}>({code})</span>
              </h2>
              <div className="font-mono-data text-sm" style={{ color: 'var(--text-dim)' }}>
                {data.eventSummary.totalEvents.toLocaleString()} events
                <span style={{ color: 'var(--text-faint)' }}> · {data.dateFrom} to {data.dateTo}</span>
              </div>
            </div>

            {data.eventSummary.totalEvents === 0 ? (
              <div className="mt-4 text-sm" style={{ color: 'var(--text-faint)' }}>
                No event history found for this country in the selected range.
              </div>
            ) : (
              <>
                <div className="mt-5">
                  <div className="text-sm font-semibold mb-3" style={{ color: 'var(--text)' }}>Top event types</div>
                  <EventTypeBars rows={topEventTypes} />
                </div>

                <div className="mt-6">
                  <div className="text-sm font-semibold mb-3" style={{ color: 'var(--text)' }}>Recent events</div>
                  <Panel className="max-h-80 overflow-y-auto">
                    <RecentEventsList events={data.eventSummary.recentEvents} />
                  </Panel>
                </div>
              </>
            )}
          </>
        )}
      </Panel>

      {!loading && !error && data && (
        <Panel className="p-5">
          <div className="text-lg font-semibold mb-1" style={{ color: 'var(--text)' }}>Market Relevance</div>

          {marketRelevance == null ? (
            <div className="mt-2 text-sm" style={{ color: 'var(--text-faint)' }}>
              No significant event history found for this country in the selected range.
            </div>
          ) : allSaturated ? (
            <>
              <div
                className="mt-2 p-3 border text-sm"
                style={{ color: 'var(--accent)', borderColor: 'var(--accent)', background: 'rgba(255,176,32,0.08)' }}
              >
                This country appears in the news too frequently in this window to isolate a specific market
                reaction — try narrowing the date range or a more specific event type in the full Analyzer.
              </div>
              <button
                type="button"
                onClick={() => navigate('/analysis', { state: { actor1CountryCode: code } })}
                className="font-mono-data font-semibold mt-3 px-4 py-2 border transition-colors"
                style={{ borderColor: 'var(--accent)', color: 'var(--accent)', background: 'var(--accent-soft)' }}
              >
                Open in full Analyzer →
              </button>
            </>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mt-3">
              {marketRelevance.summary.map((w) => (
                <WindowCard
                  key={w.windowDays}
                  w={w}
                  query={{ eventType: testedEventType, actor1CountryCode: code }}
                />
              ))}
            </div>
          )}

          {marketRelevance?.note && (
            <div className="mt-4 text-xs italic" style={{ color: 'var(--text-faint)' }}>
              {marketRelevance.note}
            </div>
          )}
        </Panel>
      )}
    </div>
  );
}
