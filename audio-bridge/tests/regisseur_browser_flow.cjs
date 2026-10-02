// Exercise the launcher's local PC route, using real WebRTC and browser test microphones.
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
const {fixture,musician,signal,press,release}=require('./secure_browser_flow.cjs');
const output=process.env.FOSA_BROWSER_OUTPUT||'/tmp/fosa-browser-output';

async function main(){
  const f=await fixture(true);let browser;
  try{
    const base=new URL(f.console).origin;
    const {token}=await (await fetch(base+'/api/local-console',{method:'POST',headers:{'X-FOSA-Console':'1'}})).json();
    async function admin(path,body){
      const r=await fetch(base+'/api/'+path,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
      assert.equal(r.status,200);return r.json();
    }
    async function stateWhen(predicate){
      const deadline=Date.now()+10000;
      while(Date.now()<deadline){const s=await admin('state');if(predicate(s))return s;await new Promise(r=>setTimeout(r,100));}
      throw Error('Expected operator state was not persisted');
    }
    browser=await chromium.launch({args:['--no-sandbox','--no-proxy-server','--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream']});
    const context=await browser.newContext({permissions:['microphone'],serviceWorkers:'block',viewport:{width:1360,height:1000}});
    await context.addInitScript(()=>{
      window.testMicrophones=[];window.testChannels=[];
      const Peer=window.RTCPeerConnection;
      window.RTCPeerConnection=class extends Peer{createDataChannel(...args){const c=super.createDataChannel(...args);window.testChannels.push(c);return c}};
      const C=window.AudioContext||window.webkitAudioContext,gain=C.prototype.createGain;
      C.prototype.createGain=function(){const g=gain.call(this);if(!window.testOutput){const a=this.createAnalyser();a.fftSize=2048;g.connect(a);window.testOutput=a}return g};
      const acquire=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
      navigator.mediaDevices.getUserMedia=async(...args)=>{const stream=await acquire(...args);window.testMicrophones.push(stream);return stream};
    });
    const page=await context.newPage(),errors=[];
    page.on('pageerror',e=>errors.push(e.message));
    page.on('dialog',dialog=>dialog.accept());
    await page.goto(f.console+'&regisseur=1&view=live&engine=pcm');
    await page.locator('#pcRegisseur').waitFor({state:'visible'});
    await page.waitForFunction(()=>!document.querySelector('#liveEnableMic').disabled);
    assert.equal(await page.locator('#welcomeCard').isVisible(),false);
    assert.equal(await page.locator('#adminKey').inputValue(),'');
    assert.equal(await page.evaluate(()=>window.testMicrophones.length),0,'Launch must not acquire a microphone without the operator action');
    assert.equal(await page.evaluate(()=>window.testChannels.length),0,'The microphone button will prepare the connection');
    const operator=(await admin('state')).users.find(p=>p.name==='Régisseur PC');
    assert.ok(operator);assert.equal(operator.talkAllowed,true);assert.equal(operator.mix.master,0);

    // Picking the same role on another device must still require MATRIX authorization.
    const pcm=await musician(browser,f.url,'PCM receiver','Régisseur',{ignoreHTTPSErrors:true},'pcm');
    const opus=await musician(browser,f.url,'Opus receiver','Clavier',{ignoreHTTPSErrors:true},'opus');
    for(const p of (await admin('state')).users){p.mix.channels.forEach(c=>c.gain=0);await admin('mix',{id:p.id,mix:p.mix});}
    await signal(pcm.page,false);await signal(opus.page,false);

    await page.locator('#liveEnableMic').click();
    try{await page.waitForFunction(()=>!document.querySelector('#liveTalk').disabled,null,{timeout:25000});}
    catch(e){console.error('Operator activation:',await page.locator('#notice').textContent());throw e;}
    assert.equal(await page.evaluate(()=>window.testMicrophones.length),1);
    await stateWhen(s=>s.users.find(p=>p.id===operator.id)?.connected);
    assert.equal((await admin('state')).users.find(p=>p.id===operator.id).monitoringEngine,'pcm');
    await signal(page,false);

    // Personal mix editing must keep admin credentials and affect only the PC profile.
    // Wait for the externally muted channels to reach this UI before editing its master.
    await page.waitForFunction(()=>[...document.querySelectorAll('[data-gain]')].every(el=>el.value==='0'));
    await page.locator('#liveMaster').focus();await page.locator('#liveMaster').press('End');
    const s=await stateWhen(s=>s.users.find(p=>p.id===operator.id)?.mix.master===1);
    assert.ok(s.users.find(p=>p.id===operator.id).mix.channels.every(c=>c.gain===0));
    assert.equal(s.users.find(p=>p.id===pcm.credentials.id).mix.master,.5);
    await press(page);await signal(pcm.page,true);await signal(opus.page,true);await signal(page,false);
    await page.screenshot({path:output+'/regisseur-pc-talk.png',fullPage:true});
    await release(page);await signal(pcm.page,false);await signal(opus.page,false);
    await page.locator('#liveTalkTarget').selectOption(pcm.credentials.id);
    await press(page);await signal(pcm.page,true);await signal(opus.page,false);
    await release(page);await signal(pcm.page,false);

    await page.locator('.net-nav [data-view=matrix]').click();
    const permission=page.locator('[data-permission=talkAllowed][data-user="'+pcm.credentials.id+'"]');
    await permission.check();await stateWhen(s=>s.users.find(p=>p.id===pcm.credentials.id)?.talkAllowed);
    await pcm.page.waitForFunction(()=>!document.querySelector('#liveEnableMic').disabled);
    await permission.uncheck();await stateWhen(s=>!s.users.find(p=>p.id===pcm.credentials.id)?.talkAllowed);
    await page.screenshot({path:output+'/regisseur-pc-matrix.png',fullPage:true});

    // Changing the selected hardware releases the old track and requires activation again.
    await page.locator('.net-nav [data-view=live]').click();
    const source=page.locator('[data-panel=live] .microphone-input');
    const deviceId=await source.locator('option').evaluateAll(options=>options.find(o=>o.value)?.value);
    assert.ok(deviceId);await source.selectOption(deviceId);
    await page.waitForFunction(()=>window.testMicrophones.at(-1).getTracks().every(t=>t.readyState==='ended'));
    assert.equal(await page.locator('#liveTalk').isDisabled(),true);
    await page.locator('#liveEnableMic').click();await page.waitForFunction(()=>!document.querySelector('#liveTalk').disabled);
    await page.locator('#stopPcTalk').click();
    await page.waitForFunction(()=>window.testMicrophones.at(-1).getTracks().every(t=>t.readyState==='ended'));
    assert.equal(await page.locator('#liveEnableMic').isDisabled(),false,'The stopped PC can prepare its connection again');

    // An actual browser permission refusal is recoverable and explained in the UI.
    await page.evaluate(()=>{
      const acquire=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);let refuse=true;
      navigator.mediaDevices.getUserMedia=async(...args)=>{if(refuse){refuse=false;throw new DOMException('Test permission refused','NotAllowedError');}return acquire(...args);};
    });
    await page.locator('#liveEnableMic').click();
    await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('Micro refusé'));
    assert.equal(await page.locator('#liveTalk').isDisabled(),true);
    await page.locator('#liveEnableMic').click();await page.waitForFunction(()=>!document.querySelector('#liveTalk').disabled);
    await page.locator('#stopPcTalk').click();
    await page.reload();await page.locator('#pcRegisseur').waitFor({state:'visible'});
    await page.waitForFunction(()=>!document.querySelector('#liveEnableMic').disabled);
    assert.equal(await page.evaluate(()=>window.testMicrophones.length),0);
    assert.equal((await admin('state')).users.filter(p=>p.name==='Régisseur PC').length,1);
    assert.equal((await admin('state')).users.find(p=>p.name==='Régisseur PC').id,operator.id);
    await page.locator('.net-nav [data-view=matrix]').click();await permission.waitFor({state:'visible'});
    for(const item of [errors,pcm.errors,opus.errors])assert.deepEqual(item,[]);
    console.log('PASS: direct PC operator launch without session code, one-click microphone preparation, PCM/Opus talkback, person routing, no self-echo, retained admin MATRIX and personal mix, microphone selection, stop/restart, permission refusal and profile reuse. Physical hardware untested.');
  }finally{await browser?.close();f.child.kill('SIGTERM');}
}
main().catch(error=>{console.error(error);process.exitCode=1});
