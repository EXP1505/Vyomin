import { Component, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { Panel } from '../components/design/Panel';
import { CandlestickSvgChart } from '../components/StockDetailModal';
import { COUNTRIES, COUNTRY_CODE_TO_NAME } from '../data/countries';
import {
  significanceStyle, directionBadge, toneChipStyle, classifyPValue, PVALUE_CLASSIFICATIONS,
  Modal, DivergingBar, DonutRing, PValueBar, CoverageBar, WindowCard, ExplainModalContent,
} from '../components/analysis/windowSummary';

// No ErrorBoundary exists anywhere else in this app - without one, a render-time exception here
// (e.g. an unexpected field shape in a sweep response) throws past React with nothing catching
// it, and the whole page can end up back at a "coherent but wrong" state with zero visible signal
// that anything went wrong - which is indistinguishable from the request having silently done
// nothing. This scopes the blast radius to just the results panel and surfaces the real error.
class SweepResultsErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    console.error('Sweep results panel crashed while rendering:', error, info?.componentStack);
  }

  render() {
    if (this.state.error) {
      return (
        <div
          className="p-3 border text-sm"
          style={{ background: 'rgba(255,92,108,0.1)', borderColor: 'var(--negative)', color: 'var(--negative)' }}
        >
          The results panel crashed while rendering ({this.state.error.message || 'unknown error'}). The sweep itself
          completed — check the browser console for details.
        </div>
      );
    }
    return this.props.children;
  }
}

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

// Matches EventStudyService.DEFAULT_COUNTRY_PAIRS on the backend, in the same order - used to
// size the combination-count preview AND to name each simulated log line during the loading
// state (the sweep request itself never sends countryPairs; the backend applies this same list
// server-side). Purely presentational: if the backend's default list ever changes, this display
// copy would drift, but there's no API that exposes it to read back.
const SWEEP_DEFAULT_PAIRS = [
  ['USA', 'CHN'], ['RUS', 'UKR'], ['THA', 'KHM'], ['IND', 'PAK'], ['ISR', 'PSE'], ['ISR', 'IRN'],
  ['PRK', 'KOR'], ['ARM', 'AZE'], ['ETH', 'ERI'], ['SDN', 'SSD'], ['VEN', 'COL'], ['TWN', 'CHN'],
  ['USA', 'RUS'], ['USA', 'IRN'],
];
const SWEEP_DEFAULT_PAIR_COUNT = SWEEP_DEFAULT_PAIRS.length;

// Observed in backend testing: ~1.4s per combination (10,000-iteration bootstrap x up to 3
// windows). Drives both the runtime estimate shown in the live preview and the pacing of the
// simulated per-combination log reveal during loading (the backend returns one lump response,
// not incremental progress, so the reveal is client-side pacing dressed up to look live).
const SWEEP_MS_PER_COMBO = 1400;

const EVENT_TYPE_DESCRIPTIONS = {
  'Assault': 'A physical attack, short of open combat.',
  'Fight': 'Armed clashes or direct combat.',
  'Coerce': 'Threats or pressure tactics to force a change in behavior.',
  'Consult': 'Diplomatic meetings or talks.',
  'Demand': 'A formal demand made of another party.',
  'Engage in Diplomatic Cooperation': 'Positive diplomatic gestures — visits, agreements, praise.',
  'Engage in Material Cooperation': 'Aid, trade, or joint action between parties.',
  'Exhibit Force Posture': 'Military mobilization or show of force, without attacking.',
  'Express Intent to Cooperate': 'A stated willingness to work together.',
  'Protest': 'Public demonstrations.',
  'Provide Aid': 'Humanitarian or material assistance.',
  'Reduce Relations': 'Downgrading diplomatic or economic ties.',
  'Reject': 'Refusing a demand or proposal.',
  'Threaten': 'A verbal or written threat.',
  'Use Unconventional Mass Violence': 'Terrorism or mass-casualty violence.',
  'Yield': 'Making a concession or complying with a demand.',
};

const inputStyle = { background: 'var(--panel-2)', borderColor: 'var(--hairline)', color: 'var(--text)' };

function Field({ label, children }) {
  return (
    <label className="flex flex-col gap-1 text-xs" style={{ color: 'var(--text-dim)' }}>
      {label}
      {children}
    </label>
  );
}

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

// directionBadge/significanceStyle/bootstrapBadge/Modal/DivergingBar/DonutRing/PValueBar/
// CoverageBar/WindowCard/ExplainModalContent all now live in
// ../components/analysis/windowSummary.jsx (imported above) - shared with the country-dossier view.

// Step 1 of the sweep flow: one clickable card per event type (replaces a checkbox grid) - amber
// border/glow when selected, matching the app's existing accent language rather than inventing a
// new "selected" treatment.
function EventTypeCard({ type, selected, onToggle }) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-pressed={selected}
      className="text-left p-3 border transition-colors"
      style={{
        borderColor: selected ? 'var(--accent)' : 'var(--hairline)',
        background: selected ? 'var(--accent-soft)' : 'var(--panel-2)',
        boxShadow: selected ? '0 0 0 1px var(--accent)' : 'none',
      }}
    >
      <div className="text-sm font-semibold" style={{ color: selected ? 'var(--accent)' : 'var(--text)' }}>
        {type}
      </div>
      <div className="text-xs mt-1 leading-snug" style={{ color: 'var(--text-faint)' }}>
        {EVENT_TYPE_DESCRIPTIONS[type]}
      </div>
    </button>
  );
}

// Terminal/log-feed visual, matching Home's LiveSignalFeed aesthetic (monospace, accent ">"
// prefix, newest row animates in) - but appending downward and auto-scrolling to the bottom
// instead of prepending to the top, since this reads as a single running process finishing line
// by line rather than a merged multi-source feed.
// exhausted=true once every simulated row has been revealed but the real request still hasn't
// resolved (actual server time routinely runs past the SWEEP_MS_PER_COMBO estimate - proxy/DB
// load varies). Without a visible "still working" cue at that point, the panel just sits static
// on the last row indefinitely, which reads as hung and invites a refresh - discarding all
// component state and dropping the user back at the bare form with no error, no results, no clue
// the request actually succeeded server-side (it did; only the client gave up watching it).
function SweepLogFeed({ rows, exhausted }) {
  const containerRef = useRef(null);

  useEffect(() => {
    const el = containerRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [rows, exhausted]);

  return (
    <div ref={containerRef} className="h-48 overflow-y-auto font-mono-data text-xs pr-1">
      {rows.length === 0 && <div style={{ color: 'var(--text-faint)' }}>Initializing sweep…</div>}
      {rows.map((row, i) => (
        <div
          key={row.id}
          className="border-b py-1"
          style={{
            borderColor: 'var(--hairline)',
            color: 'var(--text-dim)',
            animation: !exhausted && i === rows.length - 1 ? 'vyomin-feed-in 0.3s ease-out' : undefined,
          }}
        >
          <span style={{ color: 'var(--accent)' }}>{'>'}</span> Testing {row.eventType}: {row.actor1} ↔ {row.actor2}...
        </div>
      ))}
      {exhausted && (
        <div className="py-2" style={{ color: 'var(--accent)', animation: 'vyomin-pulse-text 1.4s ease-in-out infinite' }}>
          <span style={{ color: 'var(--accent)' }}>{'>'}</span> Still aggregating results — real runtime can run past the
          estimate above, hang tight...
        </div>
      )}
      <style>{`
        @keyframes vyomin-pulse-text {
          0%, 100% { opacity: 1; }
          50% { opacity: 0.4; }
        }
        @keyframes vyomin-feed-in {
          0% { opacity: 0; transform: translateY(-4px); }
          100% { opacity: 1; transform: translateY(0); }
        }
      `}</style>
    </div>
  );
}

function classifyHitRate(pct) {
  if (pct < 45) return { label: 'Below coin-flip', tone: 'muted-negative' };
  if (pct <= 55) return { label: 'About a coin flip', tone: 'neutral' };
  return { label: 'Above coin-flip', tone: 'positive' };
}

// PVALUE_CLASSIFICATIONS/classifyPValue imported from ../components/analysis/windowSummary -
// PVALUE_TABLE below is derived from the shared PVALUE_CLASSIFICATIONS, same as before.

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
const PVALUE_TABLE = PVALUE_CLASSIFICATIONS.map(({ range, label, tone }) => ({ range, label, tone }));
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

// ExplainModalContent/WindowCard imported from ../components/analysis/windowSummary.

export default function EventStudyAnalysis() {
  // Lets the country-dossier view's "Open in full Analyzer" link pre-fill actor1CountryCode -
  // same navigate(path, { state }) + useLocation().state pattern IntelligenceGraphExplorer already
  // uses for its initialSearch, so this doesn't introduce a second navigation-state convention.
  const initial = useLocation().state;
  const [form, setForm] = useState({
    eventType: 'Fight',
    actor1CountryCode: initial?.actor1CountryCode ?? 'USA',
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

  const [sweepEventTypes, setSweepEventTypes] = useState(['Fight', 'Assault']);
  const [sweepLoading, setSweepLoading] = useState(false);
  const [sweepError, setSweepError] = useState(null);
  const [sweepResult, setSweepResult] = useState(null);
  // Client-side simulated progress log for the loading state - see runSweep/SWEEP_MS_PER_COMBO.
  const [sweepLogRows, setSweepLogRows] = useState([]);
  // true once every simulated combo has been revealed but the real fetch still hasn't resolved -
  // drives SweepLogFeed's "still aggregating" fallback line so the panel never reads as frozen.
  const [sweepLogExhausted, setSweepLogExhausted] = useState(false);
  // { row, w } - the clicked SweepResult plus its bestWindow's WindowSummary, fed straight into
  // the same ExplainModalContent the single-query WindowCard modal uses.
  const [sweepModalRow, setSweepModalRow] = useState(null);

  // The results panel lands well below the (already-tall) sweep form, off the bottom of the
  // viewport the user was looking at when they clicked Launch - confirmed via mount/state tracing
  // that the state update itself was landing correctly, the results were just rendering unseen.
  // Auto-scrolling to it the moment it appears makes that impossible to miss.
  const sweepResultsRef = useRef(null);
  useEffect(() => {
    if (sweepResult && sweepResultsRef.current) {
      sweepResultsRef.current.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, [sweepResult]);

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

  const toggleSweepEventType = (t) => {
    setSweepEventTypes((prev) => (prev.includes(t) ? prev.filter((x) => x !== t) : [...prev, t]));
  };

  // Step 2's live preview - recomputed on every card click, no button required.
  const sweepComboCount = sweepEventTypes.length * SWEEP_DEFAULT_PAIR_COUNT;
  const sweepEstimatedSeconds = Math.round((sweepComboCount * SWEEP_MS_PER_COMBO) / 1000);

  // Reuses form.dateFrom/dateTo/basket - the same state the single-query form above edits - so
  // there's exactly one date-range/basket to keep in sync, not two drifting copies. countryPairs
  // is omitted so the backend falls back to its own default list (v1: no custom-pair UI yet), but
  // that same default list is mirrored client-side as SWEEP_DEFAULT_PAIRS purely to name the
  // simulated log lines below with the real pairs the backend will actually test, in the same
  // eventType-outer/pair-inner order runSweep() iterates server-side.
  const runSweep = async (e) => {
    e.preventDefault();
    setSweepLoading(true);
    setSweepError(null);
    setSweepResult(null);
    setSweepModalRow(null);
    setSweepLogRows([]);
    setSweepLogExhausted(false);

    const combos = [];
    for (const eventType of sweepEventTypes) {
      for (const [actor1, actor2] of SWEEP_DEFAULT_PAIRS) {
        combos.push({ eventType, actor1, actor2 });
      }
    }

    // Reveals one combo per SWEEP_MS_PER_COMBO - paced to roughly track the real backend runtime,
    // not driven by actual progress (the endpoint returns one lump response). Real server time
    // routinely runs past this estimate (proxy/DB load varies), so once every combo's been shown
    // the interval stops itself and flips sweepLogExhausted - SweepLogFeed then shows a "still
    // aggregating" line instead of just sitting static, which otherwise reads as hung and invites
    // a page refresh that throws away all this component's state for nothing (the request is
    // still running server-side the whole time). The real fetch below clears the interval
    // regardless of which finishes first.
    let comboIndex = 0;
    let intervalId = null;
    const revealNext = () => {
      if (comboIndex >= combos.length) return;
      const c = combos[comboIndex];
      comboIndex += 1;
      setSweepLogRows((prev) => [...prev, { id: comboIndex, ...c }]);
      if (comboIndex >= combos.length) {
        setSweepLogExhausted(true);
        if (intervalId) clearInterval(intervalId);
      }
    };
    revealNext();
    intervalId = combos.length > 1 && comboIndex < combos.length ? setInterval(revealNext, SWEEP_MS_PER_COMBO) : null;

    try {
      const body = {
        eventTypes: sweepEventTypes,
        dateFrom: form.dateFrom,
        dateTo: form.dateTo,
        basket: basketList,
        windows: WINDOWS,
      };

      const res = await fetch('/api/analysis/event-study-sweep', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });

      if (!res.ok) {
        const errBody = await res.json().catch(() => ({}));
        throw new Error(errBody?.error || `Request failed (${res.status})`);
      }

      const data = await res.json();
      // Diagnostic only (kept in production too): this endpoint's runtime is genuinely variable
      // and hard to reproduce on demand, so if a future run ever again shows nothing despite the
      // request succeeding, this line is the way to tell "data never arrived intact" apart from
      // "data arrived fine but something after this point failed to render it".
      console.info('[sweep] response received', {
        totalCombinationsRun: data?.totalCombinationsRun,
        testableCombinationsCount: data?.testableCombinationsCount,
        resultsLength: Array.isArray(data?.results) ? data.results.length : 'not-an-array',
        correctedThreshold: data?.correctedThreshold,
      });
      setSweepResult(data);
    } catch (err) {
      console.error('[sweep] request failed', err);
      setSweepError(err.message || 'Sweep failed');
    } finally {
      if (intervalId) clearInterval(intervalId);
      setSweepLoading(false);
    }
  };

  // Backend already ranks by bestPValue ascending - re-sorted here too so the table's contract
  // ("sort by bestPValue ascending") doesn't silently depend on the backend never changing that.
  const sortedSweepResults = useMemo(
    () => (sweepResult?.results ? [...sweepResult.results].sort((a, b) => a.bestPValue - b.bestPValue) : []),
    [sweepResult]
  );

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
          Tests whether a geopolitical event type moved a stock basket, with statistical significance built in — not just a chart overlay.
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

      <Panel className="p-5 mb-6">
        <h2 className="text-lg font-semibold mb-1" style={{ color: 'var(--text)' }}>Batch Sweep</h2>
        <div className="text-xs mb-4" style={{ color: 'var(--text-faint)' }}>
          Runs the same analysis above across every combination of the selected event types × a
          default set of {SWEEP_DEFAULT_PAIR_COUNT} country pairs, then ranks the testable results
          and flags which survive Bonferroni correction for running many comparisons at once.
        </div>

        <form onSubmit={runSweep}>
          <div className="text-xs font-semibold uppercase tracking-wide mb-2" style={{ color: 'var(--text-faint)' }}>
            Step 1 — choose event types
          </div>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
            {EVENT_TYPES.map((t) => (
              <EventTypeCard key={t} type={t} selected={sweepEventTypes.includes(t)} onToggle={() => toggleSweepEventType(t)} />
            ))}
          </div>
          <div className="text-xs mt-2" style={{ color: 'var(--text-dim)' }}>
            {sweepEventTypes.length} event type{sweepEventTypes.length === 1 ? '' : 's'} selected.
          </div>

          <div className="text-xs font-semibold uppercase tracking-wide mt-5 mb-2" style={{ color: 'var(--text-faint)' }}>
            Step 2 — preview
          </div>
          {sweepEventTypes.length > 0 ? (
            <div className="text-sm font-mono-data p-3 border" style={{ borderColor: 'var(--hairline)', background: 'var(--panel-2)', color: 'var(--text)' }}>
              {sweepEventTypes.length} event type{sweepEventTypes.length === 1 ? '' : 's'} × {SWEEP_DEFAULT_PAIR_COUNT}{' '}
              country pairs = {sweepComboCount} combinations. Estimated runtime: ~{sweepEstimatedSeconds}s
            </div>
          ) : (
            <div className="text-xs" style={{ color: 'var(--text-faint)' }}>
              Select at least one event type above to preview how many combinations this would run.
            </div>
          )}

          <div className="text-xs font-semibold uppercase tracking-wide mt-5 mb-2" style={{ color: 'var(--text-faint)' }}>
            Step 3 — launch
          </div>
          <div className="text-xs mb-3" style={{ color: 'var(--text-faint)' }}>
            Using date range {form.dateFrom} → {form.dateTo} and basket{' '}
            {basketList.length ? basketList.join(', ') : '(none set)'} from the form above.
          </div>

          <div className="flex items-center gap-3">
            <button
              type="submit"
              disabled={sweepLoading || sweepEventTypes.length === 0 || basketList.length === 0}
              className="font-mono-data font-semibold px-4 py-2 border transition-colors disabled:opacity-50"
              style={{ borderColor: 'var(--accent)', color: 'var(--accent)', background: 'var(--accent-soft)' }}
            >
              {sweepLoading ? 'Running…' : 'Launch Investigation'}
            </button>
            {sweepEventTypes.length === 0 && (
              <span className="text-xs" style={{ color: 'var(--text-faint)' }}>Select at least one event type.</span>
            )}
          </div>
        </form>

        {sweepLoading && (
          <div className="mt-4 border p-3" style={{ borderColor: 'var(--hairline)', background: 'var(--panel-2)' }}>
            <div className="text-[10px] uppercase tracking-[0.2em] mb-2" style={{ color: 'var(--text-faint)' }}>
              / SWEEP LOG
            </div>
            <SweepLogFeed rows={sweepLogRows} exhausted={sweepLogExhausted} />
          </div>
        )}

        {sweepError && (
          <div
            className="mt-3 p-3 border text-sm"
            style={{ background: 'rgba(255,92,108,0.1)', borderColor: 'var(--negative)', color: 'var(--negative)' }}
          >
            {sweepError}
          </div>
        )}
      </Panel>

      {sweepResult && (
        <div ref={sweepResultsRef}>
        <SweepResultsErrorBoundary>
        <Panel className="p-5 mb-6">
          <div className="text-xs mb-3" style={{ color: 'var(--text-faint)' }}>
            Historical association only — not investment advice, and past patterns are not predictive of future price
            movement.
          </div>
          <div className="text-sm" style={{ color: 'var(--text)' }}>
            <span className="font-mono-data font-semibold">{sweepResult.totalCombinationsRun}</span> combinations
            tested, <span className="font-mono-data font-semibold">{sweepResult.testableCombinationsCount}</span>{' '}
            testable, corrected significance threshold:{' '}
            <span className="font-mono-data font-semibold">
              {typeof sweepResult.correctedThreshold === 'number' ? sweepResult.correctedThreshold.toFixed(5) : '—'}
            </span>
          </div>
          <div className="text-xs mt-1 mb-4" style={{ color: 'var(--text-faint)' }}>
            Bonferroni correction: since testing more combinations increases the chance of a false
            positive, the bar for "real" gets stricter the more you test at once. Corrected
            threshold = 0.05 ÷ {sweepResult.totalTestsForCorrection} tests.
          </div>

          {sortedSweepResults.length === 0 ? (
            <div className="text-sm" style={{ color: 'var(--text-faint)' }}>
              No testable combinations — every combination came back saturated or had no data.
            </div>
          ) : (
            <div>
              {sortedSweepResults.map((r, i) => {
                const bestWindowSummary = r.summary.find((w) => w.windowDays === r.bestWindow);
                const survives = r.survivesCorrection;
                const pText = typeof r.bestPValue === 'number' ? r.bestPValue.toFixed(4) : '—';
                // Only ever computed for survivors - see directionBadge's own comment for why.
                const direction = survives ? directionBadge(bestWindowSummary?.meanReturn) : null;
                // Three discrete tiers by rank, not a continuous gradient: rank 1 is a "hero" card,
                // ranks 2-5 a medium tier (still shows the mini bar), rank 6+ compact/text-only -
                // the bottom of a sweep is genuinely less important and should read as skimmable.
                const tier = i === 0 ? 'hero' : i <= 4 ? 'mid' : 'compact';
                // A visual label layer only - the ranking itself stays purely bestPValue-ascending
                // (no re-grouping), this just marks where the event type changes from the row above.
                const showGroupHeader = i === 0 || r.eventType !== sortedSweepResults[i - 1].eventType;

                return (
                  <div key={`${r.eventType}-${r.actor1}-${r.actor2}`}>
                    {showGroupHeader && (
                      <div
                        className="text-[10px] uppercase tracking-[0.2em]"
                        style={{ color: 'var(--text-faint)', marginTop: i === 0 ? 0 : '1.25rem', marginBottom: '0.4rem' }}
                      >
                        {r.eventType}
                      </div>
                    )}
                    <button
                      type="button"
                      onClick={() => bestWindowSummary && setSweepModalRow({ row: r, w: bestWindowSummary })}
                      className="w-full text-left border transition-colors block"
                      style={{
                        borderColor: survives ? 'var(--positive)' : tier === 'hero' ? 'var(--accent)' : 'var(--hairline)',
                        borderWidth: tier === 'hero' ? 2 : 1,
                        background: survives ? 'rgba(53,214,184,0.08)' : 'var(--panel-2)',
                        padding: tier === 'hero' ? '1.5rem 1.75rem' : tier === 'mid' ? '0.9rem 1.1rem' : '0.4rem 0.8rem',
                        marginBottom: tier === 'hero' ? '0.75rem' : tier === 'mid' ? '0.5rem' : '0.25rem',
                      }}
                    >
                      {tier === 'hero' && (
                        <div className="text-[10px] uppercase tracking-[0.2em] mb-2" style={{ color: 'var(--accent)' }}>
                          Closest to significant
                        </div>
                      )}
                      <div className="flex items-center justify-between gap-4">
                        <div className="min-w-0">
                          <div
                            style={{
                              color: tier === 'compact' ? 'var(--text-dim)' : 'var(--text)',
                              fontSize: tier === 'hero' ? '1.4rem' : tier === 'mid' ? '0.95rem' : '0.8rem',
                              fontWeight: tier === 'compact' ? 400 : 600,
                            }}
                          >
                            {r.eventType} — {r.actor1} ↔ {r.actor2}
                          </div>
                          <div
                            className="font-mono-data mt-0.5"
                            style={{
                              color: 'var(--text-faint)',
                              fontSize: tier === 'hero' ? '0.85rem' : tier === 'mid' ? '0.75rem' : '0.7rem',
                            }}
                          >
                            Best window +{r.bestWindow}d · p = {pText}
                          </div>
                        </div>
                        <div className="flex flex-col items-end gap-1.5 shrink-0">
                          <span
                            className="font-mono-data text-[11px] px-2 py-1 border shrink-0"
                            style={significanceStyle(survives)}
                          >
                            {survives ? 'Survives correction' : 'Does not survive'}
                          </span>
                          {direction && (
                            <span className="font-mono-data text-[11px] px-2 py-1 border text-right" style={direction.style}>
                              {direction.label}
                            </span>
                          )}
                        </div>
                      </div>

                      {tier !== 'compact' && typeof r.bestPValue === 'number' && (
                        <PValueBar
                          pValue={r.bestPValue}
                          correctedThreshold={sweepResult.correctedThreshold}
                          compact={tier === 'mid'}
                        />
                      )}
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </Panel>
        </SweepResultsErrorBoundary>
        </div>
      )}

      <Modal
        isOpen={!!sweepModalRow}
        onClose={() => setSweepModalRow(null)}
        title={
          sweepModalRow
            ? `${sweepModalRow.w.windowDays}-day window — ${sweepModalRow.row.eventType} ${sweepModalRow.row.actor1} ↔ ${sweepModalRow.row.actor2}`
            : ''
        }
      >
        {sweepModalRow && <ExplainModalContent w={sweepModalRow.w} />}
      </Modal>

      <HelpButton onClick={() => setHelpOpen(true)} />
      <Modal isOpen={helpOpen} onClose={() => setHelpOpen(false)} title="Event-Study concept guide">
        <HelpOverlayContent />
      </Modal>
    </div>
  );
}
