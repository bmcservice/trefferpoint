// TrefferPoint Detection v2.4.0 — pure JS (Browser + Node), kein OpenCV.
// Portierung der Python-Referenz. Arbeitet auf Float32/Uint8-Graustufen-Arrays.
//
// Kernfunktionen:
//   registerTranslation(ref, post, W, H, maxShift)  -> {dx,dy, aligned}
//   detectOnDiff(diffSigned, W, H, spiegel, kaliberMm, opts) -> [{x,y,score,snr,zone}]
//   centerSurround via Integral-Image (ersetzt Template-NCC, O(N))
//
// "spiegel" = {cx, cy, r, pxPerMm}

'use strict';

// ---- Integral Image (Summed Area Table) ----
function integralImage(arr, W, H) {
  // (W+1)*(H+1) Float64
  const I = new Float64Array((W + 1) * (H + 1));
  for (let y = 0; y < H; y++) {
    let rowsum = 0;
    for (let x = 0; x < W; x++) {
      rowsum += arr[y * W + x];
      I[(y + 1) * (W + 1) + (x + 1)] = I[y * (W + 1) + (x + 1)] + rowsum;
    }
  }
  return I;
}
function boxSum(I, W, x0, y0, x1, y1) {
  // inklusive [x0,x1]×[y0,y1], in Integral-Koords (+1)
  const Wp = W + 1;
  return I[(y1 + 1) * Wp + (x1 + 1)] - I[y0 * Wp + (x1 + 1)]
       - I[(y1 + 1) * Wp + x0] + I[y0 * Wp + x0];
}
function boxMean(I, W, H, cx, cy, rad) {
  const x0 = Math.max(0, cx - rad), y0 = Math.max(0, cy - rad);
  const x1 = Math.min(W - 1, cx + rad), y1 = Math.min(H - 1, cy + rad);
  const n = (x1 - x0 + 1) * (y1 - y0 + 1);
  return n > 0 ? boxSum(I, W, x0, y0, x1, y1) / n : 0;
}

// ---- Translation-Registration (coarse-to-fine, JS-portierbar) ----
function _mseAt(ref, post, W, H, dx, dy, sub) {
  const cy0 = (H >> 1), cx0 = (W >> 1);
  const rh = (H / 3) | 0, rw = (W / 3) | 0;
  let sse = 0, n = 0;
  for (let y = cy0 - rh; y < cy0 + rh; y += sub) {
    const ry = y + dy;
    if (ry < 0 || ry >= H) return Infinity;
    for (let x = cx0 - rw; x < cx0 + rw; x += sub) {
      const rx = x + dx;
      if (rx < 0 || rx >= W) return Infinity;
      const d = post[y * W + x] - ref[ry * W + rx];
      sse += d * d; n++;
    }
  }
  return n > 0 ? sse / n : Infinity;
}
function registerTranslation(ref, post, W, H, maxShift = 10) {
  let best = { mse: Infinity, dx: 0, dy: 0 };
  // Phase 1: grob, step 2, subsample 4
  for (let dy = -maxShift; dy <= maxShift; dy += 2)
    for (let dx = -maxShift; dx <= maxShift; dx += 2) {
      const mse = _mseAt(ref, post, W, H, dx, dy, 4);
      if (mse < best.mse) best = { mse, dx, dy };
    }
  // Phase 2: fein um bestes ±1, subsample 2
  const c = { ...best }; best.mse = Infinity;
  for (let dy = c.dy - 1; dy <= c.dy + 1; dy++)
    for (let dx = c.dx - 1; dx <= c.dx + 1; dx++) {
      const mse = _mseAt(ref, post, W, H, dx, dy, 2);
      if (mse < best.mse) best = { mse, dx, dy };
    }
  // aligned ref erzeugen (shift ref um (dx,dy) so dass es auf post passt)
  const aligned = new Float32Array(W * H);
  const { dx, dy } = best;
  for (let y = 0; y < H; y++) {
    const ry = y + dy;
    for (let x = 0; x < W; x++) {
      const rx = x + dx;
      aligned[y * W + x] = (rx >= 0 && rx < W && ry >= 0 && ry < H) ? ref[ry * W + rx] : post[y * W + x];
    }
  }
  return { dx: best.dx, dy: best.dy, aligned };
}

// ---- Center-Surround-Detektor auf einem Signal-Feld ----
// score = mean(inner r) - mean(outer 2.5r). Hoch = kompakter Peak der Größe r.
function centerSurround(signal, W, H, rInner) {
  const I = integralImage(signal, W, H);
  const rOuter = Math.max(rInner + 2, Math.round(rInner * 2.5));
  const score = new Float32Array(W * H);
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const inner = boxMean(I, W, H, x, y, rInner);
      const outer = boxMean(I, W, H, x, y, rOuter);
      score[y * W + x] = inner - outer;
    }
  }
  return score;
}

// ---- lokale Maxima finden ----
function localMaxima(score, W, H, thr, minDist) {
  const cand = [];
  const r = Math.max(1, minDist | 0);
  for (let y = r; y < H - r; y++) {
    for (let x = r; x < W - r; x++) {
      const v = score[y * W + x];
      if (v < thr) continue;
      let isMax = true;
      for (let dy = -r; dy <= r && isMax; dy++)
        for (let dx = -r; dx <= r; dx++) {
          if (score[(y + dy) * W + (x + dx)] > v) { isMax = false; break; }
        }
      if (isMax) cand.push({ x, y, score: v });
    }
  }
  return cand;
}

// ---- NMS ----
function nms(dets, radius) {
  dets.sort((a, b) => b.score - a.score);
  const keep = [];
  for (const d of dets) {
    let ok = true;
    for (const k of keep) {
      if (Math.hypot(d.x - k.x, d.y - k.y) < radius) { ok = false; break; }
    }
    if (ok) keep.push(d);
  }
  return keep;
}

// ---- Haupt-Detektion auf signiertem Frame-Diff ----
function detectOnDiff(diffSigned, W, H, spiegel, kaliberMm, opts = {}) {
  const thrRel = opts.thrRel ?? 0.30;   // relativ zum Score-Max
  const snrMin = opts.snrMin ?? 6;
  const ppm = spiegel.pxPerMm;
  const rInner = Math.max(2, Math.round(kaliberMm * 0.5 * ppm));
  const nmsR = Math.max(4, Math.round(1.5 * kaliberMm * ppm));

  // Signal-Felder: Spiegel = positiv (Loch heller), Außen = negativ
  const posSig = new Float32Array(W * H);
  const negSig = new Float32Array(W * H);
  for (let i = 0; i < W * H; i++) {
    const d = diffSigned[i];
    posSig[i] = d > 0 ? d : 0;
    negSig[i] = d < 0 ? -d : 0;
  }
  const scoreIn = centerSurround(posSig, W, H, rInner);
  const scoreOut = centerSurround(negSig, W, H, rInner);

  // Zonen-Masken via Radius
  const { cx, cy, r } = spiegel;
  function inZone(x, y, zone) {
    const dd = Math.hypot(x - cx, y - cy);
    if (zone === 'in') return dd < r * 0.93;
    return dd > r * 1.05 && dd < r * 2.4;
  }

  // globale Maxima für relative Schwelle
  let maxIn = 0, maxOut = 0;
  for (let i = 0; i < W * H; i++) { if (scoreIn[i] > maxIn) maxIn = scoreIn[i]; if (scoreOut[i] > maxOut) maxOut = scoreOut[i]; }

  let cand = [];
  for (const c of localMaxima(scoreIn, W, H, thrRel * maxIn, rInner)) {
    if (inZone(c.x, c.y, 'in')) cand.push({ ...c, zone: 'in' });
  }
  for (const c of localMaxima(scoreOut, W, H, thrRel * maxOut, rInner)) {
    if (inZone(c.x, c.y, 'out')) cand.push({ ...c, zone: 'out' });
  }
  cand = nms(cand, nmsR);

  // SNR-Verify: lokaler |diff|-Peak vs globaler |diff|-Median
  const absSorted = Float64Array.from(diffSigned, v => Math.abs(v));
  absSorted.sort();
  const noise = (absSorted[absSorted.length >> 1] || 0) + 1e-6;
  const out = [];
  for (const c of cand) {
    const rr = Math.max(1, Math.round(rInner * 1.5));
    let peak = 0;
    for (let dy = -rr; dy <= rr; dy++)
      for (let dx = -rr; dx <= rr; dx++) {
        const yy = c.y + dy, xx = c.x + dx;
        if (yy < 0 || yy >= H || xx < 0 || xx >= W) continue;
        const a = Math.abs(diffSigned[yy * W + xx]);
        if (a > peak) peak = a;
      }
    const snr = peak / noise;
    if (snr < snrMin) continue;
    out.push({ x: c.x, y: c.y, score: c.score, snr, zone: c.zone });
  }
  return out;
}

// ---- Stream-Detektion: ref+post Graustufen → Detektionen ----
function streamDetect(ref, post, W, H, spiegel, kaliberMm, opts = {}) {
  const doReg = opts.register !== false;
  let aligned = ref, shift = { dx: 0, dy: 0 };
  if (doReg) {
    const r = registerTranslation(ref, post, W, H, opts.maxShift ?? 10);
    aligned = r.aligned; shift = { dx: r.dx, dy: r.dy };
  }
  const diff = new Float32Array(W * H);
  for (let i = 0; i < W * H; i++) diff[i] = post[i] - aligned[i];
  const dets = detectOnDiff(diff, W, H, spiegel, kaliberMm, opts);
  return { dets, shift };
}

// Browser: window-Export; Node: module.exports
if (typeof module !== 'undefined' && module.exports) {
  module.exports = { registerTranslation, detectOnDiff, streamDetect, centerSurround, integralImage };
} else if (typeof window !== 'undefined') {
  window.TPDetect = { registerTranslation, detectOnDiff, streamDetect };
}
