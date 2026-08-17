import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Panel } from '../components/design/Panel';
import { CandlestickSvgChart } from '../components/StockDetailModal';
import { COUNTRIES } from '../data/countries';

// The 16 event_type values actually present in gdelt_event_history (verified via
// `SELECT DISTINCT event_type FROM gdelt_event_history`, not the full 20 CAMEO root categories -
// 4 of the low-Goldstein-intensity categories (Make Statement, Appeal, Investigate, Disapprove)
// never appear because the backfill's min-severity-abs cut excludes them, so they're deliberately
// left out here rather than offered as choices that would always return zero results.
const EVENT_TYPES = [
  'Assault', 'Coerce', 'Consult', 'Demand', 'Engage in Diplomatic Cooperation',
  'Engage in Material Cooperation', 'Exhibit Force Posture', 'Express Intent to Cooperate',
  'Fight', 'Protest', 'Provide Aid', 'Reduce Relations', 'Reject', 'Threaten',
  'Use Unconventional Mass Violence', 'Yield',
];

const WINDOWS = [1, 3, 5];

const inputStyle = { background: 'var(--panel-2)', borderColor: 'var(--hairline)', color: 'var(--text)' };

function formatPercent(n, digits = 3) {
  if (typeof n !== 'number' || Number.isNaN(n)) return '—';
  const sign = n > 0 ? '+' : '';
  return `${sign}${(n * 100).toFixed(digits)}%`;
}

function Field({ label, children }) {
  return (
    <label className="flex flex-col gap-1 text-xs" style={{ color: 'var(--text-dim)' }}>
      {label}
      {children}
    </label>
  );
}

const COUNTRY_CODE_TO_NAME = new Map(COUNTRIES.map((c) => [c.code, c.name]));

// Minimal combobox: displays/types a country NAME but tracks/submits an ISO alpha-3 CODE via
// onChange. No existing combobox/autocomplete library in this codebase (checked package.json),
// so this is hand-built - input + absolutely-positioned suggestion list, matching the existing
// dark/tactical inputStyle. Non-matching free text just clears the tracked code (submits nothing
// for that field) rather than blocking submission - simplest option the task allows, and actor
// fields are already optional so an empty code is a normal, valid state.
function CountryAutocomplete({ value, onChange, placeholder }) {
  const [inputText, setInputText] = useState(() => COUNTRY_CODE_TO_NAME.get(value) || value || '');
  const [open, setOpen] = useState(false);
  const [highlightIndex, setHighlightIndex] = useState(-1);
  const containerRef = useRef(null);

  // Sync display text if the code changes from outside (e.g. initial form state).
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setInputText(COUNTRY_CODE_TO_NAME.get(value) || value || '');
  }, [value]);

  const matches = useMemo(() => {
    const q = inputText.trim().toLowerCase();
    if (!q) return [];
    return COUNTRIES.filter((c) => c.name.toLowerCase().includes(q)).slice(0, 8);
  }, [inputText]);

  useEffect(() => {
    const onDocMouseDown = (e) => {
      if (containerRef.current && !containerRef.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', onDocMouseDown);
    return () => document.removeEventListener('mousedown', onDocMouseDown);
  }, []);

  const selectCountry = (c) => {
    setInputText(c.name);
    onChange(c.code);
    setOpen(false);
    setHighlightIndex(-1);
  };

  const handleChange = (e) => {
    const text = e.target.value;
    setInputText(text);
    setOpen(true);
    setHighlightIndex(-1);
    const exact = COUNTRIES.find((c) => c.name.toLowerCase() === text.trim().toLowerCase());
    onChange(exact ? exact.code : '');
  };

  const handleKeyDown = (e) => {
    if (!open || matches.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setHighlightIndex((i) => Math.min(matches.length - 1, i + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setHighlightIndex((i) => Math.max(0, i - 1));
    } else if (e.key === 'Enter') {
      if (highlightIndex >= 0) {
        e.preventDefault();
        selectCountry(matches[highlightIndex]);
      }
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  };

  const hasText = inputText.trim().length > 0;
  const hasCode = value && value.trim().length > 0;
  const showInvalidHint = hasText && !hasCode && matches.length === 0;

  return (
    <div ref={containerRef} className="relative">
      <input
        value={inputText}
        onChange={handleChange}
        onFocus={() => setOpen(true)}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        role="combobox"
        aria-expanded={open && matches.length > 0}
        aria-autocomplete="list"
        autoComplete="off"
        className="font-mono-data border px-3 py-2 text-sm focus:outline-none w-full"
        style={inputStyle}
      />
      {open && matches.length > 0 && (
        <ul
          className="absolute z-20 mt-1 w-full border max-h-56 overflow-y-auto"
          style={{ background: 'var(--panel)', borderColor: 'var(--hairline)' }}
        >
          {matches.map((c, i) => (
            <li
              key={c.code}
              onMouseDown={(e) => {
                e.preventDefault();
                selectCountry(c);
              }}
              onMouseEnter={() => setHighlightIndex(i)}
              className="px-3 py-2 text-sm cursor-pointer font-mono-data"
              style={{
                color: 'var(--text)',
                background: i === highlightIndex ? 'var(--accent-soft)' : 'transparent',
              }}
            >
              {c.name} <span style={{ color: 'var(--text-faint)' }}>({c.code})</span>
            </li>
          ))}
        </ul>
      )}
      {showInvalidHint && (
        <div className="text-[11px] mt-1" style={{ color: 'var(--text-faint)' }}>
          No matching country — this field will be left blank.
        </div>
      )}
    </div>
  );
}

// bootstrapStatus -> badge text/style + whether the p-value figure is trustworthy to show.
// Three visually distinct badge treatments (filled green / gray outline / amber outline / red
// outline) so INVALID_SATURATED and NO_DATA can never be mistaken for a real "not significant"
// result at a glance - all three used to render as the same gray badge (or none at all), which is
// exactly the misreading this exists to prevent.
function bootstrapBadge(w) {
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
    style: significant
      ? { color: 'var(--positive)', borderColor: 'var(--positive)', background: 'rgba(53,214,184,0.1)' }
      : { color: 'var(--text-dim)', borderColor: 'var(--hairline)' },
    showPValue: hasP,
  };
}

function pct1(n) {
  return typeof n === 'number' && !Number.isNaN(n) ? (n * 100).toFixed(1) : null;
}

// Tone -> border/text/background for the bottom-line takeaway callout. Reuses the same four-way
// color language as bootstrapBadge (positive/neutral/warning/negative) so a glance at either the
// badge or the takeaway reads consistently.
function takeawayStyle(tone) {
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

// The single bottom-line sentence for the modal - same branching as before, just no longer
// paired with a wall of prose paragraphs (those read as a wall of text nobody reads; the modal's
// four visual panels carry that information instead).
function buildTakeaway(w) {
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

// Generic centered modal: dimmed backdrop, scale+fade transition in/out, closes on X/backdrop/Escape.
// Stays mounted through the closing transition (unmounts `durationMs` after isOpen goes false) so
// the fade-out is actually visible instead of the content just vanishing.
function Modal({ isOpen, onClose, title, children, durationMs = 200 }) {
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

  // Single click handler on the outermost (fullscreen) container closes on any click that isn't
  // explicitly stopped - the backdrop visual and the flex-centering wrapper are purely visual with
  // no handlers of their own, only the actual panel content stops propagation. Two competing
  // same-size "absolute inset-0" siblings (backdrop div + wrapper div) would have the wrapper
  // silently swallow every click via its own stopPropagation before it could ever reach the
  // backdrop's handler underneath - this single-handler-on-the-outer-box structure avoids that.
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

// Zero-centered horizontal bar: fills right (green) for a positive value, left (red) for negative,
// width proportional to |value| against scaleMax (auto-widens past the default ±1% cap so a large
// real move never gets clipped). Optional noiseBand ({min,max} as fractions, e.g. {min:0.001,
// max:0.005} for ±0.1-0.5%) shades the "typical noise" range symmetrically on both sides for the
// playground's "is this move inside or outside typical noise" visual - unused (and thus a no-op)
// by the real result modal's existing call site.
function DivergingBar({ value, noiseBand }) {
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

// Hand-rolled donut (stroke-dasharray trick) rather than a charting library - this codebase has no
// existing Recharts usage anywhere despite it being a listed dependency (every other chart here,
// e.g. CandlestickSvgChart/Sparkline, is hand-rolled SVG), so this stays consistent with that.
function DonutRing({ percent, size = 120, strokeWidth = 14 }) {
  const p = typeof percent === 'number' ? Math.max(0, Math.min(100, percent)) : 0;
  const radius = (size - strokeWidth) / 2;
  const center = size / 2;
  const circumference = 2 * Math.PI * radius;
  const filled = (p / 100) * circumference;

  // Angle (degrees, SVG coordinate space) for a percent-around-the-ring position, matching the
  // -90deg rotation applied to the progress arc below so 0% sits at 12 o'clock, clockwise.
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
      {/* 50% (coin-flip) reference tick */}
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

// 0..1 p-value bar with a piecewise scale: the 0-0.05 "significant" zone is drawn at a fixed 25%
// of the bar's width regardless of how small it numerically is, so the 0.05 boundary and where the
// marker sits relative to it stay clearly visible instead of being compressed into an unreadable
// sliver by a strict linear 0..1 scale. Order-preserving (still strictly increasing), just not
// uniform - the numeric p-value is displayed as text alongside it either way.
function pValueToBarPercent(p) {
  const SIG_ZONE_WIDTH = 25;
  if (p <= 0.05) return (p / 0.05) * SIG_ZONE_WIDTH;
  return SIG_ZONE_WIDTH + ((p - 0.05) / 0.95) * (100 - SIG_ZONE_WIDTH);
}

function PValueBar({ pValue }) {
  const significant = pValue < 0.05;
  const markerPct = pValueToBarPercent(pValue);
  const zoneBoundary = pValueToBarPercent(0.05);

  return (
    <div className="mt-3">
      <div className="relative w-full h-5" style={{ background: 'var(--panel-2)' }}>
        <div
          className="absolute top-0 bottom-0 left-0"
          style={{ width: `${zoneBoundary}%`, background: 'rgba(53,214,184,0.15)' }}
        />
        <div
          className="absolute top-0 bottom-0"
          style={{ left: `${zoneBoundary}%`, right: 0, background: 'rgba(124,134,152,0.1)' }}
        />
        <div className="absolute top-0 bottom-0 w-px" style={{ left: `${zoneBoundary}%`, background: 'var(--hairline)' }} />
        <div
          className="absolute rounded-full"
          style={{
            left: `${markerPct}%`,
            top: '50%',
            width: 12,
            height: 12,
            transform: 'translate(-50%, -50%)',
            background: significant ? 'var(--positive)' : 'var(--text-dim)',
            border: '2px solid var(--panel)',
          }}
        />
      </div>
      <div className="flex justify-between text-xs mt-1" style={{ color: 'var(--text-faint)' }}>
        <span>0</span>
        <span style={{ position: 'relative', left: `-${50 - zoneBoundary}%` }}>0.05</span>
        <span>1</span>
      </div>
    </div>
  );
}

function CoverageBar({ percent }) {
  const p = typeof percent === 'number' ? Math.max(0, Math.min(100, percent)) : 0;
  return (
    <div className="w-full h-4 mt-3 relative" style={{ background: 'var(--panel-2)' }}>
      <div className="absolute top-0 bottom-0 left-0" style={{ width: `${p}%`, background: 'var(--accent)' }} />
    </div>
  );
}

function StatPanel({ label, children }) {
  return (
    <div className="border p-4" style={{ borderColor: 'var(--hairline)', background: 'var(--panel-2)' }}>
      <div className="text-sm font-semibold uppercase tracking-wide" style={{ color: 'var(--text-faint)' }}>{label}</div>
      {children}
    </div>
  );
}

// Five-tone chip language for the concept-guide's threshold tables and live classification labels
// - a superset of takeawayStyle's four tones ('strong-positive' and 'muted-negative' added) since
// the p-value/hit-rate tables both need two shades of green or two shades of red to distinguish
// e.g. "very unlikely to be chance" from "unlikely to be chance".
function toneChipStyle(tone) {
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

function classifyHitRate(pct) {
  if (pct < 45) return { label: 'Below coin-flip', tone: 'muted-negative' };
  if (pct <= 55) return { label: 'About a coin flip', tone: 'neutral' };
  return { label: 'Above coin-flip', tone: 'positive' };
}

function classifyPValue(p) {
  if (p < 0.01) return { label: 'Very unlikely to be chance', tone: 'strong-positive' };
  if (p < 0.05) return { label: 'Unlikely to be chance', tone: 'positive' };
  if (p <= 0.2) return { label: 'Plausibly chance', tone: 'neutral' };
  return { label: 'Very plausibly chance', tone: 'muted-negative' };
}

function classifyCoverage(pct) {
  if (pct < 30) return { label: 'Sparse — good for testing', tone: 'positive' };
  if (pct <= 70) return { label: 'Moderate', tone: 'neutral' };
  if (pct < 98) return { label: 'Dense', tone: 'warning' };
  return { label: 'Saturated — cannot test', tone: 'negative' };
}

const HIT_RATE_TABLE = [
  { range: '< 45%', label: 'Below coin-flip', tone: 'muted-negative' },
  { range: '45–55%', label: 'About a coin flip', tone: 'neutral' },
  { range: '> 55%', label: 'Above coin-flip', tone: 'positive' },
];
const PVALUE_TABLE = [
  { range: '< 0.01', label: 'Very unlikely to be chance', tone: 'strong-positive' },
  { range: '0.01–0.05', label: 'Unlikely to be chance', tone: 'positive' },
  { range: '0.05–0.20', label: 'Plausibly chance', tone: 'neutral' },
  { range: '> 0.20', label: 'Very plausibly chance', tone: 'muted-negative' },
];
const COVERAGE_TABLE = [
  { range: '< 30%', label: 'Sparse — good for testing', tone: 'positive' },
  { range: '30–70%', label: 'Moderate', tone: 'neutral' },
  { range: '70–98%', label: 'Dense', tone: 'warning' },
  { range: '≥ 98%', label: 'Saturated — cannot test', tone: 'negative' },
];

function ThresholdTable({ rows }) {
  return (
    <table className="w-full text-sm mt-3" style={{ borderCollapse: 'collapse' }}>
      <tbody>
        {rows.map((row) => (
          <tr key={row.range} className="border-t" style={{ borderColor: 'var(--hairline)' }}>
            <td className="font-mono-data py-2 pr-4 whitespace-nowrap" style={{ color: 'var(--text-dim)' }}>{row.range}</td>
            <td className="py-2">
              <span className="inline-block px-2.5 py-1 border" style={toneChipStyle(row.tone)}>{row.label}</span>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function PlaygroundSlider({ label, value, onChange, min, max, step, formatValue }) {
  return (
    <div className="flex items-center gap-3 mt-3">
      <span className="text-sm shrink-0 w-28" style={{ color: 'var(--text-faint)' }}>{label}</span>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="flex-1"
      />
      <span className="font-mono-data text-sm shrink-0 w-16 text-right" style={{ color: 'var(--text)' }}>
        {formatValue ? formatValue(value) : value}
      </span>
    </div>
  );
}

function ConceptSection({ title, question, explanation, table, table2, children }) {
  return (
    <div className="border p-5" style={{ borderColor: 'var(--hairline)', background: 'var(--panel-2)' }}>
      <div className="text-lg font-bold" style={{ color: 'var(--accent)' }}>{title}</div>
      <div className="text-base font-semibold mt-1" style={{ color: 'var(--text)' }}>{question}</div>
      <p className="text-sm mt-2 leading-relaxed" style={{ color: 'var(--text-dim)' }}>{explanation}</p>
      {table}
      {table2}
      <div className="mt-4 pt-3 border-t" style={{ borderColor: 'var(--hairline)' }}>
        <div className="text-sm font-semibold uppercase tracking-wide mb-1" style={{ color: 'var(--text-faint)' }}>
          Try it
        </div>
        {children}
      </div>
    </div>
  );
}

function HelpOverlayContent() {
  const [meanReturnPct, setMeanReturnPct] = useState(0.2);
  const [hitRatePct, setHitRatePct] = useState(50);
  const [pValue, setPValue] = useState(0.5);
  const [coveragePct, setCoveragePct] = useState(50);

  const hitRateClass = classifyHitRate(hitRatePct);
  const pValueClass = classifyPValue(pValue);
  const coverageClass = classifyCoverage(coveragePct);
  const noiseBand = { min: 0.001, max: 0.005 };
  const insideNoise = Math.abs(meanReturnPct / 100) <= noiseBand.max;

  return (
    <div className="space-y-4">
      <ConceptSection
        title="Mean Return"
        question="How big was the average move?"
        explanation="The average % price change across all matched independent event-days, over the chosen forward window. It's an average, not a guarantee - some days moved more, some less, some the other way."
        table={
          <div className="mt-3 p-3 text-sm border" style={{ borderColor: 'var(--hairline)', color: 'var(--text-faint)' }}>
            Typical daily stock noise is often ±0.1–0.5%. Size alone doesn't matter — only the p-value tells you if it's real.
          </div>
        }
      >
        <PlaygroundSlider
          label="Return"
          value={meanReturnPct}
          onChange={setMeanReturnPct}
          min={-2}
          max={2}
          step={0.05}
          formatValue={(v) => `${v > 0 ? '+' : ''}${v.toFixed(2)}%`}
        />
        <DivergingBar value={meanReturnPct / 100} noiseBand={noiseBand} />
        <div className="text-sm mt-2" style={{ color: 'var(--text-faint)' }}>
          Shaded band = typical noise range (±0.1–0.5%). Your value is currently{' '}
          <span style={{ color: insideNoise ? 'var(--text-dim)' : 'var(--positive)', fontWeight: 600 }}>
            {insideNoise ? 'inside' : 'outside'} typical noise
          </span>.
        </div>
      </ConceptSection>

      <ConceptSection
        title="Hit Rate"
        question="Did it usually go up?"
        explanation="The % of event-days that had any positive move, regardless of size. 50% is a coin flip - no better than random chance at guessing direction."
        table={<ThresholdTable rows={HIT_RATE_TABLE} />}
      >
        <PlaygroundSlider label="Hit rate" value={hitRatePct} onChange={setHitRatePct} min={0} max={100} step={1} formatValue={(v) => `${v}%`} />
        <div className="flex items-center gap-4 mt-2">
          <DonutRing percent={hitRatePct} size={100} strokeWidth={12} />
          <span className="inline-block px-3 py-1.5 border text-sm" style={toneChipStyle(hitRateClass.tone)}>{hitRateClass.label}</span>
        </div>
      </ConceptSection>

      <ConceptSection
        title="Bootstrap P-Value"
        question="Is this a real pattern, or just luck?"
        explanation="Compares the real average against thousands of random-day samples of the same size, and reports how often a random sample would look this extreme by chance alone. Lower = less likely to be chance."
        table={<ThresholdTable rows={PVALUE_TABLE} />}
      >
        <PlaygroundSlider label="p-value" value={pValue} onChange={setPValue} min={0} max={1} step={0.01} formatValue={(v) => v.toFixed(2)} />
        <PValueBar pValue={pValue} />
        <div className="mt-2">
          <span className="inline-block px-3 py-1.5 border text-sm" style={toneChipStyle(pValueClass.tone)}>{pValueClass.label}</span>
        </div>
      </ConceptSection>

      <ConceptSection
        title="Coverage"
        question="How often did this happen?"
        explanation="The % of trading days in your selected range that had at least one qualifying event. Near 100% means this event type is too broad/common to test meaningfully - there's no set of 'non-event' days left to compare against."
        table={<ThresholdTable rows={COVERAGE_TABLE} />}
      >
        <PlaygroundSlider label="Coverage" value={coveragePct} onChange={setCoveragePct} min={0} max={100} step={1} formatValue={(v) => `${v}%`} />
        <CoverageBar percent={coveragePct} />
        <div className="mt-2">
          <span className="inline-block px-3 py-1.5 border text-sm" style={toneChipStyle(coverageClass.tone)}>{coverageClass.label}</span>
        </div>
      </ConceptSection>
    </div>
  );
}

function HelpButton({ onClick }) {
  return (
    <button
      type="button"
      onClick={onClick}
      title="Concept guide"
      className="fixed bottom-6 right-6 z-40 w-11 h-11 rounded-full border font-mono-data font-bold flex items-center justify-center transition-colors"
      style={{ borderColor: 'var(--hairline)', color: 'var(--text-dim)', background: 'var(--panel)' }}
      onMouseEnter={(e) => {
        e.currentTarget.style.borderColor = 'var(--accent)';
        e.currentTarget.style.color = 'var(--accent)';
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.borderColor = 'var(--hairline)';
        e.currentTarget.style.color = 'var(--text-dim)';
      }}
    >
      ?
    </button>
  );
}

function ExplainModalContent({ w }) {
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

function WindowCard({ w, query }) {
  const [modalOpen, setModalOpen] = useState(false);
  const meanPositive = typeof w.meanReturn === 'number' && w.meanReturn >= 0;
  const badge = bootstrapBadge(w);

  const modalTitle = `${w.windowDays}-day window — ${query.eventType || 'any event type'} ${query.actor1CountryCode || 'any actor'}` +
    `${query.actor2CountryCode ? ' ↔ ' + query.actor2CountryCode : ''}`;

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

export default function EventStudyAnalysis() {
  const [form, setForm] = useState({
    eventType: 'Fight',
    actor1CountryCode: 'USA',
    actor2CountryCode: 'CHN',
    dateFrom: '2026-02-01',
    dateTo: '2026-08-01',
    basket: 'LMT,RTX,NOC,GD,BA',
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);
  // Captures the exact filter values that produced `result`, separate from the live-editable
  // `form` state - so a modal title doesn't drift if the form gets edited without re-running.
  const [lastQuery, setLastQuery] = useState(null);
  const [chartCandles, setChartCandles] = useState(null);
  const [chartLoading, setChartLoading] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);

  const basketList = useMemo(
    () => form.basket.split(',').map((s) => s.trim().toUpperCase()).filter(Boolean),
    [form.basket]
  );
  const chartSymbol = basketList[0] || null;

  const runAnalysis = async (e) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    setResult(null);
    setChartCandles(null);

    try {
      const body = {
        eventType: form.eventType.trim() || null,
        actor1CountryCode: form.actor1CountryCode.trim() || null,
        actor2CountryCode: form.actor2CountryCode.trim() || null,
        dateFrom: form.dateFrom,
        dateTo: form.dateTo,
        basket: basketList,
        windows: WINDOWS,
      };

      const res = await fetch('/api/analysis/event-study', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });

      if (!res.ok) {
        const errBody = await res.json().catch(() => ({}));
        throw new Error(errBody?.error || `Request failed (${res.status})`);
      }

      const data = await res.json();
      setResult(data);
      setLastQuery({
        eventType: body.eventType,
        actor1CountryCode: body.actor1CountryCode,
        actor2CountryCode: body.actor2CountryCode,
      });

      if (chartSymbol) {
        setChartLoading(true);
        try {
          const cRes = await fetch(
            `/api/analysis/price-history?ticker=${encodeURIComponent(chartSymbol)}&from=${form.dateFrom}&to=${form.dateTo}`
          );
          const cJson = await cRes.json();
          setChartCandles(Array.isArray(cJson) ? cJson : null);
        } catch {
          setChartCandles(null);
        } finally {
          setChartLoading(false);
        }
      }
    } catch (err) {
      setError(err.message || 'Analysis failed');
    } finally {
      setLoading(false);
    }
  };

  // price-history returns { date, open, high, low, close, volume } ascending by date;
  // CandlestickSvgChart expects { ts (epoch seconds), open, high, low, close }.
  const chartData = useMemo(() => {
    if (!Array.isArray(chartCandles)) return [];
    return chartCandles
      .map((p) => ({
        ts: Math.floor(new Date(`${p.date}T00:00:00Z`).getTime() / 1000),
        open: p.open,
        high: p.high,
        low: p.low,
        close: p.close,
      }))
      .filter((p) => p.open != null && p.close != null && p.high != null && p.low != null);
  }, [chartCandles]);

  // Dedupe by calendar date - the API's events[].eventDate is the raw GDELT event date, not the
  // resolved t0 trading-day baseline (t0 isn't exposed in the response). For weekday events (the
  // vast majority) these coincide; a weekend/holiday event's marker sits a day or two off from
  // its true t0. Good enough for this diagnostic view.
  const eventMarkers = useMemo(() => {
    if (!result?.events?.length) return [];
    const uniqueDates = new Set(result.events.map((ev) => ev.eventDate));
    return [...uniqueDates].map((d) => Math.floor(new Date(`${d}T00:00:00Z`).getTime() / 1000));
  }, [result]);

  return (
    <div className="h-full overflow-y-auto">
      <Panel className="p-5 mb-6">
        <h2 className="text-lg font-semibold mb-1" style={{ color: 'var(--text)' }}>Event-Study Analyzer</h2>
        <div className="text-xs mb-4" style={{ color: 'var(--text-faint)' }}>
          Diagnostic view — posts directly to /api/analysis/event-study. Not a polished feature yet.
        </div>

        <form onSubmit={runAnalysis} className="grid grid-cols-2 md:grid-cols-3 gap-3">
          <Field label="Event type">
            <select
              required
              value={form.eventType}
              onChange={(e) => setForm({ ...form, eventType: e.target.value })}
              className="font-mono-data border px-3 py-2 text-sm focus:outline-none"
              style={inputStyle}
            >
              {EVENT_TYPES.map((t) => (
                <option key={t} value={t}>{t}</option>
              ))}
            </select>
          </Field>

          <Field label="Actor 1 country (optional)">
            <CountryAutocomplete
              value={form.actor1CountryCode}
              onChange={(code) => setForm({ ...form, actor1CountryCode: code })}
              placeholder="e.g. United States"
            />
          </Field>

          <Field label="Actor 2 country (optional)">
            <CountryAutocomplete
              value={form.actor2CountryCode}
              onChange={(code) => setForm({ ...form, actor2CountryCode: code })}
              placeholder="e.g. China"
            />
          </Field>

          <Field label="Date from">
            <input
              type="date"
              value={form.dateFrom}
              onChange={(e) => setForm({ ...form, dateFrom: e.target.value })}
              className="font-mono-data border px-3 py-2 text-sm focus:outline-none"
              style={inputStyle}
            />
          </Field>

          <Field label="Date to">
            <input
              type="date"
              value={form.dateTo}
              onChange={(e) => setForm({ ...form, dateTo: e.target.value })}
              className="font-mono-data border px-3 py-2 text-sm focus:outline-none"
              style={inputStyle}
            />
          </Field>

          <Field label="Basket (comma-separated tickers)">
            <input
              value={form.basket}
              onChange={(e) => setForm({ ...form, basket: e.target.value })}
              className="font-mono-data border px-3 py-2 text-sm focus:outline-none"
              style={inputStyle}
            />
          </Field>

          <div className="col-span-2 md:col-span-3 flex items-center gap-3 mt-1">
            <button
              type="submit"
              disabled={loading || basketList.length === 0}
              className="font-mono-data font-semibold px-4 py-2 border transition-colors disabled:opacity-50"
              style={{ borderColor: 'var(--accent)', color: 'var(--accent)', background: 'var(--accent-soft)' }}
            >
              {loading ? 'Running…' : 'Run Analysis'}
            </button>
            <span className="text-xs" style={{ color: 'var(--text-faint)' }}>Windows fixed at +1 / +3 / +5 trading days</span>
          </div>
        </form>
      </Panel>

      {error && (
        <div
          className="mb-6 p-3 border text-sm"
          style={{ background: 'rgba(255,92,108,0.1)', borderColor: 'var(--negative)', color: 'var(--negative)' }}
        >
          {error}
        </div>
      )}

      {result && (
        <>
          <div className="mb-2 text-sm" style={{ color: 'var(--text-dim)' }}>
            {result.distinctEventCount} distinct dyad-events matched this filter (before price alignment).
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
            {result.summary.map((w) => (
              <WindowCard key={w.windowDays} w={w} query={lastQuery || {}} />
            ))}
          </div>

          <Panel className="p-4">
            <div className="flex items-center justify-between mb-2 gap-2">
              <div className="text-sm" style={{ color: 'var(--text-dim)' }}>
                {chartSymbol ? `${chartSymbol} — historical price_daily with event-date markers` : 'No ticker in basket'}
              </div>
              <div className="text-xs shrink-0" style={{ color: 'var(--text-faint)' }}>
                {eventMarkers.length} distinct event trading days
              </div>
            </div>

            <div className="h-[360px]">
              {chartLoading ? (
                <div className="h-full flex items-center justify-center text-sm" style={{ color: 'var(--text-dim)' }}>
                  Loading chart…
                </div>
              ) : (
                <CandlestickSvgChart chartData={chartData} markers={eventMarkers} />
              )}
            </div>
          </Panel>
        </>
      )}

      <HelpButton onClick={() => setHelpOpen(true)} />
      <Modal isOpen={helpOpen} onClose={() => setHelpOpen(false)} title="Event-Study concept guide">
        <HelpOverlayContent />
      </Modal>
    </div>
  );
}
