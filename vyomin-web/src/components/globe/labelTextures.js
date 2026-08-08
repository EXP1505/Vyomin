import * as THREE from 'three';

const cache = new Map();

// Renders a small dark-backed text chip so capital names stay legible against the
// grid lines/graticule behind them, instead of raw text bleeding into the background.
export function getLabelTexture(text) {
  if (cache.has(text)) return cache.get(text);

  const fontSize = 28;
  const paddingX = 14;
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  ctx.font = `${fontSize}px 'JetBrains Mono', monospace`;
  const textWidth = ctx.measureText(text).width;

  canvas.width = Math.ceil(textWidth + paddingX * 2);
  canvas.height = fontSize + 16;

  ctx.font = `${fontSize}px 'JetBrains Mono', monospace`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = '#7c8698';
  ctx.fillText(text, canvas.width / 2, canvas.height / 2);

  const texture = new THREE.CanvasTexture(canvas);
  texture.needsUpdate = true;
  const aspect = canvas.width / canvas.height;
  const entry = { texture, aspect };
  cache.set(text, entry);
  return entry;
}
