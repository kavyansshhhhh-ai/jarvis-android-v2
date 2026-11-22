import React, { useEffect, useRef } from 'react';
import { Phase } from '../lib/types';

interface OrbProps {
  phase: Phase;
  level?: number;
  size?: number;
  isDark?: boolean;
  label?: string;
  onClick?: () => void;
}

const POINTS = 100;

export const Orb: React.FC<OrbProps> = ({
  phase,
  level = 0,
  size = 190,
  isDark = true,
  label = 'JARVIS Orb',
  onClick,
}) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const phaseRef = useRef(phase);
  const levelRef = useRef(level);
  const darkRef = useRef(isDark);

  phaseRef.current = phase;
  levelRef.current = level;
  darkRef.current = isDark;

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const prefersReduced =
      typeof window !== 'undefined' &&
      window.matchMedia &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    let rafId = 0;
    let currentEnergy = 0.05;
    let currentVoice = 0;
    const start = performance.now();

    const targetEnergy = (p: Phase) => {
      switch (p) {
        case 'Off':
          return 0.05;
        case 'Connecting':
        case 'Processing':
          return 0.35;
        case 'Listening':
          return 0.55;
        case 'Speaking':
          return 0.95;
      }
    };

    const draw = (now: number) => {
      const dpr = window.devicePixelRatio || 1;
      const pxSize = size * dpr;
      if (canvas.width !== pxSize || canvas.height !== pxSize) {
        canvas.width = pxSize;
        canvas.height = pxSize;
      }

      ctx.save();
      ctx.scale(dpr, dpr);
      ctx.clearRect(0, 0, size, size);

      const p = phaseRef.current;
      const dark = darkRef.current;
      const accent = dark ? '#38E5FF' : '#006C7B';
      const accentRgb = dark ? '56, 229, 255' : '0, 108, 123';
      const violetRgb = dark ? '157, 101, 255' : '112, 68, 174';
      const pinkRgb = dark ? '244, 114, 182' : '181, 47, 120';

      currentEnergy += (targetEnergy(p) - currentEnergy) * 0.08;
      currentVoice += (Math.max(0, Math.min(1, levelRef.current)) - currentVoice) * 0.22;

      const t = prefersReduced || p === 'Off' ? 0 : ((now - start) % 10000) / 10000;
      const a = t * Math.PI * 2;
      const cx = size / 2;
      const cy = size / 2;
      const staticBase = (size / 2) * 0.58;
      const base = staticBase * (1 + 0.08 * currentVoice);
      const wobble = 0.025 + 0.06 * currentEnergy + 0.12 * currentVoice;

      // 1. Multi-layered Liquid Ambient Glow
      const glowRadius = base * 2.15;
      const glow = ctx.createRadialGradient(cx, cy, base * 0.2, cx, cy, glowRadius);
      glow.addColorStop(0, `rgba(${accentRgb}, ${(0.28 + 0.25 * currentVoice + 0.12 * currentEnergy).toFixed(3)})`);
      glow.addColorStop(0.55, `rgba(${violetRgb}, ${(0.14 + 0.1 * currentEnergy).toFixed(3)})`);
      glow.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = glow;
      ctx.beginPath();
      ctx.arc(cx, cy, glowRadius, 0, Math.PI * 2);
      ctx.fill();

      // 2. Liquid Glass Silhouette Path
      const buildSilhouette = () => {
        ctx.beginPath();
        for (let i = 0; i <= POINTS; i++) {
          const th = (i * 2 * Math.PI) / POINTS;
          const r =
            base *
            (1 +
              wobble *
                (0.55 * Math.sin(3 * th + 3 * a) +
                  0.3 * Math.sin(5 * th - 2 * a) +
                  0.15 * Math.sin(2 * th + 5 * a)));
          const x = cx + r * Math.cos(th);
          const y = cy + r * Math.sin(th);
          if (i === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        }
        ctx.closePath();
      };

      // 3. Clipped Fluid Liquid Body
      ctx.save();
      buildSilhouette();
      ctx.clip();

      const subGrad = ctx.createRadialGradient(cx, cy, 2, cx, cy, base * 1.3);
      subGrad.addColorStop(0, '#388BFF');
      subGrad.addColorStop(1, '#0F1B42');
      ctx.fillStyle = subGrad;
      ctx.fillRect(cx - base * 1.4, cy - base * 1.4, base * 2.8, base * 2.8);

      const spotColors = [accentRgb, violetRgb, pinkRgb];
      spotColors.forEach((rgb, i) => {
        const angle = a * (i + 1) * (i % 2 === 0 ? 1 : -1);
        const sx = cx + base * 0.45 * Math.cos(angle + i * 2.1);
        const sy = cy + base * 0.45 * Math.sin(angle + i * 2.1);
        const sg = ctx.createRadialGradient(sx, sy, 1, sx, sy, base * 1.05);
        sg.addColorStop(0, `rgba(${rgb}, 0.85)`);
        sg.addColorStop(1, `rgba(${rgb}, 0)`);
        ctx.fillStyle = sg;
        ctx.beginPath();
        ctx.arc(cx, cy, base * 1.3, 0, Math.PI * 2);
        ctx.fill();
      });

      // Top-left specular reflection
      const specX = cx - staticBase * 0.35;
      const specY = cy - staticBase * 0.42;
      const spec = ctx.createRadialGradient(specX, specY, 1, specX, specY, staticBase * 0.9);
      spec.addColorStop(0, 'rgba(255, 255, 255, 0.65)');
      spec.addColorStop(1, 'rgba(255, 255, 255, 0)');
      ctx.fillStyle = spec;
      ctx.beginPath();
      ctx.arc(cx, cy, base * 1.3, 0, Math.PI * 2);
      ctx.fill();

      // Bottom caustic highlight
      const causX = cx;
      const causY = cy + staticBase * 0.4;
      const caus = ctx.createRadialGradient(causX, causY, 1, causX, causY, staticBase * 0.95);
      caus.addColorStop(0, 'rgba(56, 229, 255, 0.35)');
      caus.addColorStop(1, 'rgba(56, 229, 255, 0)');
      ctx.fillStyle = caus;
      ctx.beginPath();
      ctx.arc(cx, cy, base * 1.15, 0, Math.PI * 2);
      ctx.fill();

      ctx.restore();

      // 4. Liquid Glass Rim Contour
      buildSilhouette();
      const rimGrad = ctx.createLinearGradient(cx - base, cy - base, cx + base, cy + base);
      rimGrad.addColorStop(0, 'rgba(255, 255, 255, 0.68)');
      rimGrad.addColorStop(0.5, accent);
      rimGrad.addColorStop(1, `rgba(${violetRgb}, 0.55)`);
      ctx.strokeStyle = rimGrad;
      ctx.lineWidth = 1.6;
      ctx.stroke();

      // 5. Dual Orbital Rings during Connecting / Processing
      if (p === 'Connecting' || p === 'Processing') {
        const ring1 = base * 1.34;
        const ring2 = base * 1.48;

        ctx.save();
        ctx.translate(cx, cy);
        ctx.rotate(t * Math.PI * 2 * 6);
        ctx.strokeStyle = accent;
        ctx.lineWidth = 2.5;
        ctx.lineCap = 'round';
        ctx.beginPath();
        ctx.arc(0, 0, ring1, 0, (130 * Math.PI) / 180);
        ctx.stroke();
        ctx.restore();

        ctx.save();
        ctx.translate(cx, cy);
        ctx.rotate(-t * Math.PI * 2 * 4);
        ctx.strokeStyle = `rgb(${violetRgb})`;
        ctx.lineWidth = 1.8;
        ctx.lineCap = 'round';
        ctx.beginPath();
        ctx.arc(0, 0, ring2, Math.PI, Math.PI + (90 * Math.PI) / 180);
        ctx.stroke();
        ctx.restore();
      }

      ctx.restore();

      if (p !== 'Off' && !prefersReduced) {
        rafId = requestAnimationFrame(draw);
      }
    };

    rafId = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(rafId);
  }, [phase, size, isDark]);

  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      style={{ width: size, height: size }}
      className="relative flex items-center justify-center rounded-full focus-visible:outline-2 focus-visible:outline-cyan-400 cursor-pointer transition-transform duration-150 active:scale-95"
    >
      <canvas ref={canvasRef} style={{ width: size, height: size }} className="block" />
    </button>
  );
};
