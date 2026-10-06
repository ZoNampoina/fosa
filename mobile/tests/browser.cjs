// Real browser WebRTC/Opus/QR protocol/cache verification. The phone coordinator
// is a test fixture here; native Android has separate instrumented tests.
const {chromium,webkit}=require('playwright');
const http=require('http'),fs=require('fs'),path=require('path'),assert=require('assert');
const out=process.env.FOSA_BROWSER_OUTPUT||'/tmp/fosa-mobile-check';fs.mkdirSync(out,{recursive:true});
const root=path.resolve(__dirname,'../..');
const server=http.createServer((q,r)=>{const name=decodeURIComponent(new URL(q.url,'http://localhost').pathname);const file=path.join(root,name.endsWith('/')?name+'index.html':name);if(!file.startsWith(root)||!fs.existsSync(file)){r.writeHead(404);return r.end();}r.setHeader('Content-Type',({'.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.json':'application/json','.html':'text/html'})[path.extname(file)]||'text/plain');r.end(fs.readFileSync(file));});
const pause=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{await new Promise(r=>server.listen(0,'0.0.0.0',r));const base=`http://127.0.0.1:${server.address().port}/mobile/`;let browser;
try {
 browser=await chromium.launch({args:['--autoplay-policy=no-user-gesture-required','--use-fake-ui-for-media-stream','--use-fake-device-for-media-stream']});
 const contexts=await Promise.all([1,2,3].map(()=>browser.newContext({viewport:{width:390,height:844}})));for(const context of contexts)await context.addInitScript(()=>{globalThis.FOSA_DISABLE_RENDEZVOUS=true;});const ctx=contexts[0],pages=await Promise.all(contexts.map(context=>context.newPage()));const errors=[];pages.forEach(p=>p.on('pageerror',e=>errors.push(e.message)));
 await Promise.all(pages.map(p=>p.goto(base)));const [host,b,c]=pages;
 await host.screenshot({path:path.join(out,'mobile-web-home.png')});await host.click('#join');assert.equal(await host.inputValue('[name="name"]'),'ZO');assert((await host.textContent('#sheet-content')).includes('SESSION CODE'));await host.screenshot({path:path.join(out,'mobile-web-join.png')});await host.click('#sheet .close');
 // The reported blank-code screen must explain the next step without starting capture.
 await b.click('#join');await b.click('#join-form button');assert((await b.textContent('#join-error')).includes('6 chiffres'));assert(await b.evaluate(()=>document.activeElement===document.querySelector('[name="code"]')&&fosaMobile.links.size===0&&!fosaMobile.raw));
 await b.fill('[name="code"]','12ab');await b.click('#join-form button');assert(await b.isVisible('#join-error'));assert(await b.isEnabled('#join-form button'));
 await b.fill('[name="code"]','123456');await b.fill('[name="name"]','   ');await b.click('#join-form button');assert((await b.textContent('#join-error')).includes('nom'));assert(await b.evaluate(()=>!fosaMobile.raw));
 assert(await b.evaluate(async()=>{try{await fosaMobile.prepare({name:'ZO',code:''});return false;}catch(e){return e.message.includes('6 chiffres')&&!fosaMobile.raw&&!fosaMobile.links.size;}}));
 await b.fill('[name="name"]','JOHN');await b.fill('[name="role"]','DRUMS');
 // Fail actual QR rendering once, after offer/capture: release resources and retain inputs.
 await b.evaluate(()=>{const draw=FosaQR.draw;FosaQR.draw=async()=>{FosaQR.draw=draw;window.failedAttemptTracks=[...fosaMobile.raw.getTracks(),fosaMobile.microphone];throw Error('QR rendering test failure');};});
 await b.click('#join-form button');await b.waitForFunction(()=>document.querySelector('#join-error')?.textContent.includes('QR rendering test failure'));assert.equal(await b.inputValue('[name="name"]'),'JOHN');assert.equal(await b.inputValue('[name="role"]'),'DRUMS');assert.equal(await b.inputValue('[name="code"]'),'123456');assert(await b.isEnabled('#join-form button'));assert(await b.evaluate(()=>fosaMobile.links.size===0&&!fosaMobile.context&&!fosaMobile.microphone&&failedAttemptTracks.every(t=>t.readyState==='ended')));await b.screenshot({path:path.join(out,'mobile-web-invitation-retry.png')});
 await host.evaluate(async()=>{
   const f=window.fosaMobile;window.fixture={members:[{id:'00000000-0000-0000-0000-000000000000',name:'ZO',role:'SAX',leader:true,group:'BAND',online:true,talk:false,level:null,generation:1}],mail:new Map(),serial:0};
   const room=window.fixture;
   window.coordinator=(id,path,body)=>{const m=room.members.find(x=>x.id===id);if(path==='poll'){m.talk=!!body.talk;m.target=body.target;m.level=body.level;const q=room.mail.get(id)||[];room.mail.set(id,q.filter(s=>s.seq>body.after));return {sessionName:'BAND LIVE',members:room.members,signals:q.filter(s=>s.seq>body.after)};}if(path==='signal'){const q=room.mail.get(body.to)||[];q.push({seq:++room.serial,from:id,generation:m.generation??1,type:body.type,data:body.data});room.mail.set(body.to,q);return {ok:true};}return {ok:true};};
   f.profile={id:room.members[0].id,name:'ZO',role:'SAX',sessionName:'BAND LIVE'};f.host=f.profile.id;f.api=async(path,body={})=>window.coordinator(f.profile.id,path,body);
   f.wire=(l,dc)=>{l.channel=dc;dc.onmessage=e=>{const q=JSON.parse(e.data);const id=[...f.links].find(([,v])=>v===l)[0];dc.send(JSON.stringify({requestId:q.requestId,data:window.coordinator(id,q.path,q.body)}));};};
   f.microphoneReady=async()=>{f.context=new AudioContext({sampleRate:48000});await f.context.resume();const osc=f.context.createOscillator(),g=f.context.createGain(),dest=f.context.createMediaStreamDestination();osc.frequency.value=440;g.gain.value=.12;osc.connect(g).connect(dest);osc.start();f.microphone=dest.stream.getAudioTracks()[0];};await f.microphoneReady();
   f.loop=setInterval(()=>f.poll(),500);
 });
 for(const [page,id,name,role] of [[b,'10000000-0000-0000-0000-000000000000','JOHN','DRUMS'],[c,'20000000-0000-0000-0000-000000000000','NIA','BASS']]){
   if(page===c)await page.click('#join');await page.fill('[name="name"]',name);await page.fill('[name="role"]',role);await page.fill('[name="code"]','123456');await page.click('#join-form button');await page.waitForSelector('#invite-qr');assert((await page.textContent('#sheet-content h2')).includes('Mode hors ligne'));
   const offer=await page.evaluate(async({name,role})=>{const {unpack,lanCandidate}=await import('./core.js');const url=new URL(document.querySelector('#native-link').href),q=await unpack(url.searchParams.get('data'));if(url.protocol!=='fosa:'||url.hostname!=='mobile-pair'||q.name!==name||q.role!==role||q.code!=='123456'||q.type!=='offer'||!q.sdp.includes('m=audio')||!q.sdp.includes('m=application'))throw Error('Invalid generated invitation');for(const l of q.sdp.split('\r\n'))if(l.startsWith('a=candidate:')&&!lanCandidate(l.slice(2)))throw Error('Non-LAN candidate');const canvas=document.querySelector('#invite-qr'),pixels=canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data;if(canvas.width<200||!pixels.some((v,i)=>i%4!==3&&v<128))throw Error('QR not rendered');return q;},{name,role});
   await page.screenshot({path:path.join(out,`mobile-web-invitation-${name.toLowerCase()}.png`)});
   await host.evaluate(async({id,name,role,offer})=>{const f=window.fosaMobile;fixture.members.push({id,name,role,leader:false,group:'BAND',online:true,talk:false,level:null,generation:1,clientKey:offer.clientKey});f.make(id,true);await f.receive({from:id,type:'offer',data:{sdp:offer.sdp}});},{id,name,role,offer});
   await pause(3800);
   const answer=await host.evaluate(async({id,name,role})=>{const {pack}=await import('./core.js');return pack({type:'answer',sdp:fosaMobile.links.get(id).pc.localDescription.sdp,host:fosaMobile.profile.id,profile:{id,name,role,token:'fixture-private-'+id,generation:1,session:'fixture-session',sessionName:'BAND LIVE'}});},{id,name,role});
   await page.fill('#answer',base+'#answer='+answer);await page.click('#accept');await page.waitForFunction(()=>!!fosaMobile.profile);assert.equal(await page.isVisible('#sheet'),false);
 }
 console.log('Initial peers',await Promise.all(pages.map(p=>p.evaluate(()=>({error:fosaMobile.error,members:fosaMobile.members.length,links:[...fosaMobile.links].map(([id,l])=>({id,state:l.pc.connectionState,signaling:l.pc.signalingState,channel:l.channel?.readyState,candidates:l.pc.localDescription?.sdp.split('\r\n').filter(x=>x.startsWith('a=candidate:'))}))})))));
 await b.waitForFunction(()=>fosaMobile.links.size===2&&[...fosaMobile.links.values()].every(l=>l.pc.connectionState==='connected'),{timeout:25000});
 await c.waitForFunction(()=>fosaMobile.links.size===2&&[...fosaMobile.links.values()].every(l=>l.pc.connectionState==='connected'),{timeout:25000});
 for(const p of [b,c])await p.evaluate(async()=>{const f=window.fosaMobile;await f.context.resume();const l=[...f.links.values()].find(l=>l.audio);window.probe=new AudioContext();await probe.resume();const src=probe.createMediaStreamSource(l.audio.srcObject);window.meter=probe.createAnalyser();meter.fftSize=2048;src.connect(meter);window.rms=()=>{const a=new Float32Array(2048);meter.getFloatTimeDomainData(a);return Math.sqrt(a.reduce((s,x)=>s+x*x,0)/a.length);};});
 await host.evaluate(()=>{fosaMobile.destination('user:10000000-0000-0000-0000-000000000000');fosaMobile.push(true);});
 await b.waitForFunction(()=>rms()>.01,{timeout:10000});await pause(500);assert((await c.evaluate(()=>rms()))<.005,'Private PTT must remain silent on other user');
 await host.evaluate(()=>{fosaMobile.destination('all');fosaMobile.push(true);});await c.waitForFunction(()=>rms()>.01,{timeout:10000});
 // Real touch PTT must remain active through pointer motion and poll redraws.
 await host.evaluate(()=>fosaMobile.push(false));await b.waitForFunction(()=>rms()<.005,{timeout:10000});
 await host.locator('#ptt').scrollIntoViewIfNeeded();const touch=await ctx.newCDPSession(host),box=await host.locator('#ptt').boundingBox();
 await touch.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:box.x+box.width/2,y:box.y+box.height/2}]});
 await host.waitForFunction(()=>fosaMobile.talking);await b.waitForFunction(()=>rms()>.01,{timeout:10000});
 await touch.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:box.x+box.width/2,y:box.y+box.height/2-55}]});
 await pause(1200);assert(await host.evaluate(()=>fosaMobile.talking),'A held touch must survive state updates and movement');
 await touch.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await host.waitForFunction(()=>!fosaMobile.talking);await b.waitForFunction(()=>rms()<.005,{timeout:10000});
 await host.focus('#ptt');await host.keyboard.down('Space');await b.waitForFunction(()=>rms()>.01,{timeout:10000});await host.keyboard.up('Space');await host.waitForFunction(()=>!fosaMobile.talking);
 await touch.detach();await host.evaluate(()=>fosaMobile.push(true));await b.waitForFunction(()=>rms()>.01,{timeout:10000});
 // Block all HTTP after connection. Audio and RPC continue directly via ICE/SRTP.
 await Promise.all(contexts.map(context=>context.route('**/*',route=>route.abort())));await pause(500);assert((await b.evaluate(()=>rms()))>.01);assert((await c.evaluate(()=>rms()))>.01);
 await host.evaluate(()=>fosaMobile.push(false));await b.waitForFunction(()=>rms()<.005,{timeout:10000});
 await b.evaluate(()=>fosaMobile.panic(true));assert(await b.evaluate(()=>fosaMobile.muted&&[...fosaMobile.links.values()].every(l=>!l.audio||l.audio.muted)));await b.evaluate(()=>fosaMobile.panic(false));
 await Promise.all(contexts.map(context=>context.unroute('**/*')));await host.click('[data-tab="MEMBERS"]');await host.screenshot({path:path.join(out,'mobile-web-members.png')});await host.click('[data-tab="STATUS"]');await host.screenshot({path:path.join(out,'mobile-web-status.png')});await host.click('[data-tab="SETTINGS"]');await host.screenshot({path:path.join(out,'mobile-web-settings.png')});await host.click('#offline');await host.waitForSelector('#sheet-content .status');assert.equal(await host.textContent('#sheet-content .status'),'READY');await host.screenshot({path:path.join(out,'mobile-web-offline.png')});await host.click('#sheet .close');await host.click('[data-tab="TALK"]');await host.screenshot({path:path.join(out,'mobile-web-talk.png')});
 for(const size of [{width:320,height:700},{width:375,height:667},{width:844,height:390},{width:1024,height:768}]){await host.setViewportSize(size);assert(await host.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));assert(await host.evaluate(()=>{const top=document.querySelector('nav').getBoundingClientRect().top;return ['ptt','panic'].every(id=>{const b=document.getElementById(id).getBoundingClientRect();return b.top>=0&&b.bottom<=top;});}),'Talk and Panic Mute must fit above navigation on phones and tablets');await host.screenshot({path:path.join(out,`mobile-web-${size.width}.png`)});}
 // A reloaded device rejoins by its private identity, preserving one roster row.
 const oldIdentity=await b.evaluate(()=>({id:fosaMobile.profile.id,token:fosaMobile.profile.token,key:JSON.parse(localStorage.getItem('fosa-lan-session:123456')).clientKey}));
 let codeAnswer='';
 await b.route('**/functions/v1/pair-rendezvous',async route=>{
   const headers={'access-control-allow-origin':'*','access-control-allow-headers':'apikey,content-type','access-control-allow-methods':'POST, OPTIONS'};
   if(route.request().method()==='OPTIONS'){await route.fulfill({status:204,headers});return;}
   const q=route.request().postDataJSON();
   if(q.action==='guest-offer'){
     codeAnswer=await host.evaluate(async({offer,identity})=>{
       const {unpack,pack}=await import('./core.js');const q=await unpack(offer);if(q.resumeToken!==identity.token||q.clientKey!==identity.key)throw Error('Missing private resume credential');
       const f=fosaMobile,m=fixture.members.find(m=>m.id===identity.id);m.generation++;m.online=true;m.talk=false;fixture.mail.set(m.id,[]);for(const [id,mail] of fixture.mail)fixture.mail.set(id,mail.filter(s=>s.from!==m.id));
       f.remove(m.id);f.members=fixture.members;f.make(m.id,true);await f.receive({from:m.id,type:'offer',data:{sdp:q.sdp}});
       await new Promise(r=>setTimeout(r,3000));return pack({type:'answer',sdp:f.links.get(m.id).pc.localDescription.sdp,host:f.profile.id,profile:{id:m.id,name:m.name,role:m.role,token:identity.token,generation:m.generation,session:'fixture-session',sessionName:'BAND LIVE'}});
     },{offer:q.offer,identity:oldIdentity});
     await route.fulfill({headers,json:{id:'resume-fixture'}});
   }else if(q.action==='guest-poll')await route.fulfill({headers,json:{answer:codeAnswer}});else throw Error('Unexpected rendezvous action');
 });
 await b.reload();await b.evaluate(()=>{globalThis.FOSA_DISABLE_RENDEZVOUS=false;window.realCapture=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);navigator.mediaDevices.getUserMedia=async()=>{throw new DOMException('Permission denied for test','NotAllowedError');};});await b.click('#join');await b.fill('[name="name"]','JOHN');await b.fill('[name="role"]','DRUMS');await b.fill('[name="code"]','123456');await b.click('#join-form button');
 await b.waitForFunction(()=>fosaMobile.profile?.generation===2&&fosaMobile.links.size===2&&[...fosaMobile.links.values()].every(l=>l.pc.connectionState==='connected'),null,{timeout:25000});
 await c.waitForFunction(()=>fosaMobile.links.size===2&&[...fosaMobile.links.values()].every(l=>l.pc.connectionState==='connected'),null,{timeout:25000});
 assert.equal(await b.evaluate(()=>fosaMobile.profile.id),oldIdentity.id);assert(await b.evaluate(()=>fosaMobile.members.length===3&&new Set(fosaMobile.members.map(m=>m.id)).size===3));
 assert(await b.evaluate(()=>!fosaMobile.microphone));assert.equal(await b.textContent('#enable'),'ENABLE MICROPHONE');assert(await b.isVisible('#enable'));
 await b.evaluate(()=>{navigator.mediaDevices.getUserMedia=window.realCapture;window.listeningPc=fosaMobile.links.get(fosaMobile.host).pc;});await b.click('#enable');
 await b.waitForFunction(()=>!!fosaMobile.microphone&&[...fosaMobile.links.values()].every(l=>l.track&&l.pc.signalingState==='stable'),null,{timeout:15000});
 assert(await b.evaluate(()=>fosaMobile.links.get(fosaMobile.host).pc===listeningPc),'Enabling microphone must preserve the listening/RPC link');
 await b.locator('#ptt').scrollIntoViewIfNeeded();const pttBox=await b.locator('#ptt').boundingBox();await b.mouse.move(pttBox.x+pttBox.width/2,pttBox.y+pttBox.height/2);await b.mouse.down();await b.waitForFunction(()=>fosaMobile.talking&&[...fosaMobile.links.values()].some(l=>l.track?.enabled));await b.mouse.up();await b.waitForFunction(()=>!fosaMobile.talking);

 await b.evaluate(async()=>{const f=fosaMobile,l=f.links.get(f.host);window.probe=new AudioContext();await probe.resume();const src=probe.createMediaStreamSource(l.audio.srcObject);window.meter=probe.createAnalyser();meter.fftSize=2048;src.connect(meter);window.rms=()=>{const a=new Float32Array(2048);meter.getFloatTimeDomainData(a);return Math.sqrt(a.reduce((s,x)=>s+x*x,0)/a.length);};});
 await host.evaluate(()=>{fosaMobile.destination('all');fosaMobile.push(true);});await b.waitForFunction(()=>rms()>.01,null,{timeout:10000});await host.evaluate(()=>fosaMobile.push(false));
 await ctx.setOffline(true);const cached=await ctx.newPage();await cached.goto(base);assert((await cached.textContent('h1')).includes('Your stage'));await cached.click('#offline');await cached.waitForSelector('#sheet-content .status');assert.equal(await cached.textContent('#sheet-content .status'),'READY');await cached.screenshot({path:path.join(out,'mobile-web-offline-reopen.png')});await ctx.setOffline(false);
 assert.deepEqual(errors,[]);await Promise.all(contexts.map(context=>context.close()));await browser.close();browser=null;
 // WebKit PWA cache and responsive UI; actual iPhone microphone/hardware not claimed.
 browser=await webkit.launch();const wc=await browser.newContext({viewport:{width:390,height:844}});await wc.addInitScript(()=>{globalThis.FOSA_DISABLE_RENDEZVOUS=true;});const wp=await wc.newPage();await wp.goto(base);await wp.waitForFunction(()=>!!navigator.serviceWorker.controller);await wp.click('#join');await wp.click('#join-form button');assert((await wp.textContent('#join-error')).includes('6 chiffres'));assert(await wp.evaluate(()=>!fosaMobile.raw&&!fosaMobile.links.size));await wp.screenshot({path:path.join(out,'mobile-web-webkit-code-help.png')});await wp.click('#sheet .close');await wp.click('#offline');await wp.waitForSelector('#sheet-content .status');assert.equal(await wp.textContent('#sheet-content .status'),'READY');await new Promise(resolve=>server.close(resolve));await wp.reload();assert((await wp.textContent('h1')).includes('Your stage'));await wp.screenshot({path:path.join(out,'mobile-web-webkit-offline.png')});await wc.close();
 console.log('PASS: code-first join form, offline QR fallback, blank/invalid code help, failed preparation cleanup and retry, real direct Opus decoded, real held-touch/keyboard PTT, microphone permission upgrade without closing the listening link, saved-identity reload with actual audio recovery and no duplicate members, private/all PTT, local RPC without HTTP, panic, responsive screens, Chromium/WebKit offline shell. Phone hardware and iOS native not tested.');
}catch(e){if(browser){for(const context of browser.contexts())for(const p of context.pages())try{console.log('Failure peers',await p.evaluate(()=>({error:window.fosaMobile?.error,links:[...(window.fosaMobile?.links||[])].map(([id,l])=>({id,state:l.pc.connectionState,signaling:l.pc.signalingState,channel:l.channel?.readyState,local:l.pc.localDescription?.sdp.split('\r\n').filter(x=>x.startsWith('a=candidate:')),remote:l.pc.remoteDescription?.sdp.split('\r\n').filter(x=>x.startsWith('a=candidate:'))}))})));}catch{}}throw e;}finally{await browser?.close();server.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
