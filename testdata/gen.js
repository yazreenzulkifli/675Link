const fs=require('fs');
const R=6371000, rad=d=>d*Math.PI/180;
const hav=(a,b)=>{const dl=rad(b[0]-a[0]),dn=rad(b[1]-a[1]);const x=Math.sin(dl/2)**2+Math.cos(rad(a[0]))*Math.cos(rad(b[0]))*Math.sin(dn/2)**2;return 2*R*Math.asin(Math.sqrt(x));};
// Kuala Lumpur scenic loop (waypoints), densified at 1 Hz with a speed profile.
const wp=[[3.1390,101.6869],[3.1700,101.7100],[3.2050,101.7250],[3.2400,101.7400],[3.2100,101.7800],[3.1700,101.7700],[3.1500,101.7300],[3.1390,101.6869]];
function build(id,startMs,wps,cruiseMs){
  const pts=[];let t=startMs,dist=0,max=0,moving=0;
  let cur=wps[0],spd=0;
  for(let i=1;i<wps.length;i++){
    const tgt=wps[i];
    while(true){
      const d=hav(cur,tgt); if(d<8)break;
      const want=cruiseMs*(0.7+0.3*Math.sin(t/20000+i));
      spd+=(want-spd)*0.15; const step=Math.min(spd,d);
      const f=step/d; const n=[cur[0]+(tgt[0]-cur[0])*f+ (Math.random()-.5)*2e-6, cur[1]+(tgt[1]-cur[1])*f+(Math.random()-.5)*2e-6];
      dist+=step; moving+=1000; max=Math.max(max,spd); t+=1000; cur=n;
      pts.push([+cur[0].toFixed(6),+cur[1].toFixed(6),t,+spd.toFixed(2)]);
    }
  }
  return {id,start:startMs,end:t,distanceMeters:dist,movingTimeMs:moving,maxSpeedMs:max,points:pts};
}
const now=Date.now(); const mid=new Date(now).setHours(0,0,0,0);
const trips=[build('test_ride_1',mid+30e3,wp,22),build('test_ride_2',mid+38*6e4,wp.slice(0,5),17),build('test_ride_3',mid+60*6e4,wp.slice(3),26)];
for(const tr of trips){
  fs.writeFileSync(tr.id+'.json',JSON.stringify(tr));
  const g=`<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="OpenCfMoto test" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>${tr.id}</name><trkseg>\n`+tr.points.map(p=>`<trkpt lat="${p[0]}" lon="${p[1]}"><time>${new Date(p[2]).toISOString()}</time><extensions><speed>${p[3]}</speed></extensions></trkpt>`).join('\n')+`\n</trkseg></trk></gpx>\n`;
  fs.writeFileSync(tr.id+'.gpx',g);
  console.log(tr.id,(tr.distanceMeters/1000).toFixed(1)+'km',Math.round(tr.movingTimeMs/60000)+'min',Math.round(tr.maxSpeedMs*3.6)+'km/h max',tr.points.length+'pts');
}
