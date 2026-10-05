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
 const ctx=await browser.newContext({viewport:{width:390,height:844}});const pages=await Promise.all([ctx.newPage(),ctx.newPage(),ctx.newPage()]);const errors=[];pages.forEach(p=>p.on('pageerror',e=>errors.push(e.message)));
 await Promise.all(pages.map(p=>p.goto(base)));const [host,b,c]=pages;
 await host.screenshot({path:path.join(out,'mobile-web-home.png')});await host.click('#join');await host.screenshot({path:path.join(out,'mobile-web-join.png')});await host.click('#sheet .close');
 await host.evaluate(async()=>{
   const f=window.fosaMobile;window.fixture={members:[{id:'00000000-0000-0000-0000-000000000000',name:'ZO',role:'SAX',leader:true,group:'BAND',online:true,talk:false,level:null}],mail:new Map(),serial:0};
   const room=window.fixture;
   window.coordinator=(id,path,body)=>{const m=room.members.find(x=>x.id===id);if(path==='poll'){m.talk=!!body.talk;m.target=body.target;m.level=body.level;const q=room.mail.get(id)||[];room.mail.set(id,q.filter(s=>s.seq>body.after));return {sessionName:'BAND LIVE',members:room.members,signals:q.filter(s=>s.seq>body.after)};}if(path==='signal'){const q=room.mail.get(body.to)||[];q.push({seq:++room.serial,from:id,type:body.type,data:body.data});room.mail.set(body.to,q);return {ok:true};}return {ok:true};};
   f.profile={id:room.members[0].id,name:'ZO',role:'SAX',sessionName:'BAND LIVE'};f.host=f.profile.id;f.api=async(path,body={})=>window.coordinator(f.profile.id,path,body);
   f.wire=(l,dc)=>{l.channel=dc;dc.onmessage=e=>{const q=JSON.parse(e.data);const id=[...f.links].find(([,v])=>v===l)[0];dc.send(JSON.stringify({requestId:q.requestId,data:window.coordinator(id,q.path,q.body)}));};};
   f.microphoneReady=async()=>{f.context=new AudioContext({sampleRate:48000});await f.context.resume();const osc=f.context.createOscillator(),g=f.context.createGain(),dest=f.context.createMediaStreamDestination();osc.frequency.value=440;g.gain.value=.12;osc.connect(g).connect(dest);osc.start();f.microphone=dest.stream.getAudioTracks()[0];};await f.microphoneReady();
   f.loop=setInterval(()=>f.poll(),500);
 });
 for(const [page,id,name,role] of [[b,'10000000-0000-0000-0000-000000000000','JOHN','DRUMS'],[c,'20000000-0000-0000-0000-000000000000','NIA','BASS']]){
   const offer=await page.evaluate(async({name,role})=>{const f=window.fosaMobile;await f.prepare({name,role,code:'123456'});return f.links.get('host').pc.localDescription.sdp;},{name,role});
   await host.evaluate(async({id,name,role,sdp})=>{const f=window.fosaMobile;fixture.members.push({id,name,role,leader:false,group:'BAND',online:true,talk:false,level:null});f.make(id,true);await f.receive({from:id,type:'offer',data:{sdp}});},{id,name,role,sdp:offer});
   await pause(3800);
   const answer=await host.evaluate(async({id,name,role})=>{const {pack}=await import('./core.js');return pack({type:'answer',sdp:fosaMobile.links.get(id).pc.localDescription.sdp,host:fosaMobile.profile.id,profile:{id,name,role,token:'fixture-private-token',sessionName:'BAND LIVE'}});},{id,name,role});
   await page.evaluate(answer=>fosaMobile.accept(answer),answer);
 }
 console.log('Initial peers',await Promise.all(pages.map(p=>p.evaluate(()=>({error:fosaMobile.error,members:fosaMobile.members.length,links:[...fosaMobile.links].map(([id,l])=>({id,state:l.pc.connectionState,signaling:l.pc.signalingState,channel:l.channel?.readyState,candidates:l.pc.localDescription?.sdp.split('\r\n').filter(x=>x.startsWith('a=candidate:'))}))})))));
 await b.waitForFunction(()=>fosaMobile.links.size===2&&[...fosaMobile.links.values()].every(l=>l.pc.connectionState==='connected'),{timeout:25000});
 await c.waitForFunction(()=>fosaMobile.links.size===2&&[...fosaMobile.links.values()].every(l=>l.pc.connectionState==='connected'),{timeout:25000});
 for(const p of [b,c])await p.evaluate(async()=>{const f=window.fosaMobile;await f.context.resume();const l=[...f.links.values()].find(l=>l.audio);window.probe=new AudioContext();await probe.resume();const src=probe.createMediaStreamSource(l.audio.srcObject);window.meter=probe.createAnalyser();meter.fftSize=2048;src.connect(meter);window.rms=()=>{const a=new Float32Array(2048);meter.getFloatTimeDomainData(a);return Math.sqrt(a.reduce((s,x)=>s+x*x,0)/a.length);};});
 await host.evaluate(()=>{fosaMobile.destination('user:10000000-0000-0000-0000-000000000000');fosaMobile.push(true);});
 await b.waitForFunction(()=>rms()>.01,{timeout:10000});await pause(500);assert((await c.evaluate(()=>rms()))<.005,'Private PTT must remain silent on other user');
 await host.evaluate(()=>{fosaMobile.destination('all');fosaMobile.push(true);});await c.waitForFunction(()=>rms()>.01,{timeout:10000});
 // Block all HTTP after connection. Audio and RPC continue directly via ICE/SRTP.
 await ctx.route('**/*',route=>route.abort());await pause(500);assert((await b.evaluate(()=>rms()))>.01);assert((await c.evaluate(()=>rms()))>.01);
 await host.evaluate(()=>fosaMobile.push(false));await b.waitForFunction(()=>rms()<.005,{timeout:10000});
 await b.evaluate(()=>fosaMobile.panic(true));assert(await b.evaluate(()=>fosaMobile.muted&&[...fosaMobile.links.values()].every(l=>!l.audio||l.audio.muted)));await b.evaluate(()=>fosaMobile.panic(false));
 await ctx.unroute('**/*');await host.click('[data-tab="MEMBERS"]');await host.screenshot({path:path.join(out,'mobile-web-members.png')});await host.click('[data-tab="STATUS"]');await host.screenshot({path:path.join(out,'mobile-web-status.png')});await host.click('[data-tab="SETTINGS"]');await host.screenshot({path:path.join(out,'mobile-web-settings.png')});await host.click('#offline');await host.waitForSelector('#sheet-content .status');assert((await host.textContent('#sheet-content')).includes('READY'));await host.screenshot({path:path.join(out,'mobile-web-offline.png')});await host.click('#sheet .close');await host.click('[data-tab="TALK"]');await host.screenshot({path:path.join(out,'mobile-web-talk.png')});
 for(const size of [{width:320,height:700},{width:844,height:390},{width:1024,height:768}]){await host.setViewportSize(size);assert(await host.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));await host.screenshot({path:path.join(out,`mobile-web-${size.width}.png`)});}
 await ctx.setOffline(true);const cached=await ctx.newPage();await cached.goto(base);assert((await cached.textContent('h1')).includes('Your stage'));await cached.click('#offline');assert((await cached.textContent('#sheet-content')).includes('READY'));await cached.screenshot({path:path.join(out,'mobile-web-offline-reopen.png')});await ctx.setOffline(false);
 assert.deepEqual(errors,[]);await ctx.close();await browser.close();browser=null;
 // WebKit PWA cache and responsive UI; actual iPhone microphone/hardware not claimed.
 browser=await webkit.launch();const wc=await browser.newContext({viewport:{width:390,height:844}});const wp=await wc.newPage();await wp.goto(base);await wp.waitForFunction(()=>!!navigator.serviceWorker.controller);await wp.click('#offline');assert((await wp.textContent('#sheet-content')).includes('READY'));await wc.setOffline(true);await wp.reload();assert((await wp.textContent('h1')).includes('Your stage'));await wp.screenshot({path:path.join(out,'mobile-web-webkit-offline.png')});await wc.close();
 console.log('PASS: real direct Opus decoded, private/all PTT, local RPC without HTTP, panic, responsive screens, Chromium/WebKit offline shell. Phone hardware and iOS native not tested.');
}catch(e){if(browser){for(const context of browser.contexts())for(const p of context.pages())try{console.log('Failure peers',await p.evaluate(()=>({error:window.fosaMobile?.error,links:[...(window.fosaMobile?.links||[])].map(([id,l])=>({id,state:l.pc.connectionState,signaling:l.pc.signalingState,channel:l.channel?.readyState,local:l.pc.localDescription?.sdp.split('\r\n').filter(x=>x.startsWith('a=candidate:')),remote:l.pc.remoteDescription?.sdp.split('\r\n').filter(x=>x.startsWith('a=candidate:'))}))})));}catch{}}throw e;}finally{await browser?.close();server.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
