/* FOSA PCM v1. Fixed storage, bounded playout, no allocations in process().
 * Network time is never converted into an end-to-end audio latency.
 */
class FosaPcmProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.frames = 240;
    this.slots = Array.from({length:32},()=>({sequence:null,pcm:new Int16Array(480)}));
    this.targetPackets = 2;
    this.latest = null;
    this.sequence = null;
    this.position = 0;
    this.started = false;
    this.lastArrival = -Infinity;
    this.reportAt = 0;
    this.counters = {received:0,late:0,duplicate:0,invalid:0,missing:0,underrunFrames:0,skipped:0,rebuffer:0};
    this.queueMs = null;
    this.lastLeft = 0; this.lastRight = 0;
    this.fade = 0;this.missingRun=0;
    this.configure(options.processorOptions?.targetMs);
    this.port.onmessage = e => {
      if(e.data instanceof ArrayBuffer)this.receive(e.data);
      else if(e.data?.type==='target')this.configure(e.data.ms);
      else if(e.data?.type==='reset')this.reset();
    };
  }
  configure(ms) {
    this.targetPackets = [5,10,20,40].includes(Number(ms)) ? Number(ms)/5 : 2;
  }
  reset() {
    for(const slot of this.slots)slot.sequence=null;
    this.sequence=null;this.latest=null;this.position=0;this.started=false;
    this.lastLeft=0;this.lastRight=0;this.fade=0;this.missingRun=0;this.lastArrival=-Infinity;
  }
  distance(a,b){return (a-b)|0;} // Sequence wrap is valid for <2^31 packets.
  receive(buffer) {
    if(buffer.byteLength!==984){this.counters.invalid++;return;}
    const view=new DataView(buffer), seq=view.getUint32(4,true);
    if(view.getUint32(0,false)!==0x464c4c31||view.getUint32(8,true)!==((seq*240)>>>0)||
       view.getUint16(12,true)!==240||view.getUint8(14)!==2||view.getUint8(15)!==0||
       view.getUint32(16,true)!==48000){this.counters.invalid++;return;}
    if(this.started&&this.sequence!==null&&this.distance(seq,this.sequence)<0){this.counters.late++;return;}
    const slot=this.slots[seq%32];
    if(slot.sequence===seq){this.counters.duplicate++;return;}
    if(this.latest===null||this.distance(seq,this.latest)>0)this.latest=seq;
    if(this.sequence===null)this.sequence=seq;
    if(!this.started&&this.distance(seq,this.sequence)<0)this.sequence=seq;
    if(this.distance(this.latest,this.sequence)>=32){
      this.counters.skipped+=this.distance(this.latest,this.sequence)-this.targetPackets+1;
      this.sequence=(this.latest-this.targetPackets+1)>>>0;this.position=0;this.fade=64;
    }
    for(let i=0;i<480;i++)slot.pcm[i]=view.getInt16(24+i*2,true);
    slot.sequence=seq;
    this.queueMs=view.getUint32(20,true)/1000;
    this.lastArrival=currentFrame;
    this.counters.received++;
  }
  process(inputs,outputs) {
    const out=outputs[0];if(!out?.length)return true;
    const left=out[0],right=out[1]||out[0];
    if(this.started&&currentFrame-this.lastArrival>sampleRate*.1){
      this.counters.rebuffer++;this.reset();
    }
    let depth=this.sequence===null?0:(this.distance(this.latest,this.sequence)+1)*240-this.position;
    if(this.started&&depth < -240){this.counters.rebuffer++;this.reset();depth=0;}
    if(!this.started&&depth>=this.targetPackets*240){this.started=true;this.fade=64;}
    if(this.started&&depth>(this.targetPackets+3)*240){
      const skip=Math.floor(depth/240)-this.targetPackets;
      this.sequence=(this.sequence+skip)>>>0;this.position=0;
      this.counters.skipped+=skip;this.fade=64;depth=this.targetPackets*240;
    }
    // Small correction for independent device clocks; large backlog is discarded above.
    const correction=Math.max(-.001,Math.min(.001,(depth-this.targetPackets*240)/240*.0002));
    const step=48000/sampleRate*(1+correction);
    for(let i=0;i<left.length;i++) {
      let l=0,r=0;
      if(this.started) {
        const slot=this.slots[this.sequence%32],index=Math.floor(this.position),frac=this.position-index;
        if(slot.sequence===this.sequence) {
          if(this.missingRun){this.fade=64;this.missingRun=0;}
          const next=index===239?this.slots[((this.sequence+1)>>>0)%32]:slot;
          const nextIndex=index===239?0:index+1;
          const nextValid=index!==239||next.sequence===((this.sequence+1)>>>0);
          const a=slot.pcm[index*2]/32768,b=slot.pcm[index*2+1]/32768;
          l=a+(nextValid?next.pcm[nextIndex*2]/32768-a:0)*frac;
          r=b+(nextValid?next.pcm[nextIndex*2+1]/32768-b:0)*frac;
        } else {
          this.counters.underrunFrames++;this.missingRun++;
          // At most 64 samples of fade, never loop old music after a lost packet.
          const remain=Math.max(0,64-this.missingRun);
          const fade=remain/(remain+1);
          l=this.lastLeft*fade;r=this.lastRight*fade;
        }
        if(this.fade>0){const f=1-this.fade/64;l=this.lastLeft*(1-f)+l*f;r=this.lastRight*(1-f)+r*f;this.fade--;}
        this.lastLeft=l;this.lastRight=r;
        this.position+=step;
        while(this.position>=240){
          if(slot.sequence!==this.sequence)this.counters.missing++;
          this.position-=240;this.sequence=(this.sequence+1)>>>0;
        }
      }
      left[i]=l;right[i]=r;
    }
    if(currentFrame>=this.reportAt){
      this.reportAt=currentFrame+sampleRate*.5;
      const frames=this.sequence===null?0:Math.max(0,(this.distance(this.latest,this.sequence)+1)*240-this.position);
      this.port.postMessage({...this.counters,type:'metrics',targetMs:this.targetPackets*5,
        bufferedMs:frames/48,captureQueueMs:this.queueMs,playing:this.started,outputRate:sampleRate});
    }
    return true;
  }
}
registerProcessor('fosa-pcm-v1',FosaPcmProcessor);
