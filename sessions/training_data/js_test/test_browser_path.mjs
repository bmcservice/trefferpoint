// Testet den EXAKTEN Browser-Code-Pfad: PNG→RGBA-ImageData→imageDataToGray→streamDetect.
// (im Browser kommt das ImageData von canvas.getImageData; hier von pngjs — identische Shape)
import { PNG } from 'pngjs';
import fs from 'fs';
import { createRequire } from 'module';
const require = createRequire(import.meta.url);
const TP = require('./detection_v240.js');

function loadImageData(path) {
  const png = PNG.sync.read(fs.readFileSync(path));
  return { data: png.data, width: png.width, height: png.height };  // RGBA, = canvas ImageData
}

const SP = { cx: 784, cy: 515, r: 229, pxPerMm: 2.29 };
const GT = {
  '1a': [{x:33,y:-76},{x:25,y:-46},{x:45,y:-6},{x:-30,y:10},{x:135.5,y:-54.7}],
  '1b': [{x:-74.9,y:-7.6},{x:28.6,y:-38.7},{x:26.1,y:20.2},{x:58.1,y:25.3},{x:5.9,y:69.0}],
};
function match(dets, gt, sp, mx=15){
  const pairs=[]; dets.forEach((d,di)=>gt.forEach((g,gi)=>{
    pairs.push([Math.hypot((d.x-sp.cx)/sp.pxPerMm-g.x,(d.y-sp.cy)/sp.pxPerMm-g.y),di,gi]);}));
  pairs.sort((a,b)=>a[0]-b[0]); const ud=new Set(),ug=new Set(),ms=[];
  for(const[dist,di,gi]of pairs){if(dist>mx)break;if(ud.has(di)||ug.has(gi))continue;ud.add(di);ug.add(gi);ms.push(dist);}
  return {nMatch:ms.length,nFP:dets.length-ud.size,dists:ms};
}

console.log('Browser-Code-Pfad-Test (imageDataToGray + streamDetect)\n');
let totGt=0,totMatch=0,totFP=0,allD=[];
for (const phase of ['1a','1b']) {
  const refID = loadImageData(`./${phase}_ref.png`);
  const postID = loadImageData(`./${phase}_post.png`);
  const ref = TP.imageDataToGray(refID);    // ← exakt der Browser-Pfad
  const post = TP.imageDataToGray(postID);
  const { dets, shift } = TP.streamDetect(ref.gray, post.gray, ref.W, ref.H, SP, 5.6,
    { thrRel: 0.30, snrMin: 6, maxShift: 10 });
  const m = match(dets, GT[phase], SP);
  const med = m.dists.length?[...m.dists].sort((a,b)=>a-b)[m.dists.length>>1]:0;
  console.log(`  ${phase}: shift ${shift.dx}/${shift.dy}, ${m.nMatch}/${GT[phase].length} match, ${m.nFP} FP, Δ=${med.toFixed(1)}mm`);
  totGt+=GT[phase].length; totMatch+=m.nMatch; totFP+=m.nFP; allD.push(...m.dists);
}
const med=allD.length?[...allD].sort((a,b)=>a-b)[allD.length>>1]:0;
console.log(`\n  GESAMT: Recall ${(100*totMatch/totGt).toFixed(0)}%, Precision ${(100*totMatch/Math.max(1,totMatch+totFP)).toFixed(0)}%, ${med.toFixed(1)}mm, ${(totFP/2).toFixed(1)} FP/Serie`);
console.log(`  → Muss identisch zu test_v240.mjs sein (90%/90%) = Browser-Pfad korrekt.`);
