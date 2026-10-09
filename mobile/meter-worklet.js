// Measures real getUserMedia PCM before the voice processing chain. No synthesis.
class FosaMeter extends AudioWorkletProcessor {
  constructor(){super();this.frames=0;this.nonzero=0;this.sum=0;this.samples=0;}
  process(inputs,outputs){const input=inputs[0]?.[0],output=outputs[0]?.[0];if(!input)return true;if(output)output.set(input);let sum=0;for(const v of input)sum+=v*v;this.frames+=input.length;this.samples+=input.length;this.sum+=sum;if(sum/input.length>1e-9)this.nonzero+=input.length;
    if(this.samples>=sampleRate/10){this.port.postMessage({frames:this.frames,nonzero:this.nonzero,dbfs:Math.max(-120,20*Math.log10(Math.max(1e-6,Math.sqrt(this.sum/this.samples))))});this.sum=0;this.samples=0;}return true;
  }
}
registerProcessor('fosa-meter',FosaMeter);
