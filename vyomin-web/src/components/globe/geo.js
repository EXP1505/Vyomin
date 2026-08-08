import { feature } from 'topojson-client';

const GLOBE_RADIUS = 2;

// Convert lat/lon (degrees) to a point on the globe sphere surface.
export function latLonToVector3(lat, lon, radius = GLOBE_RADIUS) {
  const phi = (90 - lat) * (Math.PI / 180);
  const theta = (lon + 180) * (Math.PI / 180);
  const x = -radius * Math.sin(phi) * Math.cos(theta);
  const y = radius * Math.cos(phi);
  const z = radius * Math.sin(phi) * Math.sin(theta);
  return [x, y, z];
}

export { GLOBE_RADIUS };

// Flattens a GeoJSON Polygon/MultiPolygon geometry into arrays of
// [lat, lon] ring loops, so each ring can be drawn as a closed line.
export function polygonRings(geometry) {
  if (!geometry) return [];
  if (geometry.type === 'Polygon') {
    return geometry.coordinates.map((ring) => ring.map(([lon, lat]) => [lat, lon]));
  }
  if (geometry.type === 'MultiPolygon') {
    return geometry.coordinates.flatMap((poly) => poly.map((ring) => ring.map(([lon, lat]) => [lat, lon])));
  }
  return [];
}

export function topoToCountryFeatures(topo) {
  const geo = feature(topo, topo.objects.countries);
  return geo.features;
}
