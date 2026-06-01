// Node-Harness: testet die JS-Detektion gegen die 2 Stream-Phasen.
import { PNG } from 'pngjs';
import fs from 'fs';
import { createRequire } from 'module';
const require = createRequire(import.meta.url);
const TP = require('./detection_v240.js');

function loadGray(path) {
  const png = PNG.sync.read(fs.readFileSync(path));
  const { width: W, height: H, data } = png;  // RGBA
  const gray = new Float32Array(W * H);
  for (let i = 0; i < W * H; i++) {
    // PNG ist grayscale gespeichert → R=G=B
    gray[i] = data[i * 4];
  }
  return { gray, W, H };
}

// Spiegel (aus Python-ROI-Detektion): ~ (784,515) r=229, ppm=2.29
const SP = { cx: 784, cy: 515, r: 229, pxPerMm: 2.29 };

const GT = {
  '1a': [{x:33,y:-76},{x:25,y:-46},{x:45,y:-6},{x:-30,y:10},{x:135.5,y:-54.7}],
  '1b': [{x:-74.9,y:-7.6},{x:28.6,y:-38.7},{x:26.1,y:20.2},{x:58.1,y:25.3},{x:5.9,y:69.0}],
};

function match(dets, gt, sp, mx = 15) {
  const pairs = [];
  dets.forEach((d, di) => gt.forEach((g, gi) => {
    const dmm = Math.hypot((d.x - sp.cx) / sp.pxPerMm - g.x, (d.y - sp.cy) / sp.pxPerMm - g.y);
    pairs.push([dmm, di, gi]);
  }));
  pairs.sort((a, b) => a[0] - b[0]);
  const ud = new Set(), ug = new Set(), ms = [];
  for (const [dist, di, gi] of pairs) {
    if (dist > mx) break;
    if (ud.has(di) || ug.has(gi)) continue;
    ud.add(di); ug.add(gi); ms.push(dist);
  }
  return { nMatch: ms.length, nFP: dets.length - ud.size, dists: ms };
}

console.log('JS v2.4.0 Stream-Detektion (Node-Test)\n');
let totGt = 0, totMatch = 0, totFP = 0, allDists = [];
for (const phase of ['1a', '1b']) {
  const ref = loadGray(`./${phase}_ref.png`);
  const post = loadGray(`./${phase}_post.png`);
  const t0 = Date.now();
  const { dets, shift } = TP.streamDetect(ref.gray, post.gray, ref.W, ref.H, SP, 5.6,
    { thrRel: 0.30, snrMin: 6, maxShift: 10 });
  const dt = Date.now() - t0;
  const gt = GT[phase];
  const m = match(dets, gt, SP);
  const med = m.dists.length ? m.dists.slice().sort((a,b)=>a-b)[m.dists.length>>1] : 0;
  console.log(`=== ${phase} ===  shift dx=${shift.dx} dy=${shift.dy}  (${dt}ms)`);
  console.log(`   ${dets.length} Det, ${m.nMatch}/${gt.length} match, ${m.nFP} FP, Recall=${(100*m.nMatch/gt.length).toFixed(0)}%, Δ=${med.toFixed(1)}mm`);
  totGt += gt.length; totMatch += m.nMatch; totFP += m.nFP; allDists.push(...m.dists);
}
const medAll = allDists.length ? allDists.slice().sort((a,b)=>a-b)[allDists.length>>1] : 0;
console.log('\n=== GESAMT (JS) ===');
console.log(`   Recall:    ${(100*totMatch/totGt).toFixed(0)}%  (${totMatch}/${totGt})`);
console.log(`   Precision: ${(100*totMatch/Math.max(1,totMatch+totFP)).toFixed(0)}%`);
console.log(`   Median-Δ:  ${medAll.toFixed(1)} mm`);
console.log(`   FP/Serie:  ${(totFP/2).toFixed(1)}`);
console.log('\n   Python-Referenz war: Recall 90%, Precision 90%, 2.7mm, 0.5 FP/Serie');
