const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
function fixture(rate=48000,target=10){
  let Processor;
  const context=vm.createContext({sampleRate:rate,currentFrame:0,ArrayBuffer,DataView,Int16Array,
    AudioWorkletProcessor:class{constructor(){this.port={postMessage:m=>{this.message=m}}}},
    registerProcessor:(name,p)=>{Processor=p}});
  vm.runInContext(fs.readFileSync('low-latency-worklet.js','utf8'),context);
  const p=new Processor({processorOptions:{targetMs:target}});
  function process(n=128){const out=[new Float32Array(n),new Float32Array(n)];p.process([], [out]);context.currentFrame+=n;return out;}
  function feed(seq,left=.25,right=-.125){
    const b=new ArrayBuffer(984),v=new DataView(b);
    v.setUint32(0,0x464c4c31);v.setUint32(4,seq,true);v.setUint32(8,(seq*240)>>>0,true);
    v.setUint16(12,240,true);v.setUint8(14,2);v.setUint32(16,48000,true);v.setUint32(20,2500,true);
    for(let i=0;i<240;i++){v.setInt16(24+i*4,left*32767,true);v.setInt16(26+i*4,right*32767,true)}
    p.receive(b);return b;
  }
  return {p,process,feed,context};
}
{
  const f=fixture();f.feed(0);assert.ok(f.process()[0].every(x=>x===0));
  f.feed(1);const [l,r]=f.process();assert.ok(l.at(-1)>.24&&r.at(-1)<-.12);
  f.feed(1);assert.equal(f.p.counters.duplicate,1);
  f.process(400);f.feed(0);assert.equal(f.p.counters.late,1);
  f.p.receive(new ArrayBuffer(20));assert.equal(f.p.counters.invalid,1);
}
{
  const f=fixture();f.feed(1);f.feed(0);assert.equal(f.p.sequence,0);assert.ok(f.process()[0].at(-1)>.24);
}
{
  const f=fixture();f.feed(0xffffffff);f.feed(0);f.process(500);assert.equal(f.p.sequence,1);
}
{
  const f=fixture();for(let i=0;i<100;i++)f.feed(i);
  f.process();assert.ok(f.p.counters.skipped>0);assert.ok((f.p.latest-f.p.sequence)*5<=20);
  f.context.currentFrame+=48000;assert.ok(f.process()[0].every(x=>x===0));
  f.feed(100);f.feed(101);assert.ok(f.process()[0].at(-1)>.24);
}
for(const rate of [44100,48000,96000]){
  const f=fixture(rate);f.feed(0);f.feed(1);let seq=2;
  for(let i=0;i<3000;i++){
    const expected=Math.floor(f.context.currentFrame/rate*200)+2;
    while(seq<=expected)f.feed(seq++);
    const [l,r]=f.process();assert.ok(l.every(x=>Number.isFinite(x)&&Math.abs(x)<=.251));
    assert.ok(r.every(x=>Number.isFinite(x)&&Math.abs(x)<=.126));
  }
  assert.equal(f.p.counters.missing,0,'no packet loss under regular pacing '+rate);
  assert.equal(f.p.counters.rebuffer,0);
}
{
  const f=fixture();f.feed(0);f.feed(1);f.process(480);f.feed(3);f.feed(4);f.process(480);
  assert.equal(f.p.counters.missing,1);assert.ok(f.p.counters.underrunFrames>=239);
}
console.log('PASS: PCM worklet stereo, reorder, wrap, loss, backlog bound, stale flush and device rate conversion.');
