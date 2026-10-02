/* AudioWorklet lifecycle for the PCM engine. No microphone access here. */
class FosaLowLatency {
  constructor(context, destination, {targetMs=10,onMetrics=()=>{},onPacket=()=>{},onError=()=>{}}={}) {
    this.context=context;this.destination=destination;this.targetMs=targetMs;
    this.onMetrics=onMetrics;this.onPacket=onPacket;this.onError=onError;
    this.node=null;this.closed=false;this.jitter=0;this.previousArrival=null;this.previousSequence=null;
  }
  async prepare() {
    if(!isSecureContext||!this.context?.audioWorklet||typeof AudioWorkletNode!=='function')
      throw Error('Low-Latency nécessite le QR sécurisé et un navigateur avec AudioWorklet.');
    if(!FosaLowLatency.modules.has(this.context))
      FosaLowLatency.modules.set(this.context,this.context.audioWorklet.addModule('./low-latency-worklet.js'));
    await FosaLowLatency.modules.get(this.context);
    if(this.closed)return;
    this.node=new AudioWorkletNode(this.context,'fosa-pcm-v1',{numberOfInputs:0,numberOfOutputs:1,
      outputChannelCount:[2],processorOptions:{targetMs:this.targetMs}});
    this.node.onprocessorerror=()=>this.onError('Le moteur PCM s’est arrêté. Arrête puis relance l’écoute.');
    this.node.port.onmessage=e=>{if(!this.closed&&e.data?.type==='metrics')this.onMetrics({...e.data,jitterMs:this.jitter})};
    this.node.connect(this.destination);
  }
  attach(channel) {
    channel.binaryType='arraybuffer';
    channel.addEventListener('message',e=>{
      if(this.closed||!this.node||this.context.state!=='running')return;
      const data=e.data;
      if(!(data instanceof ArrayBuffer)||data.byteLength!==984)return;
      const view=new DataView(data);
      if(view.getUint32(0,false)!==0x464c4c31)return;
      const seq=view.getUint32(4,true),now=performance.now();
      if(this.previousArrival!==null&&(seq-this.previousSequence|0)>0){
        const variation=Math.abs(now-this.previousArrival-(seq-this.previousSequence|0)*5);
        this.jitter+=(variation-this.jitter)/16;
      }
      if(this.previousSequence===null||(seq-this.previousSequence|0)>0){this.previousArrival=now;this.previousSequence=seq;}
      this.node.port.postMessage(data,[data]);this.onPacket();
    });
  }
  target(ms){this.targetMs=ms;this.node?.port.postMessage({type:'target',ms});}
  reset(){this.node?.port.postMessage({type:'reset'});this.previousArrival=null;this.previousSequence=null;this.jitter=0;}
  close(){this.closed=true;this.node?.disconnect();this.node?.port.close();this.node=null;}
}
FosaLowLatency.modules=new WeakMap();
