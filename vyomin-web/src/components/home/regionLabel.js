// Coarse, approximate lat/lon bucketing purely for the live feed's descriptive text (e.g.
// "Military track entered E. Mediterranean") - not a claim of authoritative geography, just a
// human-readable label for where on the globe a track roughly is.
const REGIONS = [
  { name: 'Arctic', test: (lat) => lat > 66.5 },
  { name: 'N. America', test: (lat, lon) => lat > 15 && lon < -50 },
  { name: 'S. America', test: (lat, lon) => lat <= 15 && lon < -30 },
  { name: 'W. Europe', test: (lat, lon) => lat > 35 && lon >= -12 && lon < 20 },
  { name: 'E. Europe', test: (lat, lon) => lat > 40 && lon >= 20 && lon < 40 },
  { name: 'E. Mediterranean', test: (lat, lon) => lat > 28 && lat <= 40 && lon >= 20 && lon < 45 },
  { name: 'Middle East', test: (lat, lon) => lat > 12 && lat <= 40 && lon >= 34 && lon < 63 },
  { name: 'Africa', test: (lat, lon) => lat <= 35 && lon >= -20 && lon < 52 },
  { name: 'S. Asia', test: (lat, lon) => lat > 5 && lat <= 38 && lon >= 60 && lon < 92 },
  { name: 'E. Asia', test: (lat, lon) => lat > 15 && lon >= 92 && lon < 150 },
  { name: 'Oceania', test: (lat) => lat <= 0 },
];

export function regionLabel(lat, lon) {
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return 'unknown airspace';
  const match = REGIONS.find((r) => r.test(lat, lon));
  return match ? match.name : 'open waters';
}
