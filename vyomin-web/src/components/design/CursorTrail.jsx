import { useEffect, useRef } from 'react';

// Tactical-HUD cursor: fading tracer trail + lagging corner-bracket reticle,
// echoing the corner-bracket motif used on Panel. Canvas overlay, pointer-events
// disabled throughout so it never intercepts clicks.
const TRAIL_MS = 450;
const RETICLE_SIZE = 22;
const RETICLE_EASE = 0.22;
const LOCK_MS = 550;

export function CursorTrail() {
  const canvasRef = useRef(null);

  useEffect(() => {
    if (window.matchMedia('(pointer: coarse)').matches) return undefined;
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    const canvas = canvasRef.current;
    const ctx = canvas.getContext('2d');

    const accent = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || '#ffb020';
    const positive = getComputedStyle(document.documentElement).getPropertyValue('--positive').trim() || '#35d6b8';

    let dpr = Math.min(window.devicePixelRatio || 1, 2);
    const resize = () => {
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      canvas.width = window.innerWidth * dpr;
      canvas.height = window.innerHeight * dpr;
      canvas.style.width = window.innerWidth + 'px';
      canvas.style.height = window.innerHeight + 'px';
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    resize();
    window.addEventListener('resize', resize);

    const points = [];
    const locks = [];
    const raw = { x: window.innerWidth / 2, y: window.innerHeight / 2 };
    const eased = { x: raw.x, y: raw.y };
    let hasMoved = false;
    let lastMoveT = performance.now();

    const onMove = (e) => {
      raw.x = e.clientX;
      raw.y = e.clientY;
      hasMoved = true;
      lastMoveT = performance.now();
      points.push({ x: e.clientX, y: e.clientY, t: lastMoveT });
    };
    const onDown = (e) => {
      locks.push({ x: e.clientX, y: e.clientY, t: performance.now() });
    };
    window.addEventListener('mousemove', onMove, { passive: true });
    window.addEventListener('mousedown', onDown, { passive: true });

    let raf;
    const tick = () => {
      raf = requestAnimationFrame(tick);
      const now = performance.now();
      ctx.clearRect(0, 0, window.innerWidth, window.innerHeight);
      if (!hasMoved) return;

      // idle fade-out after a beat of no movement
      const idleFor = now - lastMoveT;
      const idleAlpha = idleFor < 400 ? 1 : Math.max(0, 1 - (idleFor - 400) / 600);
      if (idleAlpha <= 0 && points.length === 0 && locks.length === 0) return;

      eased.x += (raw.x - eased.x) * RETICLE_EASE;
      eased.y += (raw.y - eased.y) * RETICLE_EASE;

      // tracer trail
      while (points.length && now - points[0].t > TRAIL_MS) points.shift();
      if (points.length > 1 && !reduceMotion) {
        for (let i = 1; i < points.length; i++) {
          const p0 = points[i - 1];
          const p1 = points[i];
          const age = (now - p1.t) / TRAIL_MS;
          const alpha = (1 - age) * 0.55 * idleAlpha;
          if (alpha <= 0) continue;
          ctx.beginPath();
          ctx.moveTo(p0.x, p0.y);
          ctx.lineTo(p1.x, p1.y);
          ctx.strokeStyle = accent;
          ctx.globalAlpha = alpha;
          ctx.lineWidth = Math.max(0.5, 2.2 * (1 - age));
          ctx.lineCap = 'round';
          ctx.stroke();
        }
        ctx.globalAlpha = 1;
      }

      // lock-on ping rings on click
      for (let i = locks.length - 1; i >= 0; i--) {
        const l = locks[i];
        const age = now - l.t;
        if (age > LOCK_MS) {
          locks.splice(i, 1);
          continue;
        }
        const p = age / LOCK_MS;
        const r = 6 + p * 20;
        ctx.beginPath();
        ctx.arc(l.x, l.y, r, 0, Math.PI * 2);
        ctx.strokeStyle = positive;
        ctx.globalAlpha = (1 - p) * 0.8;
        ctx.lineWidth = 1.4;
        ctx.stroke();
      }
      ctx.globalAlpha = 1;

      // lagging corner-bracket reticle
      const s = RETICLE_SIZE / 2;
      const armX = s * 0.55;
      const armY = s * 0.55;
      ctx.globalAlpha = 0.85 * idleAlpha;
      ctx.strokeStyle = accent;
      ctx.lineWidth = 1.3;
      ctx.shadowColor = accent;
      ctx.shadowBlur = 6;
      const corners = [
        [-1, -1], [1, -1], [-1, 1], [1, 1],
      ];
      corners.forEach(([dx, dy]) => {
        const cx = eased.x + dx * s;
        const cy = eased.y + dy * s;
        ctx.beginPath();
        ctx.moveTo(cx, cy - dy * armY);
        ctx.lineTo(cx, cy);
        ctx.lineTo(cx - dx * armX, cy);
        ctx.stroke();
      });
      ctx.shadowBlur = 0;

      // center dot
      ctx.beginPath();
      ctx.arc(eased.x, eased.y, 1.4, 0, Math.PI * 2);
      ctx.fillStyle = accent;
      ctx.globalAlpha = 0.9 * idleAlpha;
      ctx.fill();
      ctx.globalAlpha = 1;
    };
    raf = requestAnimationFrame(tick);

    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener('resize', resize);
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mousedown', onDown);
    };
  }, []);

  return (
    <canvas
      ref={canvasRef}
      className="fixed inset-0 z-[999]"
      style={{ pointerEvents: 'none' }}
      aria-hidden="true"
    />
  );
}