export function Sparkline({ values = [], width = 100, height = 28, color }) {
  if (!values.length) {
    return <svg width={width} height={height} />;
  }
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  const step = width / Math.max(1, values.length - 1);
  const points = values.map((v, i) => `${i * step},${height - ((v - min) / range) * height}`).join(' ');
  const trendColor = color || (values[values.length - 1] >= values[0] ? 'var(--positive)' : 'var(--negative)');

  return (
    <svg width={width} height={height}>
      <polyline points={points} fill="none" stroke={trendColor} strokeWidth="1.5" />
    </svg>
  );
}
