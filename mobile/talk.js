/** Shared, clock-driven arming and VOX policy. AUTO is an energy gate, not a voice classifier. */
export class TalkControl {
  constructor(){this.mode='HOLD';this.armed=false;this.threshold=-36;this.closeDelayMs=650;this.timeoutMs=0;this.noiseReduction=true;this.deadline=Infinity;this.lastVoice=-Infinity;this.open=false;}
  get requested(){return this.armed&&(this.mode!=='AUTO'||this.open);}
  setMode(value){if(!['HOLD','TAP','AUTO'].includes(value))throw Error('Invalid talk mode');this.stop();this.mode=value;}
  active(value,now){if(!value){this.stop();return;}this.armed=true;this.open=this.mode!=='AUTO';this.lastVoice=-Infinity;this.deadline=now+(this.mode==='HOLD'?30000:this.timeoutMs>0?this.timeoutMs:Infinity);}
  level(db,now){if(this.mode!=='AUTO'||!this.armed)return;if(Number.isFinite(db)&&db>=this.threshold){this.lastVoice=now;this.open=true;}if(now-this.lastVoice>this.closeDelayMs)this.open=false;this.tick(now);}
  tick(now){if(this.armed&&now>=this.deadline)this.stop();}
  stop(){this.armed=false;this.open=false;this.deadline=Infinity;this.lastVoice=-Infinity;}
}
