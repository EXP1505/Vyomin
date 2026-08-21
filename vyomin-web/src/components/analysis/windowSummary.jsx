import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { Panel } from '../design/Panel';

// Shared WindowSummary rendering pieces, factored out of EventStudyAnalysis.jsx so the
// single-query result cards, the sweep result modal, and the country-dossier's marketRelevance
// section all render a WindowSummary object identically instead of three drifting copies.

export function formatPercent(n, digits = 3) {
  if (typeof n !== 'number' || Number.isNaN(n)) return '—';
  const sign = n > 0 ? '+' : '';
  return `${sign}${(n * 100).toFixed(digits)}%`;
}

export function significanceStyle(pass) {
  return pass
    ? { color: 'var(--positive)', borderColor: 'var(--positive)', background: 'rgba(53,214,184,0.1)' }
    : { color: 'var(--text-dim)', borderColor: 'var(--hairline)' };
}

// Directional badge is ONLY ever meaningful for cards where the result survives correction -
// describes what happened in the tested sample, not a recommendation.
export function directionBadge(meanReturn) {
  if (typeof meanReturn !== 'number' || meanReturn === 0) return null;
  const rising = meanReturn > 0;
  return {
    label: rising ? 'Basket tended to rise after this event' : 'Basket tended to fall after this event',
    style: rising
      ? { color: 'var(--positive)', borderColor: 'var(--positive)', background: 'rgba(53,214,184,0.1)' }
      : { color: 'var(--negative)', borderColor: 'var(--negative)', background: 'rgba(255,92,108,0.08)' },
  };
}

// bootstrapStatus -> badge text/style + whether the p-value figure is trustworthy to show. Three
// visually distinct treatments so INVALID_SATURATED and NO_DATA can never be mistaken for a real
// "not significant" result at a glance.
export function bootstrapBadge(w) {
  if (w.bootstrapStatus === 'INVALID_SATURATED') {
    return {
      label: 'Cannot test — coverage too saturated',
      style: { color: 'var(--accent)', borderColor: 'var(--accent)', background: 'rgba(255,176,32,0.1)' },
      showPValue: false,
    };
  }
  if (w.bootstrapStatus === 'NO_DATA') {
    return {
      label: 'No data',
      style: { color: 'var(--negative)', borderColor: 'var(--negative)', background: 'rgba(255,92,108,0.08)' },
      showPValue: false,
    };
  }
  const hasP = typeof w.bootstrapPValue === 'number';
  const significant = hasP && w.bootstrapPValue < 0.05;
  return {
    label: significant ? 'Statistically significant' : 'Not statistically distinguishable from random',
    style: significanceStyle(significant),
    showPValue: hasP,
  };
}

export function pct1(n) {
  return typeof n === 'number' && !Number.isNaN(n) ? (n * 100).toFixed(1) : null;
}

export function takeawayStyle(tone) {
  switch (tone) {
    case 'positive':
      return { color: 'var(--positive)', borderColor: 'var(--positive)', background: 'rgba(53,214,184,0.08)' };
    case 'warning':
      return { color: 'var(--accent)', borderColor: 'var(--accent)', background: 'rgba(255,176,32,0.08)' };
    case 'negative':
      return { color: 'var(--negative)', borderColor: 'var(--negative)', background: 'rgba(255,92,108,0.08)' };
    default:
      return { color: 'var(--text-dim)', borderColor: 'var(--hairline)', background: 'var(--panel-2)' };
  }
}

export function buildTakeaway(w) {
  if (w.bootstrapStatus === 'OK') {
    const significant = typeof w.bootstrapPValue === 'number' && w.bootstrapPValue < 0.05;
    const hitRateHigh = typeof w.hitRate === 'number' && w.hitRate * 100 >= 55;
    return significant && hitRateHigh
      ? { text: "This looks like a real, worth-investigating pattern — but still not proof, and doesn't account for trading costs.", tone: 'positive' }
      : { text: 'This does not look like a reliable pattern — the average is within the range random days would also produce.', tone: 'neutral' };
  }
  if (w.bootstrapStatus === 'INVALID_SATURATED') {
    return {
      text: 'This event type is too common in this window to test this way — try a narrower filter (specific actor pair, higher severity, shorter date range) or a rarer event type.',
      tone: 'warning',
    };
  }
  if (w.bootstrapStatus === 'NO_DATA') {
    return { text: 'Try a broader filter or a wider date range.', tone: 'negative' };
  }
  return { text: 'Not enough information to summarize.', tone: 'neutral' };
}

// Five-tone chip language (superset of takeawayStyle's four tones) for the p-value/hit-rate/
// coverage classification chips.
export function toneChipStyle(tone) {
  switch (tone) {
    case 'strong-positive':
      return { color: 'var(--positive)', borderColor: 'var(--positive)', background: 'rgba(53,214,184,0.2)' };
    case 'positive':
      return { color: 'var(--positive)', borderColor: 'var(--positive)', background: 'rgba(53,214,184,0.08)' };
    case 'warning':
      return { color: 'var(--accent)', borderColor: 'var(--accent)', background: 'rgba(255,176,32,0.1)' };
    case 'negative':
      return { color: 'var(--negative)', borderColor: 'var(--negative)', background: 'rgba(255,92,108,0.12)' };
    case 'muted-negative':
      return { color: 'var(--negative)', borderColor: 'var(--negative)', background: 'rgba(255,92,108,0.05)' };
    default:
      return { color: 'var(--text-dim)', borderColor: 'var(--hairline)', background: 'var(--panel-2)' };
  }
}

export const PVALUE_CLASSIFICATIONS = [
  { range: '< 0.01', label: 'Very unlikely to be random — likely real', tone: 'strong-positive', test: (p) => p < 0.01 },
  { range: '0.01–0.05', label: 'Unlikely to be random — possibly real', tone: 'positive', test: (p) => p < 0.05 },
  { range: '0.05–0.20', label: 'Could easily be random — inconclusive', tone: 'neutral', test: (p) => p <= 0.2 },
  { range: '> 0.20', label: 'Looks like random noise — not real', tone: 'muted-negative', test: () => true },
];

export function classifyPValue(p) {
  return PVALUE_CLASSIFICATIONS.find((c) => c.test(p));
}

// Generic centered modal: dimmed backdrop, scale+fade transition in/out, closes on X/backdrop/Escape.
export function Modal({ isOpen, onClose, title, children, durationMs = 200 }) {
  const [mounted, setMounted] = useState(false);
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (isOpen) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setMounted(true);
      const raf = requestAnimationFrame(() => setVisible(true));
      return () => cancelAnimationFrame(raf);
    }
    setVisible(false);
    const t = setTimeout(() => setMounted(false), durationMs);
    return () => clearTimeout(t);
  }, [isOpen, durationMs]);

  useEffect(() => {
    if (!mounted) return undefined;
    const onKeyDown = (e) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [mounted, onClose]);

  if (!mounted) return null;

  return createPortal(
    <div className="fixed inset-0 z-50" onMouseDown={onClose}>
      <div
        className="absolute inset-0 bg-black transition-opacity"
        style={{ opacity: visible ? 0.7 : 0, transitionDuration: `${durationMs}ms` }}
      />
      <div className="absolute inset-0 flex items-center justify-center p-4">
        <div
          className="border overflow-y-auto transition-all"
          onMouseDown={(e) => e.stopPropagation()}
          style={{
            width: '82vw',
            maxWidth: '82vw',
            maxHeight: '85vh',
            background: 'var(--panel)',
            borderColor: 'var(--hairline)',
            opacity: visible ? 1 : 0,
            transform: visible ? 'scale(1)' : 'scale(0.95)',
            transitionDuration: `${durationMs}ms`,
          }}
        >
          <div
            className="p-4 flex items-center justify-between gap-4 border-b sticky top-0 z-10"
            style={{ borderColor: 'var(--hairline)', background: 'var(--panel)' }}
          >
            <div className="font-semibold text-xl" style={{ color: 'var(--text)' }}>{title}</div>
            <button
              onClick={onClose}
              className="border px-3 py-1.5 text-base transition-colors"
              style={{ borderColor: 'var(--hairline)', color: 'var(--text-dim)' }}
            >
              ✕
            </button>
          </div>
          <div className="p-6 text-base">{children}</div>
        </div>
      </div>
    </div>,
    document.body
  );
}

export function DivergingBar({ value, noiseBand }) {
  const v = typeof value === 'number' ? value : 0;
  const scaleMax = Math.max(0.01, Math.abs(v) * 1.15);
  const halfPct = Math.min(1, Math.abs(v) / scaleMax) * 50;
  const positive = v >= 0;

  const bandMinPct = noiseBand ? Math.min(1, noiseBand.min / scaleMax) * 50 : null;
  const bandMaxPct = noiseBand ? Math.min(1, noiseBand.max / scaleMax) * 50 : null;

  return (
    <div className="relative w-full h-3 mt-3" style={{ background: 'var(--panel-2)' }}>
      {noiseBand && (
        <>
          <div
            className="absolute top-0 bottom-0"
            style={{ left: `${50 + bandMinPct}%`, width: `${bandMaxPct - bandMinPct}%`, background: 'rgba(124,134,152,0.3)' }}
          />
          <div
            className="absolute top-0 bottom-0"
            style={{ right: `${50 + bandMinPct}%`, width: `${bandMaxPct - bandMinPct}%`, background: 'rgba(124,134,152,0.3)' }}
          />
        </>
      )}
      <div
        className="absolute top-0 bottom-0"
        style={{
          ...(positive ? { left: '50%', width: `${halfPct}%` } : { right: '50%', width: `${halfPct}%` }),
          background: positive ? 'var(--positive)' : 'var(--negative)',
        }}
      />
      <div className="absolute top-0 bottom-0 w-px" style={{ left: '50%', background: 'var(--text-faint)' }} />
    </div>
  );
}

// Hand-rolled donut (stroke-dasharray trick) rather than a charting library - no existing Recharts
// usage anywhere in this codebase despite it being a listed dependency.
export function DonutRing({ percent, size = 120, strokeWidth = 14 }) {
  const p = typeof percent === 'number' ? Math.max(0, Math.min(100, percent)) : 0;
  const radius = (size - strokeWidth) / 2;
  const center = size / 2;
  const circumference = 2 * Math.PI * radius;
  const filled = (p / 100) * circumference;

  const angleFor = (pct) => (-90 + (pct / 100) * 360) * (Math.PI / 180);
  const refAngle = angleFor(50);
  const tickInner = radius - strokeWidth / 2 - 3;
  const tickOuter = radius + strokeWidth / 2 + 3;

  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
      <circle cx={center} cy={center} r={radius} fill="none" stroke="var(--hairline)" strokeWidth={strokeWidth} />
      <circle
        cx={center}
        cy={center}
        r={radius}
        fill="none"
        stroke="var(--positive)"
        strokeWidth={strokeWidth}
        strokeDasharray={`${filled} ${Math.max(0, circumference - filled)}`}
        transform={`rotate(-90 ${center} ${center})`}
      />
      <line
        x1={center + tickInner * Math.cos(refAngle)}
        y1={center + tickInner * Math.sin(refAngle)}
        x2={center + tickOuter * Math.cos(refAngle)}
        y2={center + tickOuter * Math.sin(refAngle)}
        stroke="var(--text-faint)"
        strokeWidth={2}
      />
      <text
        x={center}
        y={center}
        textAnchor="middle"
        dominantBaseline="central"
        fontSize={size * 0.2}
        fontFamily="'JetBrains Mono', monospace"
        fontWeight="bold"
        fill="var(--text)"
      >
        {p.toFixed(0)}%
      </text>
    </svg>
  );
}

function pValueToBarPercent(p) {
  const SIG_ZONE_WIDTH = 25;
  if (p <= 0.05) return (p / 0.05) * SIG_ZONE_WIDTH;
  return SIG_ZONE_WIDTH + ((p - 0.05) / 0.95) * (100 - SIG_ZONE_WIDTH);
}

export function PValueBar({ pValue, correctedThreshold, compact = false }) {
  const significant = pValue < 0.05;
  const markerPct = pValueToBarPercent(pValue);
  const zoneBoundary = pValueToBarPercent(0.05);
  const correctedPct = typeof correctedThreshold === 'number' ? pValueToBarPercent(correctedThreshold) : null;
  const barHeight = compact ? 10 : 20;
  const dotSize = compact ? 8 : 12;

  return (
    <div className={compact ? 'mt-2' : 'mt-3'}>
      <div className="relative w-full" style={{ height: barHeight, background: 'var(--panel-2)' }}>
        <div
          className="absolute top-0 bottom-0 left-0"
          style={{ width: `${zoneBoundary}%`, background: 'rgba(53,214,184,0.15)' }}
        />
        <div
          className="absolute top-0 bottom-0"
          style={{ left: `${zoneBoundary}%`, right: 0, background: 'rgba(124,134,152,0.1)' }}
        />
        <div className="absolute top-0 bottom-0 w-px" style={{ left: `${zoneBoundary}%`, background: 'var(--hairline)' }} />
        {correctedPct != null && (
          <div
            className="absolute top-0 bottom-0"
            style={{ left: `${correctedPct}%`, width: 2, background: 'var(--accent)' }}
            title={`Bonferroni-corrected threshold: ${correctedThreshold.toFixed(5)}`}
          />
        )}
        <div
          className="absolute rounded-full"
          style={{
            left: `${markerPct}%`,
            top: '50%',
            width: dotSize,
            height: dotSize,
            transform: 'translate(-50%, -50%)',
            background: significant ? 'var(--positive)' : 'var(--text-dim)',
            border: `${compact ? 1 : 2}px solid var(--panel)`,
          }}
        />
      </div>
      {!compact && (
        <div className="flex justify-between text-xs mt-1" style={{ color: 'var(--text-faint)' }}>
          <span>0</span>
          <span style={{ position: 'relative', left: `-${50 - zoneBoundary}%` }}>0.05</span>
          <span>1</span>
        </div>
      )}
      {!compact && correctedPct != null && (
        <div className="text-[10px] mt-0.5" style={{ color: 'var(--accent)' }}>
          Amber line = Bonferroni-corrected threshold ({correctedThreshold.toFixed(5)})
        </div>
      )}
    </div>
  );
}

export function CoverageBar({ percent }) {
  const p = typeof percent === 'number' ? Math.max(0, Math.min(100, percent)) : 0;
  return (
    <div className="w-full h-4 mt-3 relative" style={{ background: 'var(--panel-2)' }}>
      <div className="absolute top-0 bottom-0 left-0" style={{ width: `${p}%`, background: 'var(--accent)' }} />
    </div>
  );
}

export function StatPanel({ label, children }) {
  return (
    <div className="border p-4" style={{ borderColor: 'var(--hairline)', background: 'var(--panel-2)' }}>
      <div className="text-sm font-semibold uppercase tracking-wide" style={{ color: 'var(--text-faint)' }}>{label}</div>
      {children}
    </div>
  );
}

export function ExplainModalContent({ w }) {
  const meanPositive = typeof w.meanReturn === 'number' && w.meanReturn >= 0;
  const totalTradingDays = w.coverage > 0 ? Math.round(w.independentWindowCount / w.coverage) : null;
  const takeaway = buildTakeaway(w);

  return (
    <div>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <StatPanel label="Mean return">
          <div
            className="font-mono-data text-3xl font-bold mt-1"
            style={{ color: meanPositive ? 'var(--positive)' : 'var(--negative)' }}
          >
            {formatPercent(w.meanReturn)}
          </div>
          <DivergingBar value={w.meanReturn} />
          <div className="text-sm mt-2" style={{ color: 'var(--text-faint)' }}>
            Average move across {w.independentWindowCount} independent event-days.
          </div>
        </StatPanel>

        <StatPanel label="Hit rate">
          <div className="flex items-center gap-4 mt-1">
            <DonutRing percent={typeof w.hitRate === 'number' ? w.hitRate * 100 : 0} />
            <div className="text-sm" style={{ color: 'var(--text-faint)' }}>
              {typeof w.hitRate === 'number' ? (
                <>
                  {pct1(w.hitRate)}% of event-days moved positive
                  <span style={{ color: 'var(--text-dim)' }}> (gray tick marks the 50% coin-flip line).</span>
                </>
              ) : (
                'No data.'
              )}
            </div>
          </div>
        </StatPanel>

        <StatPanel label="Bootstrap p-value">
          {w.bootstrapStatus === 'OK' && typeof w.bootstrapPValue === 'number' ? (
            <>
              <div className="font-mono-data text-3xl font-bold mt-1" style={{ color: w.bootstrapPValue < 0.05 ? 'var(--positive)' : 'var(--text)' }}>
                {w.bootstrapPValue.toFixed(4)}
              </div>
              <div className="mt-2">
                <span className="inline-block px-2.5 py-1 border text-xs" style={toneChipStyle(classifyPValue(w.bootstrapPValue).tone)}>
                  {classifyPValue(w.bootstrapPValue).label}
                </span>
              </div>
              <PValueBar pValue={w.bootstrapPValue} />
              <div className="text-sm mt-2" style={{ color: 'var(--text-faint)' }}>
                Green zone (0–0.05) = significant. Marker is green if the real p-value falls inside it.
              </div>
            </>
          ) : (
            <div
              className="mt-2 p-3 border text-sm"
              style={
                w.bootstrapStatus === 'INVALID_SATURATED'
                  ? { color: 'var(--accent)', borderColor: 'var(--accent)', background: 'rgba(255,176,32,0.08)' }
                  : { color: 'var(--negative)', borderColor: 'var(--negative)', background: 'rgba(255,92,108,0.08)' }
              }
            >
              {w.bootstrapStatusReason || 'No test could be run for this window.'}
            </div>
          )}
        </StatPanel>

        <StatPanel label="Coverage">
          <div className="font-mono-data text-3xl font-bold mt-1" style={{ color: 'var(--text)' }}>
            {pct1(w.coverage) ?? '—'}%
          </div>
          <CoverageBar percent={typeof w.coverage === 'number' ? w.coverage * 100 : 0} />
          <div className="text-sm mt-2" style={{ color: 'var(--text-faint)' }}>
            {totalTradingDays != null
              ? `${w.independentWindowCount} of ${totalTradingDays} trading days had a qualifying event.`
              : `${w.independentWindowCount} trading days had a qualifying event.`}
          </div>
        </StatPanel>
      </div>

      <div className="mt-5 p-4 border text-base font-bold text-center" style={takeawayStyle(takeaway.tone)}>
        {takeaway.text}
      </div>
    </div>
  );
}

// query = { eventType, actor1CountryCode, actor2CountryCode } - only used to label the "Explain
// this" modal's title, so callers can pass a partial object (e.g. just actor1CountryCode).
export function WindowCard({ w, query }) {
  const [modalOpen, setModalOpen] = useState(false);
  const meanPositive = typeof w.meanReturn === 'number' && w.meanReturn >= 0;
  const badge = bootstrapBadge(w);
  const q = query || {};

  const modalTitle = `${w.windowDays}-day window — ${q.eventType || 'any event type'} ${q.actor1CountryCode || 'any actor'}` +
    `${q.actor2CountryCode ? ' ↔ ' + q.actor2CountryCode : ''}`;

  return (
    <Panel className="p-4">
      <div className="flex items-start justify-between mb-1 gap-2">
        <div className="font-mono-data text-sm font-semibold" style={{ color: 'var(--text)' }}>
          +{w.windowDays} trading day{w.windowDays > 1 ? 's' : ''}
        </div>
        <span
          className="font-mono-data text-[11px] px-2 py-1 border text-right"
          style={badge.style}
          title={w.bootstrapStatusReason || undefined}
        >
          {badge.label}
        </span>
      </div>
      {w.bootstrapStatusReason && (
        <div className="text-xs text-right mb-2" style={{ color: 'var(--text-faint)' }}>
          {w.bootstrapStatusReason}
        </div>
      )}

      <div className="grid grid-cols-2 gap-3 text-sm mt-2">
        <div>
          <div className="text-xs" style={{ color: 'var(--text-faint)' }}>Mean return</div>
          <div className="font-mono-data" style={{ color: meanPositive ? 'var(--positive)' : 'var(--negative)' }}>
            {formatPercent(w.meanReturn)}
          </div>
        </div>
        <div>
          <div className="text-xs" style={{ color: 'var(--text-faint)' }}>Hit rate</div>
          <div className="font-mono-data" style={{ color: 'var(--text)' }}>
            {typeof w.hitRate === 'number' ? `${(w.hitRate * 100).toFixed(1)}%` : '—'}
          </div>
        </div>
        <div>
          <div className="text-xs" style={{ color: 'var(--text-faint)' }}>Bootstrap p-value</div>
          <div className="font-mono-data" style={{ color: badge.showPValue ? 'var(--text)' : 'var(--text-faint)' }}>
            {badge.showPValue ? w.bootstrapPValue.toFixed(4) : '—'}
          </div>
        </div>
        <div>
          <div className="text-xs" style={{ color: 'var(--text-faint)' }}>Coverage</div>
          <div className="font-mono-data" style={{ color: 'var(--text)' }}>
            {typeof w.coverage === 'number' ? `${(w.coverage * 100).toFixed(1)}%` : '—'}
          </div>
        </div>
      </div>

      <div className="mt-4 pt-3 border-t" style={{ borderColor: 'var(--hairline)' }}>
        <div className="font-mono-data text-base" style={{ color: 'var(--accent)' }}>n = {w.independentWindowCount}</div>
        <div className="text-xs" style={{ color: 'var(--text-faint)' }}>
          Independent trading-day samples — the real sample size behind meanReturn/hitRate above.
        </div>
      </div>

      <div className="mt-2 text-xs" style={{ color: 'var(--text-dim)' }}>
        {w.distinctEventCountThisWindow} events matched · {w.totalArticleCount} source articles
        <span style={{ color: 'var(--text-faint)' }}> — not sample size, many events can share one trading day.</span>
      </div>

      <button
        type="button"
        onClick={() => setModalOpen(true)}
        className="w-full mt-3 pt-3 border-t flex items-center justify-between text-xs transition-colors"
        style={{ borderColor: 'var(--hairline)', color: 'var(--text-dim)' }}
        onMouseEnter={(e) => (e.currentTarget.style.color = 'var(--accent)')}
        onMouseLeave={(e) => (e.currentTarget.style.color = 'var(--text-dim)')}
      >
        <span>Explain this</span>
        <span className="font-mono-data">→</span>
      </button>

      <Modal isOpen={modalOpen} onClose={() => setModalOpen(false)} title={modalTitle}>
        <ExplainModalContent w={w} />
      </Modal>
    </Panel>
  );
}
