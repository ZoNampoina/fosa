class FosaGateProcessor extends AudioWorkletProcessor {
  static get parameterDescriptors() {
    return [
      { name: 'thresholdDb', defaultValue: -48, minValue: -80, maxValue: -20, automationRate: 'k-rate' },
      { name: 'enabled', defaultValue: 1, minValue: 0, maxValue: 1, automationRate: 'k-rate' }
    ];
  }
  constructor() {
    super();
    this.gain = 0;
    this.hold = 0;
    this.releaseCoeff = Math.exp(-1 / (sampleRate * 0.055));
  }
  process(inputs, outputs, parameters) {
    const input = inputs[0];
    const output = outputs[0];
    if (!output.length) return true;
    const n = output[0].length;
    const thresholdDb = parameters.thresholdDb[0] ?? -48;
    const enabled = (parameters.enabled[0] ?? 1) >= 0.5;
    const threshold = Math.pow(10, thresholdDb / 20);
    const holdSamples = Math.max(1, Math.round(sampleRate * 0.07));
    for (let i = 0; i < n; i++) {
      let peak = 0;
      for (let c = 0; c < input.length; c++) {
        const v = Math.abs(input[c]?.[i] || 0);
        if (v > peak) peak = v;
      }
      if (!enabled) {
        this.gain = 1;
      } else if (peak >= threshold) {
        this.hold = holdSamples;
        this.gain = 1;
      } else if (this.hold > 0) {
        this.hold--;
        this.gain = 1;
      } else {
        this.gain *= this.releaseCoeff;
        if (this.gain < 0.0001) this.gain = 0;
      }
      for (let c = 0; c < output.length; c++) {
        output[c][i] = (input[c]?.[i] || 0) * this.gain;
      }
    }
    return true;
  }
}
registerProcessor('fosa-gate', FosaGateProcessor);