// Actual Supabase WSS signalling, P-256/AES-GCM, WebRTC and microphone test device.
// HTTPS assets are fulfilled from this checkout before publication. No hardware claim.
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const {readFileSync, mkdirSync} = require('node:fs');
const {basename} = require('node:path');
const {chromium, webkit, devices} = require('playwright');
const output = process.env.FOSA_BROWSER_OUTPUT || '/tmp/fosa-browser-output';
mkdirSync(output, {recursive:true});

async function fixture(localHttps=false) {
  const child=spawn(process.env.PYTHON||'python',['-u','audio-bridge/tests/browser_server.py','--secure',...(localHttps?['--local-https']:[])],{stdio:['ignore','pipe','inherit']});
  const config=await new Promise((resolve,reject)=>{
    let text='';const timer=setTimeout(()=>{child.kill();reject(Error('Secure fixture startup timeout'))},45000);
    child.stdout.on('data',chunk=>{text+=chunk;const line=text.split('\n').find(s=>s.startsWith('{"url"'));if(line){clearTimeout(timer);resolve(JSON.parse(line))}});
    child.on('exit',code=>{clearTimeout(timer);reject(Error('Secure fixture exited '+code))});
  });
  return {child,...config};
}
async function musician(browser, url, name, role, device={},engine='opus') {
  const context=await browser.newContext({...device,permissions:['microphone'],serviceWorkers:'block'});
  const allowed=new Set(['network.html','network.js','network.css','bodypack.css','network-relay.js','network-relay-config.json','fosa-icon.svg','low-latency.js','low-latency-worklet.js']);
  await context.route('https://zonampoina.github.io/fosa/musicians/**/*',route=>{
    const name=basename(new URL(route.request().url()).pathname);
    if(!allowed.has(name))return route.abort();
    const contentType=name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':name.endsWith('.json')?'application/json':name.endsWith('.svg')?'image/svg+xml':'text/html';
    return route.fulfill({status:200,contentType,body:readFileSync(name)});
  });
  await context.addInitScript(()=>{
    window.testChannels=[];window.testSockets=[];window.testMicrophones=[];
    const Original=window.RTCPeerConnection;
    window.RTCPeerConnection=class extends Original{createDataChannel(...args){const c=super.createDataChannel(...args);window.testChannels.push(c);return c}};
    const Socket=window.WebSocket;
    window.WebSocket=class extends Socket{constructor(...args){super(...args);window.testSockets.push(this)}};
    const C=window.AudioContext||window.webkitAudioContext, gain=C.prototype.createGain;
    C.prototype.createGain=function(){const g=gain.call(this);if(!window.testOutput){const a=this.createAnalyser();a.fftSize=2048;g.connect(a);window.testOutput=a}return g};
    const acquire=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    navigator.mediaDevices.getUserMedia=async (...args)=>{const stream=await acquire(...args);window.testMicrophones.push(stream);return stream};
  });
  const page=await context.newPage(),errors=[],requests=[];
  page.on('pageerror',e=>errors.push(e.message));
  page.on('request',r=>requests.push(r.url()));
  await page.goto(url);
  assert.equal(await page.evaluate(()=>isSecureContext),true);
  assert.equal(await page.evaluate(()=>typeof navigator.mediaDevices.getUserMedia),'function');
  await page.locator('#profileName').fill(name);await page.locator('#profileRole').selectOption(role);
  await page.locator('#welcomeCard .monitoring-engine').selectOption(engine);
  const start=Date.now();await page.locator('#joinListen').click();
  try {
    await page.waitForFunction(()=>document.querySelector('#streamStatus').textContent==='AUDIO EN LECTURE'&&window.testChannels.some(c=>c.readyState==='open'),null,{timeout:30000});
  } catch(error) { console.error(name,await page.locator('#notice').textContent());throw error; }
  await page.waitForFunction(()=>window.testSockets.length>0&&window.testSockets.every(s=>s.readyState===WebSocket.CLOSED),null,{timeout:10000});
  assert.ok(requests.every(u=>!u.startsWith('http://')),'HTTPS client must never fetch the private HTTP server');
  assert.equal(await page.locator('#liveEnableMic').isDisabled(),true,'Regisseur permission is required');
  assert.ok((await page.locator('#liveTalkNote').textContent()).includes('Sur le PC : LIVE'));
  assert.deepEqual(errors,[]);
  const credentials=await page.evaluate(()=>JSON.parse(localStorage.fosa_network_credentials)[JSON.parse(localStorage.fosa_network_server)]);
  console.log(JSON.stringify({name,secureJoinMs:Date.now()-start,transport:'actual encrypted Supabase WSS -> direct LAN data channel'}));
  return {context,page,errors,credentials};
}
async function signal(page,active) {
  await page.waitForFunction(active=>{
    const a=window.testOutput;if(!a)return false;
    const values=new Float32Array(a.fftSize);a.getFloatTimeDomainData(values);
    const rms=Math.sqrt(values.reduce((s,n)=>s+n*n,0)/values.length);
    return active?rms>.001:rms<.0003;
  },active,{timeout:12000,polling:100});
}
async function press(page) {
  await page.bringToFront();await page.locator('#liveTalk').scrollIntoViewIfNeeded();
  const box=await page.locator('#liveTalk').boundingBox();
  await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();
  await page.waitForFunction(()=>document.querySelector('#liveTalk').classList.contains('talking'),null,{timeout:8000});
}
async function release(page) { await page.mouse.up(); }

async function main() {
  const f=await fixture();let chrome,safari;
  try {
    const base=new URL(f.console).origin;
    const adminResponse=await fetch(base+'/api/local-console',{method:'POST',headers:{'X-FOSA-Console':'1'}});
    const {token}=await adminResponse.json();
    async function admin(path,body) {
      const response=await fetch(base+'/api/'+path,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
      assert.equal(response.status,200);return response.json();
    }
    chrome=await chromium.launch({args:['--no-sandbox','--no-proxy-server','--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream']});
    const operatorContext=await chrome.newContext({serviceWorkers:'block',viewport:{width:1200,height:900}});
    const operator=await operatorContext.newPage(),operatorErrors=[];
    operator.on('pageerror',e=>operatorErrors.push(e.message));
    await operator.goto(f.console+'&regisseur=1&view=live');
    await operator.locator('#pcMicrophones').waitFor({state:'visible'});
    const a=await musician(chrome,f.url,'TEST Android microphone','Chef',devices['Pixel 7']);
    const b=await musician(chrome,f.url,'TEST PC listener','Chant',{viewport:{width:1200,height:900}});
    const c=await musician(chrome,f.url,'TEST Android tablet listener','Clavier',{...devices['Pixel 7'],viewport:{width:800,height:1280}});
    for(const p of (await admin('state')).users) {
      p.mix.channels.forEach(ch=>ch.gain=0);await admin('mix',{id:p.id,mix:p.mix});
    }
    await signal(b.page,false);await signal(c.page,false);
    const androidPermission=operator.locator('[data-phone-mic="'+a.credentials.id+'"]');
    await androidPermission.waitFor({state:'visible'});
    await a.page.screenshot({path:output+'/android-microphone-blocked.png',fullPage:true});
    await androidPermission.check();
    await a.page.waitForFunction(()=>!document.querySelector('#liveEnableMic').disabled);
    await a.page.locator('#liveEnableMic').click();
    await a.page.waitForFunction(()=>!document.querySelector('#liveTalk').disabled,null,{timeout:12000});
    // The cloud is unavailable to these browser contexts after connection. PTT and mix still work.
    for(const musician of [a,b,c])await musician.context.routeWebSocket('wss://kgrrxhmzteefmdbgdbaf.supabase.co/**',ws=>ws.close());
    await press(a.page);await signal(b.page,true);await signal(c.page,true);await signal(a.page,false);
    await a.page.screenshot({path:output+'/secure-android-talk.png',fullPage:true});
    await release(a.page);await signal(b.page,false);await signal(c.page,false);
    await a.page.locator('#liveTalkTarget').selectOption(b.credentials.id);
    await press(a.page);await signal(b.page,true);await signal(c.page,false);await release(a.page);await signal(b.page,false);
    await a.page.locator('#liveTalkTarget').selectOption('role:Clavier');
    await press(a.page);await signal(c.page,true);await signal(b.page,false);
    await admin('matrix',{id:a.credentials.id,talkAllowed:false});
    await signal(c.page,false);await release(a.page);
    await a.page.waitForFunction(()=>window.testMicrophones.length>0&&window.testMicrophones.at(-1).getTracks().every(t=>t.readyState==='ended'));
    assert.equal(await a.page.locator('#liveTalk').isDisabled(),true);
    console.log('PASS: Chromium real getUserMedia with browser test input, all/person/role routing, release, revocation, no self-echo, cloud-independent live control.');

    // A real capture permission result can arrive after the user stops listening.
    await admin('matrix',{id:a.credentials.id,talkAllowed:true});
    await a.page.waitForFunction(()=>!document.querySelector('#liveEnableMic').disabled);
    await a.page.evaluate(()=>{
      const acquire=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
      window.testAcquireOriginal=acquire;
      navigator.mediaDevices.getUserMedia=async(...args)=>{const stream=await acquire(...args);await new Promise(resolve=>window.finishTestCapture=resolve);return stream};
    });
    await a.page.locator('#liveEnableMic').click();
    await a.page.waitForFunction(()=>typeof window.finishTestCapture==='function');
    await a.page.locator('.net-nav [data-view="mix"]').click();
    await a.page.locator('#stopAudio').click();
    await a.page.evaluate(()=>window.finishTestCapture());
    await a.page.waitForFunction(()=>window.testMicrophones.at(-1).getTracks().every(t=>t.readyState==='ended'));
    assert.equal(await a.page.locator('#talk').isDisabled(),true);
    console.log('PASS: late microphone permission cannot reopen a stopped session.');
    // An authorized phone can activate its microphone directly after stopping playback.
    await a.page.evaluate(()=>navigator.mediaDevices.getUserMedia=window.testAcquireOriginal);
    await a.page.locator('.net-nav [data-view="live"]').click();
    await a.page.waitForFunction(()=>!document.querySelector('#liveEnableMic').disabled);
    await a.page.locator('#liveEnableMic').click();
    await a.page.waitForFunction(()=>!document.querySelector('#liveTalk').disabled,null,{timeout:25000});
    await a.page.locator('.net-nav [data-view="mix"]').click();await a.page.locator('#stopAudio').click();

    safari=await webkit.launch();
    const ios=await musician(safari,f.url,'TEST WebKit iPhone microphone','Chant',devices['iPhone 13']);
    const iosState=(await admin('state')).users.find(p=>p.id===ios.credentials.id);
    iosState.mix.channels.forEach(ch=>ch.gain=0);await admin('mix',{id:ios.credentials.id,mix:iosState.mix});
    await operator.waitForFunction(()=>[...document.querySelectorAll('[data-phone-mic]')].some(el=>el.getAttribute('aria-label').includes('TEST WebKit iPhone microphone')));
    await operator.locator('#allowConnectedMics').click();
    await ios.page.waitForFunction(()=>!document.querySelector('#liveEnableMic').disabled);
    await ios.page.locator('#liveEnableMic').click();
    try {await ios.page.waitForFunction(()=>!document.querySelector('#liveTalk').disabled,null,{timeout:12000})}
    catch(error){console.error('WebKit microphone:',await ios.page.locator('#notice').textContent());throw error}
    await ios.page.locator('#liveTalkTarget').selectOption(b.credentials.id);
    await press(ios.page);await signal(b.page,true);await signal(c.page,false);
    await ios.page.screenshot({path:output+'/secure-iphone-talk.png',fullPage:true});
    await release(ios.page);await signal(b.page,false);
    // Revoking from the PC LIVE card must release the phone microphone too.
    const iosPermission=operator.locator('[data-phone-mic="'+ios.credentials.id+'"]');
    await operator.waitForFunction(id=>document.querySelector('[data-phone-mic="'+id+'"]').checked,ios.credentials.id);
    await iosPermission.uncheck();
    await ios.page.waitForFunction(()=>document.querySelector('#liveEnableMic').disabled&&window.testMicrophones.at(-1).getTracks().every(t=>t.readyState==='ended'));
    await operator.screenshot({path:output+'/pc-receiver-microphones.png',fullPage:true});
    const ipad=await musician(safari,f.url,'TEST WebKit iPad','Clavier',devices['iPad Pro 11']);
    await ipad.page.screenshot({path:output+'/secure-ipad.png',fullPage:true});
    for(const item of [a,b,c,ios,ipad])assert.deepEqual(item.errors,[]);
    assert.deepEqual(operatorErrors,[]);
    console.log('PASS: PC LIVE grants one Android microphone or all connected receivers, WebKit microphone activates, LIVE revocation ends capture, blocked phones show the PC authorization instruction, authorized Android can activate directly without another Listen action.');
    console.log('PASS: HTTPS QR + real encrypted relay + direct talkback in Chromium/WebKit; physical devices and MR18 untested.');
  } finally { await chrome?.close();await safari?.close();f.child.kill('SIGTERM'); }
}
module.exports={fixture,musician,signal,press,release};
if(require.main===module)main().catch(error=>{console.error(error);process.exitCode=1});
