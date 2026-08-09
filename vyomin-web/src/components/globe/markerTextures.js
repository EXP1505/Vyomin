import * as THREE from 'three';

const SIZE = 96;
const CENTER = SIZE / 2;
const cache = new Map();

function glow(ctx, color) {
  const gradient = ctx.createRadialGradient(CENTER, CENTER, 6, CENTER, CENTER, CENTER * 0.7);
  gradient.addColorStop(0, `${color}30`);
  gradient.addColorStop(1, `${color}00`);
  ctx.fillStyle = gradient;
  ctx.fillRect(0, 0, SIZE, SIZE);
}

function drawMilitary(ctx, color) {
  // Bold, simplified jet dart — reads clearly even at the small on-screen size
  // markers render at, unlike a finely-detailed delta-wing silhouette.
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(48, 14);
  ctx.lineTo(62, 52);
  ctx.lineTo(48, 46);
  ctx.lineTo(34, 52);
  ctx.closePath();
  ctx.fill();
  ctx.beginPath();
  ctx.moveTo(48, 40);
  ctx.lineTo(56, 76);
  ctx.lineTo(48, 68);
  ctx.lineTo(40, 76);
  ctx.closePath();
  ctx.fill();
}

function drawCargo(ctx, color) {
  // Boxier plane outline — a simple cross/plus silhouette, bold enough to read small.
  ctx.strokeStyle = color;
  ctx.lineWidth = 5;
  ctx.lineJoin = 'round';
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.moveTo(48, 12);
  ctx.lineTo(48, 82);
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(18, 46);
  ctx.lineTo(78, 46);
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(38, 74);
  ctx.lineTo(58, 74);
  ctx.stroke();
}

function drawHelicopter(ctx, color) {
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.lineWidth = 4;
  ctx.beginPath();
  ctx.moveTo(20, 24);
  ctx.lineTo(76, 24);
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(48, 16);
  ctx.lineTo(48, 34);
  ctx.stroke();
  ctx.beginPath();
  ctx.roundRect(36, 34, 24, 32, 8);
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(48, 66);
  ctx.lineTo(48, 80);
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(36, 78);
  ctx.lineTo(60, 78);
  ctx.stroke();
}

function drawDrone(ctx, color) {
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.lineWidth = 4;
  const arms = [
    [28, 28],
    [68, 28],
    [28, 68],
    [68, 68],
  ];
  for (const [x, y] of arms) {
    ctx.beginPath();
    ctx.arc(x, y, 11, 0, Math.PI * 2);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(48, 48);
    ctx.lineTo(x, y);
    ctx.stroke();
  }
  ctx.beginPath();
  ctx.rect(40, 40, 16, 16);
  ctx.fill();
}

function drawUnknown(ctx, color) {
  ctx.strokeStyle = color;
  ctx.lineWidth = 4;
  ctx.setLineDash([6, 6]);
  ctx.beginPath();
  ctx.moveTo(48, 12);
  ctx.lineTo(84, 48);
  ctx.lineTo(48, 84);
  ctx.lineTo(12, 48);
  ctx.closePath();
  ctx.stroke();
}

const DRAWERS = {
  MILITARY: drawMilitary,
  CARGO: drawCargo,
  HELICOPTER: drawHelicopter,
  DRONE: drawDrone,
  PRIVATE: drawCargo,
  UNKNOWN: drawUnknown,
};

// Pulsing amber ring used for conflict/event flashpoints - scale/opacity animated per-frame
// by ConflictMarkers, this texture itself is just a static glow + ring stamp.
export function getRingTexture(color) {
  const key = `ring:${color}`;
  if (cache.has(key)) return cache.get(key);

  const canvas = document.createElement('canvas');
  canvas.width = SIZE;
  canvas.height = SIZE;
  const ctx = canvas.getContext('2d');
  ctx.clearRect(0, 0, SIZE, SIZE);
  glow(ctx, color);
  ctx.strokeStyle = color;
  ctx.lineWidth = 5;
  ctx.beginPath();
  ctx.arc(CENTER, CENTER, CENTER * 0.55, 0, Math.PI * 2);
  ctx.stroke();

  const texture = new THREE.CanvasTexture(canvas);
  texture.needsUpdate = true;
  cache.set(key, texture);
  return texture;
}

export function getMarkerTexture(type, color) {
  const key = `${type}:${color}`;
  if (cache.has(key)) return cache.get(key);

  const canvas = document.createElement('canvas');
  canvas.width = SIZE;
  canvas.height = SIZE;
  const ctx = canvas.getContext('2d');
  ctx.clearRect(0, 0, SIZE, SIZE);
  glow(ctx, color);
  (DRAWERS[type] || drawUnknown)(ctx, color);

  const texture = new THREE.CanvasTexture(canvas);
  texture.needsUpdate = true;
  cache.set(key, texture);
  return texture;
}
