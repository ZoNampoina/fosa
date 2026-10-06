// Exercise the production signalling logic without a browser. Real ICE/Opus
// and Android audio are verified separately by the instrumented/browser suites.
const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const path=require('node:path');

class Peer {
  constructor(){this.signalingState='stable';this.connectionState='connected';this.track=null;}
  addTrack(track){this.track=track;}
  addTransceiver(){}
  createDataChannel(){return {readyState:'open',send(){}};}
  async createOffer(){return {type:'offer',sdp:`v=0\r\na=mic:${!!this.track}\r\n`};}
  async createAnswer(){return {type:'answer',sdp:`v=0\r\na=mic:${!!this.track}\r\n`};}
  async setLocalDescription(s){
    assert.equal(this.signalingState,s.type==='offer'?'stable':'have-remote-offer','Invalid local SDP state');
    this.localDescription=s;this.signalingState=s.type==='offer'?'have-local-offer':'stable';
  }
  async setRemoteDescription(s){
    assert.equal(this.signalingState,s.type==='offer'?'stable':'have-local-offer','Colliding audio descriptions');
    this.remoteDescription=s;this.signalingState=s.type==='offer'?'have-remote-offer':'stable';
  }
  close(){this.connectionState='closed';this.signalingState='closed';}
}
global.RTCPeerConnection=Peer;
global.MediaStream=class {constructor(tracks){this.tracks=tracks;}};
function track(){return {enabled:false,clone:track,stop(){}};}

(async()=>{
  const file=process.env.FOSA_CORE_PATH||path.resolve(__dirname,'../core.js');
  const {WebLanClient}=await import(pathToFileURL(file));
  const a=new WebLanClient(),b=new WebLanClient(),clients={a,b},mail=[];
  const members=[{id:'a',online:true,group:'BAND'},{id:'b',online:true,group:'BAND'}];
  for(const [id,f] of Object.entries(clients)){
    f.profile={id};f.members=members;f.host=id;
    f.microphoneReady=async()=>{f.microphone=track();};f.enableAudio=async()=>{};
    f.api=async(p,q)=>{assert.equal(p,'signal');mail.push({recipient:q.to,from:id,type:q.type,data:q.data});return {ok:true};};
  }
  a.make('b');b.make('a');const originalA=a.links.get('b').pc,originalB=b.links.get('a').pc;
  await Promise.all([a.enableMicrophone(),b.enableMicrophone()]);
  for(let count=0;mail.length&&count<30;count++){
    const q=mail.shift();await clients[q.recipient].receive(q);
  }
  assert.equal(mail.length,0,'Signalling must settle without an offer loop');
  assert.equal(a.links.get('b').pc,originalA);assert.equal(b.links.get('a').pc,originalB);
  for(const [id,f] of Object.entries(clients)){
    const l=f.links.get(id==='a'?'b':'a');assert.equal(l.pc.signalingState,'stable');
    assert.match(l.pc.remoteDescription.sdp,/a=mic:true/);assert.equal(l.track.enabled,false);
    f.push(true);assert.equal(f.talking,true);assert.equal(l.track.enabled,true);
    f.push(false);assert.equal(f.talking,false);assert.equal(l.track.enabled,false);
    f.close();
  }
  const f=new WebLanClient(),delivered=[];let first=true;
  f.api=async(p,q)=>{if(first){first=false;throw Error('Transient LAN timeout');}delivered.push(q);return {ok:true};};
  await assert.rejects(f.signal('peer','offer',{sdp:'retained SDP'}),/Transient LAN timeout/);
  await f.signal('peer','ice',{candidate:'retained ICE'});
  assert.deepEqual(delivered.map(q=>q.type),['offer','ice']);assert.equal(f.signals.length,0);
  assert.equal(delivered[0].data.sdp,'retained SDP');
  console.log('PASS: simultaneous microphone permission, stable SDP in both directions, listening links preserved, held PTT release, and retained SDP/ICE after a transient signalling timeout. Transport is simulated; no hardware audio claim.');
})().catch(e=>{console.error(e);process.exitCode=1;});
