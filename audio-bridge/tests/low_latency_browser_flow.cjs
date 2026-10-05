const assert=require('node:assert/strict');
const {chromium,webkit,devices}=require('playwright');
const {fixture,musician,signal,press,release}=require('./secure_browser_flow.cjs');
const output=process.env.FOSA_BROWSER_OUTPUT||'/tmp/fosa-browser-output';

async function main(){
  const f=await fixture(true);let chrome,safari;
  try{
    const base=new URL(f.console).origin;
    const {token}=await (await fetch(base+'/api/local-console',{method:'POST',headers:{'X-FOSA-Console':'1'}})).json();
    async function admin(path,body){
      const r=await fetch(base+'/api/'+path,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
      assert.equal(r.status,200);return r.json();
    }
    chrome=await chromium.launch({args:['--no-sandbox','--no-proxy-server','--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream']});
    safari=await webkit.launch();
    const a=await musician(chrome,f.url,'PCM Android','Chef',{...devices['Pixel 7'],ignoreHTTPSErrors:true},'pcm');
    const b=await musician(chrome,f.url,'PCM Windows','Chant',{viewport:{width:1200,height:900},ignoreHTTPSErrors:true},'pcm');
    const ios=await musician(safari,f.url,'PCM iPhone','Clavier',{...devices['iPhone 13'],ignoreHTTPSErrors:true},'pcm');
    const ipad=await musician(safari,f.url,'PCM iPad','Clavier',{...devices['iPad Pro 11'],ignoreHTTPSErrors:true},'pcm');
    const stable=await musician(chrome,f.url,'Opus listener','Chant',{ignoreHTTPSErrors:true},'opus');
    const all=[a,b,ios,ipad,stable];
    for(const item of all)await signal(item.page,true);
    for(const item of [a,b,ios,ipad]){
      const channel=await item.page.evaluate(()=>window.testChannels.find(c=>c.label==='fosa-pcm-v1')&&({ordered:window.testChannels.find(c=>c.label==='fosa-pcm-v1').ordered,retries:window.testChannels.find(c=>c.label==='fosa-pcm-v1').maxRetransmits}));
      assert.deepEqual(channel,{ordered:false,retries:0});
    }
    async function configure(id,channel,pan){
      const p=(await admin('state')).users.find(p=>p.id===id);
      p.mix.channels.forEach(c=>c.gain=0);p.mix.channels[channel].gain=1;p.mix.channels[channel].pan=pan;
      await admin('mix',{id,mix:p.mix});
    }
    await configure(a.credentials.id,0,-1);await configure(b.credentials.id,2,1);
    for(const item of [a,b,ios,ipad]){
      const p=(await admin('state')).users.find(p=>p.id===item.credentials.id);
      assert.equal(p.monitoringEngine,'pcm');assert.ok(p.pcm.sent>0);
      await item.page.locator('.net-nav [data-view=diagnostic]').click();
      await item.page.waitForFunction(()=>document.querySelector('#diagnostics').textContent.includes('PCM · direct'));
      assert.ok((await item.page.locator('#diagnostics').textContent()).includes('NON MESURÉE'));
      await item.page.screenshot({path:output+'/pcm-'+item.credentials.id+'.png',fullPage:true});
    }
    // Real Bodypack gain reaches decoded PCM; panic is local even without its RPC.
    await a.page.locator('.net-nav [data-view=mix]').click();
    const rms=()=>a.page.evaluate(async()=>{
      // Median across distinct audio windows rejects a transient rebuffer/fade.
      const values=[];
      for(let i=0;i<9;i++){
        await new Promise(r=>setTimeout(r,100));
        const a=window.testOutput,s=new Float32Array(a.fftSize);a.getFloatTimeDomainData(s);
        values.push(Math.sqrt(s.reduce((v,n)=>v+n*n,0)/s.length));
      }
      return values.sort((a,b)=>a-b)[4];
    });
    await a.page.waitForFunction(()=>document.querySelector('[data-pan="0"]').value==='-100'&&document.querySelector('[data-gain="2"]').value==='0');
    const before=await rms();assert.ok(before>.001);
    await a.page.locator('#monitorGain').evaluate(el=>{el.value='6';el.dispatchEvent(new Event('input',{bubbles:true}))});
    await a.page.waitForFunction(()=>document.querySelector('#mixNotice').textContent.startsWith('Mix enregistré')&&document.querySelector('#monitorGain').value==='6');
    const boosted=(await admin('state')).users.find(p=>p.id===a.credentials.id);
    assert.equal(boosted.mix.monitorGainDb,6);assert.equal(boosted.mix.master,.5);
    const after=await rms();assert.ok(after>before*1.6&&after<before*2.4,`+6 dB must approximately double decoded RMS: ${before} -> ${after}`);
    assert.ok(boosted.levels.outputPeakDb>boosted.levels.mixPeakDb-1);
    await a.page.screenshot({path:output+'/bodypack-personal-mix.png',fullPage:true});
    await a.page.locator('#lockMix').click();assert.equal(await a.page.locator('#monitorGain').isDisabled(),true);
    await a.page.evaluate(()=>{const ch=window.testChannels.find(c=>c.label==='fosa-control'),send=ch.send.bind(ch);window.restorePanicRpc=()=>ch.send=send;ch.send=value=>{try{if(JSON.parse(value).path==='panic')return}catch{}send(value)}});
    await a.page.locator('#panicMute').click();await signal(a.page,false);await signal(b.page,true);
    assert.equal((await admin('state')).users.find(p=>p.id===a.credentials.id).mix.muteAll,false,'Local panic must not need server acknowledgement');
    await a.page.evaluate(()=>window.restorePanicRpc());await a.page.locator('#releasePanic').click();await signal(a.page,true);
    await a.page.locator('#lockMix').click();
    await a.page.locator('#monitorGain').evaluate(el=>{el.value='0';el.dispatchEvent(new Event('input',{bubbles:true}))});
    // Matrix restrictions affect PCM too, and leave the other mix audible.
    const allowed=Array(18).fill(true);allowed[2]=false;
    await admin('matrix',{id:b.credentials.id,allowed});await signal(b.page,false);await signal(a.page,true);
    allowed[2]=true;await admin('matrix',{id:b.credentials.id,allowed});await signal(b.page,true);
    // Actual packet loss is injected into the receiver's MessagePort, not an audio demo.
    await b.page.evaluate(()=>{
      const Original=window.AudioWorkletNode;
      window.testPcmNodes=[];
      window.AudioWorkletNode=class extends Original{constructor(...args){super(...args);window.testPcmNodes.push(this);this.port.addEventListener('message',e=>window.testPcmMetrics=e.data);this.port.start()}};
    });
    await b.page.locator('.net-nav [data-view=mix]').click();await b.page.locator('#stopAudio').click();await b.page.locator('#startAudio').click();await signal(b.page,true);
    await b.page.waitForFunction(()=>window.testPcmMetrics?.received>20);
    await b.page.evaluate(()=>{
      const port=window.testPcmNodes.at(-1).port,post=port.postMessage.bind(port);
      window.restorePcmPost=()=>port.postMessage=post;
      let count=0;port.postMessage=(message,...args)=>{if(message instanceof ArrayBuffer&&++count%7===0)return;return post(message,...args)};
    });
    await b.page.waitForFunction(()=>window.testPcmMetrics.missing>5,null,{timeout:12000});
    await b.page.evaluate(()=>window.restorePcmPost());await signal(b.page,true);
    await b.page.waitForFunction(()=>window.testPcmMetrics.bufferedMs<=25,null,{timeout:10000});
    // PCM listeners and the existing Opus listener hear the same authorized talkback.
    for(const p of (await admin('state')).users){p.mix.channels.forEach(c=>c.gain=0);await admin('mix',{id:p.id,mix:p.mix});}
    await admin('matrix',{id:a.credentials.id,talkAllowed:true});
    await a.page.locator('.net-nav [data-view=live]').click();
    await a.page.waitForFunction(()=>!document.querySelector('#liveEnableMic').disabled);
    await a.page.locator('#liveEnableMic').click();await a.page.waitForFunction(()=>!document.querySelector('#liveTalk').disabled);
    await a.page.locator('#liveTalkTarget').selectOption(b.credentials.id);
    await press(a.page);await signal(b.page,true);await signal(ios.page,false);await signal(a.page,false);
    await release(a.page);await signal(b.page,false);
    await a.page.locator('#liveTalkTarget').selectOption('all');await press(a.page);
    await signal(b.page,true);await signal(ios.page,true);await signal(stable.page,true);
    await admin('matrix',{id:a.credentials.id,talkAllowed:false});await signal(b.page,false);await signal(stable.page,false);await release(a.page);
    // Switching mode re-negotiates once; stopping closes microphone and PCM worklet.
    await b.page.locator('.net-nav [data-view=settings]').click();
    await b.page.locator('[data-panel=settings] .monitoring-engine').selectOption('opus');
    await b.page.waitForFunction(()=>document.querySelector('#streamStatus').textContent==='AUDIO EN LECTURE');
    await b.page.locator('[data-panel=settings] .monitoring-engine').selectOption('pcm');
    await b.page.waitForFunction(()=>document.querySelector('#streamStatus').textContent==='AUDIO EN LECTURE');
    for(const item of all)assert.deepEqual(item.errors,[]);
    console.log('PASS: PCM audio decoded in Chromium/WebKit, independent mixes, matrix, bounded loss recovery, reconnect, Opus coexistence, talkback destination/release/revocation. Physical devices untested; no end-to-end latency claim.');
  }finally{await chrome?.close();await safari?.close();f.child.kill('SIGTERM');}
}
main().catch(error=>{console.error(error);process.exitCode=1});
