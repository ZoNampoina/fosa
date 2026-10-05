// Control/UI integration only. Android service audio is tested on the emulator.
const assert=require('node:assert/strict');

module.exports=async function nativeControls(browser,url){
 const context=await browser.newContext({viewport:{width:412,height:915}});
 await context.addInitScript(()=>{
  window.nativeCalls=[];
  const status={connected:false,playback:false,packets:0,mic:false,panic:false,talk:false,
   lowLatencyMode:false,outputBufferMs:40,captureQueueMs:2,jitter:1,rtt:3,loss:0,buffer:20,output:'USB AUDIO / DAC'};
  window.FosaAndroid={
   start(server,token,target){window.nativeCalls.push({action:'start',server,token,target});status.connected=status.playback=true;return true},
   stop(){window.nativeCalls.push({action:'stop'});status.connected=status.playback=false},
   status(){if(status.connected)status.packets+=140;return JSON.stringify(status)},
   target(ms){window.nativeCalls.push({action:'target',ms})},
   panic(active){status.panic=active;window.nativeCalls.push({action:'panic',active})},
   control(talk,target){status.talk=talk;window.nativeCalls.push({action:'control',talk,target})},
   microphone(active){status.mic=active;window.nativeCalls.push({action:'microphone',active})}
  };
  localStorage.setItem('fosa_pcm_target','20');
 });
 const page=await context.newPage(),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 try{
  const address=new URL(url);address.searchParams.set('native','1');
  await page.goto(address.href);
  await page.locator('#profileName').fill('Native control fixture');
  await page.locator('#joinListen').click();
  await page.waitForFunction(()=>window.nativeCalls.some(c=>c.action==='start'));
  const credentials=await page.evaluate(()=>JSON.parse(localStorage.fosa_network_credentials)[location.origin]);
  const start=await page.evaluate(()=>window.nativeCalls.find(c=>c.action==='start'));
  assert.equal(start.server,address.origin);assert.equal(start.token,credentials.token);assert.equal(start.target,20);
  assert.equal(await page.locator('[data-panel=mix]').isVisible(),true);
  const saved=page.waitForResponse(r=>r.url().endsWith('/api/mix')&&r.request().method()==='POST');
  await page.locator('#monitorGain').evaluate(el=>{el.value='6';el.dispatchEvent(new Event('input',{bubbles:true}))});
  assert.equal((await (await saved).json()).mix.monitorGainDb,6,'Native controls must change the actual server mix');
  await page.locator('#panicMute').click();
  assert.ok(await page.evaluate(()=>window.nativeCalls.some(c=>c.action==='panic'&&c.active)));
  await page.locator('#releasePanic').click();
  await page.waitForFunction(()=>window.nativeCalls.some(c=>c.action==='panic'&&!c.active));
  await page.locator('.net-nav [data-view=settings]').click();
  for(const selector of ['#jitterTarget','#output','#refreshOutputs'])assert.equal(await page.locator(selector).isDisabled(),true);
  await page.locator('#latencyProfile').selectOption('safe');
  assert.ok(await page.evaluate(()=>window.nativeCalls.some(c=>c.action==='target'&&c.ms===20)));
  await page.locator('.net-nav [data-view=diagnostic]').click();
  await page.waitForFunction(()=>document.querySelector('#diagnostics').textContent.includes('USB AUDIO / DAC'));
  const metrics=await page.locator('#diagnostics .metric').evaluateAll(rows=>Object.fromEntries(rows.map(row=>[row.querySelector('span').textContent,row.querySelector('b').textContent])));
  assert.equal(metrics['Durée du bloc audio'],'5 ms');assert.equal(metrics['Buffer PCM ciblé'],'20 ms');
  assert.equal(metrics['Android Low Latency accordé'],'NON');
  assert.match(metrics['Latence audio bout-en-bout'],/UNKNOWN/);
  await page.locator('.net-nav [data-view=mix]').click();await page.locator('#stopAudio').click();
  assert.ok(await page.evaluate(()=>window.nativeCalls.some(c=>c.action==='stop')));
  assert.deepEqual(errors,[]);
  console.log('PASS: native UI boot, authenticated server mix, local panic dispatch, receive target and honest diagnostics. Native bridge mocked here; audio covered by Android instrumentation.');
 }finally{await context.close()}
};
