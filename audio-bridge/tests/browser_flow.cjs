// Real HTTP / ICE / DTLS / SRTP / Opus / Web Audio test against browser_server.py.
// Mobile viewports and WebKit are browser coverage, not physical Android/iPhone validation.
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const {mkdirSync} = require('node:fs');
const {chromium, webkit, devices} = require('playwright');
const output = process.env.FOSA_BROWSER_OUTPUT || '/tmp/fosa-browser-output';
mkdirSync(output,{recursive:true});

async function fixture(){
 const child=spawn(process.env.PYTHON || 'python',['-u','audio-bridge/tests/browser_server.py'],{stdio:['ignore','pipe','inherit']});
 const config=await new Promise((resolve,reject)=>{
  let text='';const timer=setTimeout(()=>reject(Error('Fixture startup timeout')),15000);
  child.stdout.on('data',chunk=>{text+=chunk;const line=text.split('\n').find(s=>s.startsWith('{"url"'));if(line){clearTimeout(timer);resolve(JSON.parse(line))}});
  child.on('exit',code=>{clearTimeout(timer);reject(Error('Fixture exited '+code))});
 });
 return {child,...config};
}

async function newMusician(browser,url,name,device={}){
 const context=await browser.newContext({...device});
 await context.addInitScript(()=>{
  window.testConnections=[];const Original=window.RTCPeerConnection;
  if(Original)window.RTCPeerConnection=class extends Original{constructor(...args){super(...args);window.testConnections.push(this)}};
  const C=window.AudioContext||window.webkitAudioContext;
  if(C){const gain=C.prototype.createGain;C.prototype.createGain=function(){const g=gain.call(this),a=this.createAnalyser();a.fftSize=2048;g.connect(a);window.testOutput=a;return g}}
 });
 const page=await context.newPage(),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.goto(url);
 assert.equal(await page.evaluate(()=>isSecureContext),false,'Must exercise insecure LAN HTTP');
 assert.equal(await page.evaluate(()=>!!navigator.mediaDevices?.getUserMedia),false,'Receive must not need a microphone');
 assert.ok((await page.locator('#joinCode').inputValue()).length>0,'QR must prefill the code');
 await page.locator('#profileName').fill(name);
 const start=Date.now();await page.locator('#joinListen').click();
 await page.waitForFunction(()=>document.querySelector('#streamStatus').textContent==='AUDIO EN LECTURE',null,{timeout:25000});
 await page.waitForFunction(()=>{const a=window.testOutput;if(!a)return false;const samples=new Float32Array(a.fftSize);a.getFloatTimeDomainData(samples);return samples.some(x=>Math.abs(x)>.001)},null,{timeout:10000});
 assert.ok(Date.now()-start<30000,'Listen must connect within 30 seconds in this fixture');
 assert.equal(await page.locator('#liveTalk').isDisabled(),true,'HTTP talkback must be explicit and disabled');
 assert.ok((await page.locator('#liveTalkNote').textContent()).includes('Ce lien HTTP permet seulement l’écoute'));
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,'No horizontal overflow');
 assert.deepEqual(errors,[],'No browser runtime errors');
 console.log(JSON.stringify({device:name,listenMs:Date.now()-start,state:'audio packets received + Web Audio running'}));
 return {context,page,errors};
}
async function rms(page){return page.evaluate(()=>{const a=window.testOutput;if(!a)return 0;const samples=new Float32Array(a.fftSize);a.getFloatTimeDomainData(samples);return Math.sqrt(samples.reduce((sum,n)=>sum+n*n,0)/samples.length)})}
async function main(){
 const f=await fixture();let chrome,safari;
 try{
  chrome=await chromium.launch({args:['--no-sandbox','--no-proxy-server']});
  await require('./native_control_browser.cjs')(chrome,f.url);
  const adminContext=await chrome.newContext(),consolePage=await adminContext.newPage();
  const consoleErrors=[];consolePage.on('pageerror',e=>consoleErrors.push(e.message));
  await consolePage.goto(f.console);
  await consolePage.locator('#setupQr img').waitFor();
  assert.equal(await consolePage.locator('#scanInterfaces').isDisabled(),false);
  assert.deepEqual(consoleErrors,[]);
  await consolePage.screenshot({path:output+'/console.png',fullPage:true});
  const desktop=await newMusician(chrome,f.url,'PC client',{viewport:{width:1280,height:800}});
  const android=await newMusician(chrome,f.url,'Android viewport',devices['Pixel 7']);
  await android.page.screenshot({path:output+'/android.png',fullPage:true});
  await desktop.page.waitForFunction(()=>{const a=window.testOutput,s=new Float32Array(a.fftSize);a.getFloatTimeDomainData(s);return s.some(x=>Math.abs(x)>.001)});
  assert.ok(await rms(android.page)>.001,'Decoded Opus must reach the output');
  await desktop.page.locator('#liveMaster').focus();
  await desktop.page.locator('#liveMaster').press('Home');
  await desktop.page.waitForFunction(()=>{const a=window.testOutput,s=new Float32Array(a.fftSize);a.getFloatTimeDomainData(s);return !s.some(x=>Math.abs(x)>.0002)},{},{timeout:10000});
  assert.ok(await rms(android.page)>.001,'The other musician mix must stay audible');
  // Same profile and server mix survive reload; user gesture can be required again.
  const before=await android.page.evaluate(()=>JSON.parse(localStorage.fosa_network_credentials));
  await android.page.reload();
  await android.page.locator('[data-panel="live"]').waitFor({state:'visible'});
  await android.page.locator('[data-do="listen"]').click();
  await android.page.waitForFunction(()=>document.querySelector('#streamStatus').textContent==='AUDIO EN LECTURE',null,{timeout:25000});
  assert.deepEqual(await android.page.evaluate(()=>JSON.parse(localStorage.fosa_network_credentials)),before);
  // Force a broken transport, then verify the existing automatic retry creates a new one.
  await android.page.evaluate(()=>window.testConnections.at(-1).close());
  await android.page.waitForFunction(()=>window.testConnections.length>=2&&document.querySelector('#streamStatus').textContent==='AUDIO EN LECTURE',null,{timeout:25000});
  const tablet=await newMusician(chrome,f.url,'Android tablet viewport',{...devices['Pixel 7'],viewport:{width:800,height:1280}});
  await tablet.page.screenshot({path:output+'/tablet.png',fullPage:true});
  safari=await webkit.launch();
  const iphone=await newMusician(safari,f.url,'WebKit iPhone viewport',devices['iPhone 13']);
  await iphone.page.screenshot({path:output+'/iphone.png',fullPage:true});
  const ipad=await newMusician(safari,f.url,'WebKit iPad viewport',devices['iPad Pro 11']);
  await ipad.page.screenshot({path:output+'/ipad.png',fullPage:true});
  console.log('PASS: HTTP listen, decoded output, two isolated mixes, profile restore, reconnect, PC/mobile/tablet layout. Physical devices untested.');
 }finally{await chrome?.close();await safari?.close();f.child.kill('SIGTERM')}
}
main().catch(e=>{console.error(e);process.exitCode=1});
