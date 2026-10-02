(() => {
'use strict';
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const get = (k,d) => {try{return JSON.parse(localStorage.getItem(k))??d}catch{return d}};
const getText=k=>{try{return localStorage.getItem(k)||''}catch{return ''}};
const put = (k,v) => {try{localStorage.setItem(k,JSON.stringify(v))}catch{notice('Stockage local indisponible. Garde la session ouverte.')}};
const blank = () => ({master:.5,muteAll:false,ducking:-6,channels:Array.from({length:18},()=>({gain:.5,pan:0,mute:false,solo:false}))});
const normalize = value => {
 const m=blank(),num=(x,d,min,max)=>Number.isFinite(Number(x))?Math.max(min,Math.min(max,Number(x))):d;
 if(!value||!Array.isArray(value.channels)||value.channels.length!==18)return m;
 m.master=num(value.master,.5,0,1);m.muteAll=value.muteAll===true;m.ducking=[0,-3,-6,-12,-99].includes(value.ducking)?value.ducking:-6;
 m.channels=value.channels.map(c=>({gain:num(c?.gain,.5,0,1),pan:num(c?.pan,0,-1,1),mute:c?.mute===true,solo:false}));return m;
};
let server=get('fosa_network_server',''),credentials=get('fosa_network_credentials',{}),admin='',state=null,profile=null;
let mix=normalize(get('fosa_network_draft',blank())),page='audio',pc=null,mic=null,transceiver=null,micEnabled=false,talking=false;
let desired=false,connecting=false,connectingGeneration=0,reconnectAt=0,retry=0,changed=false,sending=false,polling=false,meterPolling=false,refreshId=0;
let localRevision=0, confirmedMixVersion=0;
let telemetry={rtt:null,jitter:null,loss:null,buffer:null},previousStats=null,wake=null,externalTalk=false,qrObjectUrl=null;
const audio=$('#monitorAudio'), query=new URLSearchParams(location.search);
const invite=new URLSearchParams(location.hash.slice(1));
const inviteValue=k=>invite.get(k)||query.get(k);
let outputContext=null,outputSource=null,outputGain=null,playbackReady=false,receivedPackets=0,lastPacketsAt=0;
let serverNetwork=null,setupQrUrl=null,joining=false;
let relayClient=null,direct=null,controlSequence=0,talkConfirmed=false,micPending=false,micRequest=0,regisseurCredentials=null;
let micDevice=get('fosa_talkback_device','');
let engine=inviteValue('engine')==='pcm'?'pcm':get('fosa_monitoring_engine','opus');
if(!['opus','pcm'].includes(engine))engine='opus';
let activeEngine=null,lowOutput=null,lowMetrics=null,previousLowStats=null;
const musician=query.has('musician');
const embedded=window.parent!==window;
const notice=t=>$('#notice').textContent=t;
function contextKey(){return 'fosa_network_mix:'+server+':'+(profile?.id||'draft')}
function token(){return admin||credentials[server]?.token||''}
function personalToken(){return admin?regisseurCredentials?.token||'':credentials[server]?.token||''}
async function prepareRegisseur(){
 const r=await fetch(server+'/api/regisseur',{method:'POST',headers:{Authorization:'Bearer '+admin,'X-FOSA-Console':'1'}});const b=await r.json();if(!r.ok)throw Error(b.error||'Profil régisseur PC indisponible');
 regisseurCredentials={token:b.token,id:b.profile.id};profile=b.profile;mix=normalize(profile.mix);changed=false;syncMix();paintStatus();
}
function setServer(value){
 if(relayClient){server='relay:'+relayClient.descriptor.relay;put('fosa_network_server',server);$('#serverUrl').value=server;return}
 const u=new URL(value);if(!['http:','https:'].includes(u.protocol)||u.username||u.password)throw Error('Adresse HTTP(S) invalide');
 const next=u.origin;if(server!==next){stopAudio();state=null;profile=null;admin=''}server=next;put('fosa_network_server',server);$('#serverUrl').value=server;
}
async function api(path,{body,auth=true,timeout=6000}={}){
 if(!server)throw Error('Renseigne l’adresse du PC serveur.');
 if(direct?.ready&&['health','state','meters','mix','control'].includes(path)&&(!admin||['health','meters','control'].includes(path)))return direct.request(path,body,timeout);
 if(relayClient){if(['control','meters'].includes(path))throw Error('Liaison locale directe indisponible');return relayClient.request(path,body,auth?token():'',Math.max(timeout,12000))}
 const headers={};if(auth)headers.Authorization='Bearer '+(['offer','control','disconnect'].includes(path)?personalToken():token());if(body!==undefined)headers['Content-Type']='application/json';
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),timeout);
 try{const r=await fetch(server+'/api/'+path,{method:body===undefined?'GET':'POST',headers,body:body===undefined?undefined:JSON.stringify(body),cache:'no-store',signal:controller.signal});
 const j=await r.json().catch(()=>({error:'Réponse du serveur illisible'}));if(!r.ok)throw Error(j.error||'Erreur serveur '+r.status);return j;}finally{clearTimeout(timer)}
}
function show(view){
 if(!$$('[data-panel]').some(x=>x.dataset.panel===view))view='audio';page=view;
 $$('[data-panel]').forEach(x=>x.hidden=x.dataset.panel!==view);$$('.net-nav [data-view]').forEach(x=>x.classList.toggle('selected',x.dataset.view===view));
 if(view==='talkback')renderTalkTargets();if(view==='mix')syncMix();if(view==='matrix')renderMatrix();if(view==='devices')renderDevices();if(view==='diagnostic')renderDiagnostic();
}
function networkType(){const t=navigator.connection?.type;return ({ethernet:'Ethernet',wifi:'Wi-Fi',cellular:'Données mobiles',bluetooth:'Bluetooth'})[t]||'Réseau inconnu'}
const fmt=(v,suffix=' ms')=>Number.isFinite(v)?v.toFixed(1)+suffix:'—';
function quality(){if(!pc||pc.connectionState!=='connected'||telemetry.jitter===null)return'EN ATTENTE';if(telemetry.loss>5||telemetry.jitter>30||telemetry.rtt>150)return'CRITIQUE';if(telemetry.loss>1||telemetry.jitter>15||telemetry.rtt>70)return'INSTABLE';return telemetry.jitter<5&&telemetry.loss===0&&telemetry.rtt!==null&&telemetry.rtt<20?'EXCELLENT':'BON'}
function paintStatus(){
 if(embedded)window.parent.postMessage({type:'fosa-network-state',active:desired},location.origin);const reachable=!!state;$('#bridgeStatus').textContent=reachable?(state.connected?'MR18 / INTERFACE CONNECTÉE':'BRIDGE CONNECTÉ · ENTRÉES ABSENTES'):'NON CONNECTÉ';$('#bridgeStatus').classList.toggle('online',!!state?.connected);
 const incoming=receivedPackets>0&&Date.now()-lastPacketsAt<3500;
 $('#streamStatus').textContent=pc?.connectionState==='connected'?(state?.connected?(playbackReady&&incoming?'AUDIO EN LECTURE':!playbackReady?'TOUCHE ÉCOUTER POUR LA LECTURE':'LIAISON PRÊTE · ATTENTE AUDIO'):'LIAISON PRÊTE · SOURCE ABSENTE'):connecting?'CONNEXION AUDIO…':desired?'RECONNEXION…':'AUDIO ARRÊTÉ';
 $('#netType').textContent=networkType();$('#liveQuality').textContent=quality();$('#liveQuality').style.color=['CRITIQUE','INSTABLE'].includes(quality())?'var(--amber)':'';
 $('#liveLatency').textContent=(activeEngine==='pcm'?'Low-Latency · PCM 5 ms · ':activeEngine==='opus'?'Stable · Opus · ':'')+'Latence audio : non mesurée';
 $('#captureSummary').textContent=state?.connected?`${state.device?.name} · ${state.device?.driver} · ${state.sampleRate} Hz · ${state.buffer} échantillons · ${state.inputs} entrées`:(state?.error||'NON CONNECTÉ · aucune entrée détectée');
 $('#pcRegisseur').hidden=!regisseurCredentials;$('#welcomeCard').hidden=!!regisseurCredentials;
 $('#scanInterfaces').disabled=!admin;$('#startCapture').disabled=!admin;$('#interfaceSelect').disabled=!admin;
 $$('#master,#liveMaster,#muteAll,#liveMute,#ducking,#applyPreset,#restoreMix,#resetMix').forEach(el=>el.disabled=profile?.locked===true);$('#mixScope').textContent=profile?`${profile.name} · ${profile.role}${profile.locked?' · VERROUILLÉ':''}`:'Réglages locaux';
 const micAvailable=!!navigator.mediaDevices?.getUserMedia;
 const micNote=!micAvailable?'Écoute seule : scanne le QR « Écoute + talkback » du PC.':!profile?.talkAllowed?'Le régisseur doit autoriser ton micro dans MATRIX.':micPending?(direct?.ready?'Autorise le microphone dans la demande du navigateur.':'Préparation de la liaison talkback du PC…'):!direct?.ready?(regisseurCredentials?'Micro régisseur autorisé. Touche ACTIVER MON MICRO pour ouvrir la liaison PC.':'Touche ÉCOUTER pour établir la liaison locale.'):!micEnabled?'Touche ACTIVER MON MICRO, puis maintiens TALK.':talking?(talkConfirmed?'PAROLE TRANSMISE · '+$('#talkTarget').selectedOptions[0]?.textContent:'Ouverture du talkback…'):'Micro prêt · maintiens TALK pour parler.';
 $$('#liveTalkNote,#talkNote').forEach(el=>el.textContent=micNote);
 $$('#enableMic,#liveEnableMic').forEach(b=>{b.textContent=!micAvailable?'MICRO · LIEN SÉCURISÉ REQUIS':micPending?(direct?.ready?'AUTORISATION EN COURS…':'PRÉPARATION DU MICRO…'):micEnabled?'COUPER MON MICRO':'ACTIVER MON MICRO';b.disabled=!micAvailable||!profile?.talkAllowed||(!direct?.ready&&!micEnabled&&!regisseurCredentials)||micPending});
 $$('.talk').forEach(b=>{b.disabled=!micAvailable||!profile?.talkAllowed||!micEnabled||!direct?.ready;b.classList.toggle('talking',talking&&talkConfirmed);b.textContent=talking?(talkConfirmed?'PAROLE TRANSMISE':'CONNEXION TALK…'):'MAINTENIR TALK POUR PARLER'});
 $('#mixNotice').textContent=changed?'Modifications locales en attente du serveur.':profile?'Mix enregistré sur cet appareil et sur le serveur LAN.':'Réglages locaux : rejoins le serveur pour les appliquer au son.';
}
function buildMixer(){
 $('#mixer').innerHTML=Array.from({length:18},(_,i)=>`<article class="channel" data-channel="${i}"><span class="num">${String(i+1).padStart(2,'0')}</span><strong data-channel-name>CH ${String(i+1).padStart(2,'0')}</strong><output data-volume>50 %</output><div class="fader"><button data-step="-5" data-i="${i}" aria-label="Diminuer canal ${i+1}">−</button><input data-gain="${i}" type="range" min="0" max="100" value="50" aria-label="Volume canal ${i+1}"><button data-step="5" data-i="${i}" aria-label="Augmenter canal ${i+1}">+</button></div><div class="ch-actions"><button data-mute="${i}" aria-label="Mute canal ${i+1}" aria-pressed="false">M</button><button data-solo="${i}" aria-label="Solo temporaire canal ${i+1}" aria-pressed="false">S</button><label class="pan">PAN<input data-pan="${i}" type="range" min="-100" max="100" value="0" aria-label="Panoramique canal ${i+1}"></label></div><div class="meter"><i></i></div><span class="reading">NON CONNECTÉ</span></article>`).join('');
 $('#channelNames').innerHTML=Array.from({length:18},(_,i)=>`<div class="channel-name"><span>CH ${String(i+1).padStart(2,'0')}</span><input data-name="${i}" value="CH ${String(i+1).padStart(2,'0')}" maxlength="60" aria-label="Nom canal ${i+1}" disabled><input data-role="${i}" placeholder="Type : basse, chant…" maxlength="60" aria-label="Type canal ${i+1}" disabled><button data-rename="${i}" disabled>OK</button></div>`).join('');
 $$('.meter').forEach(m=>m.insertAdjacentHTML('beforeend','<b aria-hidden="true"></b>'));
 $('#physicalInput').innerHTML=Array.from({length:18},(_,i)=>`<option value="${i+1}">Entrée ${i+1}</option>`).join('');
}
function syncMix(){
 $('#master').value=$('#liveMaster').value=Math.round(mix.master*100);$('#masterValue').value=$('#liveMasterValue').value=Math.round(mix.master*100)+' %';$('#ducking').value=mix.ducking;
 $('#muteAll').classList.toggle('active',mix.muteAll);$('#liveMute').classList.toggle('active',mix.muteAll);
 $$('.channel').forEach((el,i)=>{const c=mix.channels[i];el.querySelector('[data-gain]').value=Math.round(c.gain*100);el.querySelector('[data-pan]').value=Math.round(c.pan*100);el.querySelector('[data-volume]').value=Math.round(c.gain*100)+' %';for(const key of ['mute','solo']){const b=el.querySelector('[data-'+key+']');b.classList.toggle('active',c[key]);b.setAttribute('aria-pressed',String(c[key]))}const locked=profile?.locked===true||profile?.allowed?.[i]===false;el.classList.toggle('unassigned',locked);el.querySelectorAll('input,button').forEach(b=>b.disabled=locked)});
}
function updateMeters(){
 $$('.channel').forEach((el,i)=>{const c=state?.channels?.[i],name=c?.name||`CH ${String(i+1).padStart(2,'0')}`;el.querySelector('[data-channel-name]').textContent=name;el.classList.toggle('clipping',!!c?.clipping);el.querySelector('.meter i').style.width=(c?.active?Math.max(0,Math.min(100,(c.rmsDb+60)/60*100)):0)+'%';el.querySelector('.reading').textContent=!c?.active?'NON CONNECTÉ':c.clipping?'CLIPPING':`${c.signal?'Signal':'Silence'} · ${fmt(c.rmsDb,' dBFS')} · peak ${fmt(c.peakDb,' dBFS')}`;
 const n=$(`[data-name="${i}"]`),r=$(`[data-role="${i}"]`);if(document.activeElement!==n)n.value=name;if(document.activeElement!==r)r.value=c?.role||'';n.disabled=r.disabled=$(`[data-rename="${i}"]`).disabled=!admin;
 });
 $$('.channel').forEach((el,i)=>{const c=state?.channels?.[i];el.querySelector('.meter b').style.left=(c?.active?Math.max(0,Math.min(99,(c.peakDb+60)/60*100)):0)+'%'});
}
let persistTimer;
function edit(){localRevision++;changed=true;syncMix();const durable=normalize(mix);put(contextKey(),durable);if(!profile)put('fosa_network_draft',durable);clearTimeout(persistTimer);persistTimer=setTimeout(flush,120);paintStatus()}
async function flush(){
 if(sending||!changed||!profile||!state)return;sending=true;const sent=JSON.stringify(mix);
 try{const ack=await api('mix',{body:admin?{id:profile.id,mix}:{mix}});confirmedMixVersion=ack.mixVersion||0;if(JSON.stringify(mix)===sent)changed=false}catch(e){notice(e.message)}finally{sending=false;paintStatus();if(changed&&JSON.stringify(mix)!==sent)flush()}
}
async function pollMeters(){
 if(!token()||!state||meterPolling||document.hidden||!['mix','live','diagnostic'].includes(page))return;
 meterPolling=true;
 try{
  const m=await api('meters',{timeout:1500});
  state.connected=!!m.connected;
  if(Array.isArray(m.channels)&&Array.isArray(state.channels)){
   state.channels=state.channels.map((c,i)=>Object.assign({},c,m.channels[i]||{}));
  }
  paintStatus();updateMeters();if(page==='diagnostic')renderMapping();
 }catch{}
 finally{meterPolling=false}
}
async function refresh(){
 if(!token()||polling)return;polling=true;
 try{const revision=localRevision,dirtyAtStart=changed||sending;const next=await api('state');state=next;profile=admin&&regisseurCredentials?next.users?.find(p=>p.id===regisseurCredentials.id)||null:next.profile;
  if(profile){if(!profile.talkAllowed&&(micEnabled||micPending))await disableMic();if(!dirtyAtStart&&!changed&&!sending&&revision===localRevision&&(profile.mixVersion||0)>=confirmedMixVersion&&JSON.stringify(profile.mix)!==JSON.stringify(mix)){mix=profile.mix;syncMix()}if(changed&&!profile.locked)await flush()}
  paintStatus();updateMeters();if(page==='diagnostic')renderDiagnostic();if(page==='devices')renderDevices();
  if(profile?.locked){mix=profile.mix;changed=false;syncMix()}if(page==='matrix'&&document.activeElement?.closest('#matrix')==null)renderMatrix();renderTalkTargets();
  if(admin)await updateSetup();
 }catch(e){state=null;paintStatus();updateMeters();if(page==='diagnostic')renderDiagnostic();if(desired)notice('Serveur momentanément inaccessible. Le dernier mix est conservé.');}
 finally{polling=false}
}
async function detect(){
 try{setServer($('#serverUrl').value.trim()||server||'http://127.0.0.1:8765');const h=await api('health',{auth:false,timeout:2500});if(h.service!=='fosa-audio-bridge'||h.protocol!==1)throw Error('Ce serveur n’est pas un bridge FOSA compatible.');$('#detectInfo').textContent='Bridge détecté · '+h.version;notice('Bridge trouvé. Rejoins avec le code affiché sur le PC.');await refresh()}
 catch(e){$('#detectInfo').textContent='NON CONNECTÉ';notice('PC serveur inaccessible. Vérifie que FOSA reste lancé et que les appareils utilisent le même réseau Wi-Fi. Un Wi-Fi invité, l’isolation des clients ou un VPN peut bloquer l’accès. '+e.message)}
}
async function join(listen=false){
 if(joining)return;joining=true;if(listen)unlockOutput();$('#joinListen').disabled=true;
 try{setServer($('#serverUrl').value.trim()||server);if(regisseurCredentials){stopAudio();regisseurCredentials=null}admin='';const b=await api('join',{auth:false,body:{code:$('#joinCode').value.trim().toUpperCase(),session:inviteValue('session'),name:$('#profileName').value.trim()||'Musicien',role:$('#profileRole').value}});credentials[server]={token:b.token,id:b.profile.id};put('fosa_network_credentials',credentials);profile=b.profile;mix=normalize(b.profile.mix);changed=false;await refresh();syncMix();show('live');if(invite.has('code')){const clean=new URLSearchParams(location.hash.slice(1));clean.delete('code');history.replaceState(null,'',location.pathname+location.search+(relayClient?'#'+clean.toString():''))}notice('Profil rejoint.');if(listen)await startAudio()}
 catch(e){notice('Connexion impossible : '+e.message)}finally{joining=false;$('#joinListen').disabled=false}
}
async function adminLogin(){try{setServer($('#serverUrl').value.trim()||server);admin=$('#adminKey').value.trim();const s=await api('state');if(!s.admin)throw Error('Clé régisseur incorrecte');stopAudio();regisseurCredentials=null;state=s;profile=null;$('#adminKey').value='';if(server===location.origin&&['127.0.0.1','localhost'].includes(location.hostname))await prepareRegisseur();paintStatus();await scanInterfaces();notice('Console régisseur ouverte. Les permissions sont vérifiées par le bridge.')}catch(e){admin='';notice(e.message)}}
async function scanInterfaces(){try{const b=await api('devices');$('#interfaceSelect').innerHTML=b.devices.length?b.devices.map(d=>`<option value="${d.id}" ${d.inputs<18||d.usable===false?'disabled':''}>${esc(d.name)} · ${esc(d.driver)} · ${d.inputs} IN${d.usable===false?' · ASIO REQUIS':''}</option>`).join(''):'<option>Aucune interface audio détectée</option>';const preferred=b.devices.find(d=>d.usable!==false&&d.mr18&&d.asio)||b.devices.find(d=>d.usable!==false&&d.mr18)||b.devices.find(d=>d.usable!==false&&d.inputs>=18);if(preferred)$('#interfaceSelect').value=preferred.id;else if(b.devices.some(d=>d.mr18&&d.usable===false))notice('MR18 détectée uniquement via MME/WDM. Installe ou active le pilote ASIO Midas pour accéder aux 18 canaux séparés.');}catch(e){notice(e.message)}}
async function waitIce(connection){if(connection.iceGatheringState==='complete')return;await new Promise((resolve,reject)=>{const timer=setTimeout(()=>{connection.removeEventListener('icegatheringstatechange',done);reject(Error('Délai de collecte réseau dépassé'))},8000);function done(){if(connection.iceGatheringState==='complete'){clearTimeout(timer);connection.removeEventListener('icegatheringstatechange',done);resolve()}}connection.addEventListener('icegatheringstatechange',done)})}
function tuneStereo(sdp){return sdp.replace(/a=fmtp:(\d+) ([^\r\n]+)/g,(line,pt,params)=>new RegExp('a=rtpmap:'+pt+' opus/','i').test(sdp)?`a=fmtp:${pt} ${params};stereo=1;sprop-stereo=1;maxaveragebitrate=128000`:line)}
async function startAudio(){
 unlockOutput();if(connecting)return;if(!profile||(admin&&!regisseurCredentials)){notice('Rejoins le bridge avec un profil musicien.');show('audio');return}if(pc?.connectionState==='connected'){return}
 if(typeof RTCPeerConnection!=='function'){notice('WebRTC absent de ce navigateur. Ouvre ce lien dans Chrome sur Android ou Safari sur iPhone.');return}
 if(engine==='pcm'&&(!isSecureContext||!outputContext?.audioWorklet||!state?.monitoringEngines?.pcm)){notice('Low-Latency indisponible : utilise le QR sécurisé, un navigateur récent et le bridge v0.9.6. Le mode Stable reste disponible.');return}
 desired=true;put('fosa_network_resume:'+server,true);connecting=true;const generation=++connectingGeneration;activeEngine=engine;
 let next;
 try{stopTalk();pc?.close();lowOutput?.close();lowOutput=null;outputSource?.disconnect();outputSource=null;audio.srcObject=null;lowMetrics=null;previousLowStats=null;direct=null;previousStats=null;receivedPackets=0;lastPacketsAt=0;next=new RTCPeerConnection({iceServers:[]});pc=next;transceiver=next.addTransceiver('audio',{direction:navigator.mediaDevices?.getUserMedia?'sendrecv':'recvonly'});if(micEnabled&&mic)await transceiver.sender.replaceTrack(mic.getAudioTracks()[0]);
 if(activeEngine==='pcm'){
  const receiver=new FosaLowLatency(outputContext,outputGain,{targetMs:Number($('#pcmTarget').value),onPacket:()=>{if(pc!==next)return;receivedPackets++;lastPacketsAt=Date.now()},onMetrics:m=>{if(pc!==next)return;lowMetrics=m},onError:message=>{if(pc!==next)return;notice(message);stopAudio()}});
  lowOutput=receiver;await receiver.prepare();if(generation!==connectingGeneration||!desired){receiver.close();next.close();return}
  receiver.attach(next.createDataChannel('fosa-pcm-v1',{ordered:false,maxRetransmits:0}));
 }
 const channel=next.createDataChannel('fosa-control',{ordered:true});const rpc=new FosaRpcChannel(channel);
 channel.onopen=()=>{if(pc!==next)return;direct=rpc;relayClient?.pause();sendControl();paintStatus()};channel.onclose=()=>{if(direct===rpc){direct=null;stopTalk();paintStatus()}};
 const opus=RTCRtpReceiver.getCapabilities?.('audio')?.codecs.filter(c=>c.mimeType.toLowerCase()==='audio/opus');if(opus?.length)transceiver.setCodecPreferences(opus);
 next.ontrack=e=>{if(pc!==next||activeEngine==='pcm')return;attachOutput(e.streams[0]||new MediaStream([e.track]));applyJitter();};
 next.onconnectionstatechange=()=>{if(pc!==next)return;paintStatus();if(next.connectionState==='connected'){retry=0;notice(state?.connected?'Liaison établie. Réception du mix…':'Liaison prête, en attente de la MR18.')}if(['failed','disconnected'].includes(next.connectionState)){reconnectAt=Date.now()+Math.min(8000,1000*2**Math.min(retry++,3));notice('Audio interrompu. Reconnexion automatique ; si cela persiste, vérifie le Wi-Fi et le diagnostic du PC.')}};
 const offer=await next.createOffer();offer.sdp=tuneStereo(offer.sdp);await next.setLocalDescription(offer);await waitIce(next);
 if(generation!==connectingGeneration||!desired){next.close();return}
 const answer=await api('offer',{body:{type:'offer',sdp:next.localDescription.sdp,monitoringEngine:activeEngine,device:navigator.userAgent.slice(0,100)},timeout:15000});
 if(generation!==connectingGeneration||!desired){next.close();return}if(activeEngine==='pcm'&&answer.monitoringEngine!=='pcm')throw Error('Le bridge ne confirme pas le moteur PCM');await next.setRemoteDescription({type:answer.type,sdp:answer.sdp});setupMediaSession();
 }catch(e){if(generation===connectingGeneration){notice('Audio non connecté : '+e.message+'. Si la page est accessible mais pas le son, le trafic UDP WebRTC peut être bloqué.');next?.close();lowOutput?.close();lowOutput=null;pc=null;reconnectAt=Date.now()+4000}}finally{if(generation===connectingGeneration)connecting=false;paintStatus()}
}
function stopAudio(){desired=false;connectingGeneration++;connecting=false;put('fosa_network_resume:'+server,false);stopTalk();lowOutput?.close();lowOutput=null;lowMetrics=null;activeEngine=null;pc?.close();pc=null;direct=null;transceiver=null;outputSource?.disconnect();outputSource=null;audio.srcObject=null;receivedPackets=0;playbackReady=false;telemetry={rtt:null,jitter:null,loss:null,buffer:null};micRequest++;mic?.getTracks().forEach(t=>t.stop());mic=null;micEnabled=false;micPending=false;if(token())api('disconnect',{body:{}}).catch(()=>{});paintStatus()}
// Resume Web Audio in the user's original gesture, before joining/ICE/network awaits.
// This receive-only path never opens a microphone and works on an HTTP LAN origin.
function unlockOutput(){
 try{const C=window.AudioContext||window.webkitAudioContext;if(!C)return;
  if(!outputContext){try{outputContext=new C({latencyHint:'interactive',sampleRate:48000})}catch{outputContext=new C({latencyHint:'interactive'})};outputGain=outputContext.createGain();outputGain.connect(outputContext.destination);outputContext.onstatechange=()=>{lowOutput?.reset();playbackReady=outputContext.state==='running';paintStatus()}}
  outputContext.resume().then(()=>{playbackReady=outputContext.state==='running';paintStatus()}).catch(()=>notice('Touche ÉCOUTER pour autoriser la sortie audio.'));
 }catch(e){notice('Sortie audio : '+e.message)}
}
function attachOutput(stream){
 outputSource?.disconnect();audio.srcObject=stream;
 if(outputContext){audio.muted=true;outputSource=outputContext.createMediaStreamSource(stream);outputSource.connect(outputGain);audio.play().catch(()=>{});playbackReady=outputContext.state==='running'}
 else{audio.muted=false;audio.play().then(()=>{playbackReady=true;paintStatus()}).catch(()=>notice('Audio reçu : touche ÉCOUTER pour autoriser la lecture.'))}
}
function applyJitter(){for(const r of pc?.getReceivers()||[])if('jitterBufferTarget'in r)try{r.jitterBufferTarget=Number($('#jitterTarget').value)}catch{}}
async function enableMic(){
 if(micEnabled){await disableMic();return}if(micPending)return;
 if(!profile?.talkAllowed){notice('Le régisseur doit autoriser ton micro dans MATRIX.');return}if(!navigator.mediaDevices?.getUserMedia){notice('Scanne le QR « Écoute + talkback » affiché par le PC.');return}
 if(regisseurCredentials&&!direct?.ready){
  unlockOutput();const preparation=++micRequest;micPending=true;paintStatus();
  try{await startAudio();const deadline=Date.now()+8000;while(!direct?.ready&&desired&&Date.now()<deadline&&preparation===micRequest)await new Promise(r=>setTimeout(r,50));if(preparation!==micRequest||!profile?.talkAllowed)return;if(!direct?.ready)throw Error('La liaison talkback du PC ne répond pas. Relance le bridge et réessaie.')}catch(e){notice(e.message);return}finally{if(preparation===micRequest)micPending=false;paintStatus()}
 }
 if(!direct?.ready||!transceiver){notice('Touche ÉCOUTER avant d’activer ton micro.');return}
 const request=++micRequest,sender=transceiver.sender;micPending=true;paintStatus();let captured;
 try{captured=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:false,channelCount:1,...(micDevice?{deviceId:{exact:micDevice}}:{})}});captured.getTracks().forEach(t=>t.enabled=false);if(request!==micRequest||sender!==transceiver?.sender||!profile?.talkAllowed||!direct?.ready){captured.getTracks().forEach(t=>t.stop());return}await sender.replaceTrack(captured.getAudioTracks()[0]);if(request!==micRequest||sender!==transceiver?.sender||!profile?.talkAllowed||!direct?.ready){captured.getTracks().forEach(t=>t.stop());await sender.replaceTrack(null);return}mic=captured;micEnabled=true;mic.getAudioTracks()[0].onended=()=>{disableMic();notice('Micro interrompu par le système. Active-le à nouveau pour parler.')};notice('Micro prêt : '+(mic.getAudioTracks()[0].label||'micro système')+'. Maintiens TALK pour parler.');await microphoneInputs()}
 catch(e){captured?.getTracks().forEach(t=>t.stop());if(request===micRequest)notice(e.name==='NotAllowedError'?'Micro refusé. Autorise le microphone pour ce site dans le navigateur, puis réessaie.':e.name==='NotFoundError'?'Aucun microphone disponible sur cet appareil.':e.name==='NotReadableError'?'Ce micro est occupé ou bloqué par Windows. Choisis un autre micro (casque, USB ou intégré), puis réessaie.':e.name==='OverconstrainedError'?'Le micro choisi n’est plus disponible. Sélectionne Micro système puis réessaie.':'Micro : '+e.message)}finally{if(request===micRequest)micPending=false;await microphoneInputs();paintStatus()}
}
async function disableMic(){micRequest++;stopTalk();mic?.getTracks().forEach(t=>t.stop());mic=null;micEnabled=false;micPending=false;try{await transceiver?.sender.replaceTrack(null)}catch{}paintStatus()}
function startTalk(e){if(!micEnabled||!profile?.talkAllowed||!direct?.ready){notice('Active d’abord ton micro après autorisation du régisseur.');return}e?.preventDefault();if(e?.pointerId!==undefined)e.currentTarget?.setPointerCapture?.(e.pointerId);talking=true;talkConfirmed=false;mic?.getTracks().forEach(t=>t.enabled=true);sendControl();paintStatus()}
function stopTalk(){if(!talking)return;talking=false;talkConfirmed=false;mic?.getTracks().forEach(t=>t.enabled=false);sendControl();paintStatus()}
function sendControl(){if(!desired||!profile||!state)return;const sequence=++controlSequence;api('control',{body:{sequence,talk:talking,target:$('#talkTarget').value,metrics:{...telemetry,network:networkType(),audioLatency:null,playback:playbackReady,packets:receivedPackets,pcm:activeEngine==='pcm'?lowMetrics:null}},timeout:2000}).then(reply=>{if(sequence!==controlSequence)return;talkConfirmed=talking&&reply.talkActive===true;if(!reply.talkAllowed&&(micEnabled||micPending))disableMic();paintStatus()}).catch(()=>{if(talking){stopTalk();notice('TALK coupé : liaison locale interrompue.')}})}
async function stats(){if(!pc||pc.connectionState!=='connected')return;try{const rows=await pc.getStats();let pair,rtp;rows.forEach(r=>{if(r.type==='transport'&&r.selectedCandidatePairId)pair=rows.get(r.selectedCandidatePairId);if(r.type==='inbound-rtp'&&r.kind==='audio')rtp=r});if(pair)telemetry.rtt=Number.isFinite(pair.currentRoundTripTime)?pair.currentRoundTripTime*1000:null;if(activeEngine==='pcm'&&lowMetrics){telemetry.jitter=lowMetrics.jitterMs;telemetry.buffer=lowMetrics.bufferedMs;if(previousLowStats){const missing=Math.max(0,lowMetrics.missing-previousLowStats.missing),received=Math.max(0,lowMetrics.received-previousLowStats.received);telemetry.loss=missing+received>0?Math.min(100,missing/(missing+received)*100):null}previousLowStats={missing:lowMetrics.missing,received:lowMetrics.received}}else if(rtp){if((rtp.packetsReceived||0)>receivedPackets)lastPacketsAt=Date.now();receivedPackets=rtp.packetsReceived||0;telemetry.jitter=Number.isFinite(rtp.jitter)?rtp.jitter*1000:null;if(previousStats&&previousStats.id===rtp.id){const lost=Math.max(0,(rtp.packetsLost||0)-previousStats.lost),received=Math.max(0,(rtp.packetsReceived||0)-previousStats.received),n=(rtp.jitterBufferEmittedCount||0)-previousStats.emitted;telemetry.loss=lost+received>0?lost/(lost+received)*100:null;telemetry.buffer=n>0?((rtp.jitterBufferDelay||0)-previousStats.delay)/n*1000:null}previousStats={id:rtp.id,lost:rtp.packetsLost||0,received:rtp.packetsReceived||0,emitted:rtp.jitterBufferEmittedCount||0,delay:rtp.jitterBufferDelay||0}}paintStatus();if(page==='diagnostic')renderDiagnostic()}catch{}}
function renderDiagnostic(){
 const s=state,d=s?.device;const metrics=[['MR18 / interface',s?.connected?'Connectée':'NON CONNECTÉE'],['Driver',d?.driver||'—'],['ASIO',d?.asio?'OUI':'NON / absent'],['Fréquence',s?.sampleRate?s.sampleRate+' Hz':'—'],['Buffer réel',s?.buffer?s.buffer+' éch.':'—'],['Entrées',s?.inputs??'—'],['Canaux avec signal',s?s.channels.filter(c=>c.signal).length:'—'],['CPU bridge',fmt(s?.cpu,' %')],['RAM bridge',fmt(s?.ramMB,' Mo')],['Réseau',networkType()],['RTT WebRTC',fmt(telemetry.rtt)],[activeEngine==='pcm'?'Variation d’arrivée PCM':'Jitter',fmt(telemetry.jitter)],[activeEngine==='pcm'?'Indisponibles à la lecture / intervalle':'Pertes / intervalle',fmt(telemetry.loss,' %')],['Buffer réception',fmt(telemetry.buffer)],['Latence driver',fmt(s?.captureLatencyMs)],['Moteur écoute',activeEngine==='pcm'?'PCM · direct':activeEngine==='opus'?'Stable · Opus':'Arrêté'],['Durée du bloc audio',activeEngine==='pcm'?'5 ms':s?s.opusFrameMs+' ms (Opus)':'—'],['Buffer PCM ciblé',activeEngine==='pcm'?$('#pcmTarget').value+' ms':'—'],['Attente callback → émission',fmt(lowMetrics?.captureQueueMs)],['Sortie Web Audio · estimation OS',fmt(Number.isFinite(outputContext?.baseLatency)?outputContext.baseLatency*1000:null)],['Périphérique sortie · estimation OS',fmt(Number.isFinite(outputContext?.outputLatency)?outputContext.outputLatency*1000:null)],['PCM absents / tardifs / sautés',lowMetrics?`${lowMetrics.missing} / ${lowMetrics.late} / ${lowMetrics.skipped}`:'—'],['Sous-alimentation PCM',lowMetrics?`${lowMetrics.underrunFrames} échantillons`:'—'],['XRUN / pertes capture',s?`${s.xruns} / ${s.captureDrops}`:'—'],['Qualité réseau',quality()],['Paquets audio reçus',receivedPackets],['Sortie navigateur',outputContext?.state|| (playbackReady?'Lecture active':'En attente')],['Latence audio bout-en-bout','NON MESURÉE']];$('#diagnostics').innerHTML=metrics.map(([k,v])=>`<div class="metric"><span>${esc(k)}</span><b>${esc(v)}</b></div>`).join('');renderMapping();
}
function renderMapping(){
 $('#inputMapping').innerHTML='<table><thead><tr><th>Sélecteur ASIO demandé</th><th>FOSA</th><th>RMS / peak dBFS</th></tr></thead><tbody>'+(state?.channels||[]).map((c,i)=>`<tr><td>Input ${i+1}</td><td>CH${i+1}</td><td>${fmt(c.rmsDb,'')} / ${fmt(c.peakDb,'')}</td></tr>`).join('')+'</tbody></table>';
}
async function testInput(){
 if(!state?.connected){notice('Ouvre d’abord les 18 entrées ASIO.');return}
 const expected=Number($('#physicalInput').value);$('#testInput').disabled=true;
 $('#mappingResult').textContent=`Parle ou envoie un signal uniquement dans l’entrée physique ${expected}, pendant 4 secondes…`;
 const peaks=Array(18).fill(-120);
 try{for(let n=0;n<32;n++){const m=await api('meters',{timeout:1500});if(!m.connected)throw Error('Capture interrompue');m.channels.forEach((c,i)=>{peaks[i]=Math.max(peaks[i],c.rmsDb??-120)});await new Promise(r=>setTimeout(r,125))}
  const order=peaks.map((db,i)=>({db,i})).sort((a,b)=>b.db-a.db),loud=order[0];
  if(loud.db<-60)$('#mappingResult').textContent='Aucun signal assez fort détecté. Vérifie le gain et le routage USB MR18, puis recommence.';
  else if(loud.db-order[1].db<10)$('#mappingResult').textContent='Plusieurs canaux reçoivent du signal. Isole cette entrée avant de conclure sur son mapping.';
  else $('#mappingResult').textContent=`Entrée physique ${expected} → signal dominant sur CH${loud.i+1} (${loud.db.toFixed(1)} dBFS). `+(loud.i+1===expected?'Correspondance observée pendant ce test.':'Correspondance différente : vérifie le routage USB de la MR18.');
 }catch(e){$('#mappingResult').textContent='Test interrompu : '+e.message}finally{$('#testInput').disabled=false}
}
async function sharedQr(box,role,address){
 const path='qr?role='+encodeURIComponent(role)+'&mode='+$('#shareMode').value+(address?'&address='+encodeURIComponent(address):'');
 const r=await fetch(server+'/api/'+path,{headers:{Authorization:'Bearer '+token()}});if(!r.ok)throw Error('QR indisponible');
 const url=URL.createObjectURL(await r.blob());const old=box.dataset.objectUrl;if(old)URL.revokeObjectURL(old);box.dataset.objectUrl=url;
 box.innerHTML='<img alt="QR pour rejoindre la session FOSA">';box.querySelector('img').src=url;
}
async function updateSetup(){
 if(!admin)return;
 serverNetwork=await api('network');$('#serverSetup').hidden=false;
 const adapters=serverNetwork.adapters||[],select=$('#lanAdapter');
 const previous=select.value;
 if(JSON.stringify(adapters)!==select.dataset.adapters){select.innerHTML=adapters.map(a=>`<option value="${esc(a.address)}">${esc(a.type||'LAN')} · ${esc(a.name)} · ${esc(a.address)}</option>`).join('');select.dataset.adapters=JSON.stringify(adapters);if(adapters.some(a=>a.address===previous))select.value=previous}
 $('#shareMode').querySelector('[value="secure"]').disabled=!serverNetwork.secure?.enabled;
 if(!serverNetwork.secure?.enabled)$('#shareMode').value='lan';
 const secured=$('#shareMode').value==='secure';$('#adapterLabel').hidden=secured||adapters.length<2;
 const link=new URL(secured?serverNetwork.joinUrl:serverNetwork.lanJoinUrl);if(!secured&&select.value)link.hostname=select.value;
 $('#setupAddress').value=link.href;$('#setupCode').textContent=serverNetwork.joinCode;
 $('#shareModeInfo').textContent=secured?'Écoute + talkback · Internet pour établir la session · micro autorisé dans MATRIX.':serverNetwork.secure?.enabled?'Écoute LAN sans Internet · micro indisponible sur téléphone avec ce QR.':'Écoute LAN · lance start-mobile-windows.cmd pour obtenir le QR sécurisé avec talkback.';
 const qrKey=link.href;
 if($('#setupQr').dataset.key!==qrKey){await sharedQr($('#setupQr'),'Personnalisé',select.value);$('#setupQr').dataset.key=qrKey}
 const users=state?.users||[],connected=users.filter(p=>p.connected);
 const checks=[
  [!!state?.device,'Interface audio',state?.device?.name||state?.error||'En attente de la MR18'],
  [!!state?.device?.asio,'ASIO',state?.device?.driver||'Aucun pilote ouvert'],
  [state?.connected&&state?.inputs===18,'18 entrées à 48 kHz',state?.connected?`${state.inputs} entrées · ${state.sampleRate} Hz`:'Capture en attente'],
  [adapters.length>0,'Réseau LAN',adapters.length?`${select.selectedOptions[0]?.textContent||adapters[0].address}`:'Mode local uniquement : utilise start-mobile-windows.cmd'],
  [serverNetwork.selfCheck==='passed','Serveur HTTP sur le PC',serverNetwork.selfCheckNote||'Non vérifié par ce mode de lancement'],
  [serverNetwork.firewall?.state==='configured','Pare-feu Windows',serverNetwork.firewall?.message||'Non vérifié'],
  [!!$('#setupQr').dataset.key,'QR de connexion','Code session inclus · clé régisseur exclue'],
  ...secured?[[serverNetwork.secure?.state==='ready','Connexion HTTPS',serverNetwork.secure?.message||'En préparation']]:[],
  [!!serverNetwork.lastClient,'Accès depuis un autre appareil',serverNetwork.lastClient?serverNetwork.lastClient.stage+' · '+serverNetwork.lastClient.address:'En attente du premier scan'],
  [connected.length>0,'Liaison audio',connected.length?connected.length+' profil(s) relié(s) par WebRTC':'En attente de ÉCOUTER sur le téléphone'],
  [false,'Latence physique','Non mesurée · test dans DIAGNOSTIC']
 ];
 $('#setupChecks').innerHTML=checks.map(([ok,name,detail])=>`<li><b class="${ok?'check-ok':'check-wait'}">${ok?'✓':'…'} ${esc(name)}</b> · ${esc(detail)}</li>`).join('');
 $('#setupReady').textContent=!state?.connected?'ENTRÉES À VÉRIFIER':connected.length?'MUSICIEN RELIÉ':'PRÊT À CONNECTER';
 $('#networkHelp').textContent=serverNetwork.lastClient?'Le serveur a reçu une requête d’un autre appareil. Si le son manque, consulte DIAGNOSTIC : paquets reçus, lecture et source sont contrôlés séparément.':'Le contrôle HTTP depuis ce PC ne prouve pas l’accès depuis le téléphone. Si le QR ne s’ouvre pas : même Wi-Fi, réseau invité et isolation des appareils, VPN ou autorisation réseau local du navigateur sont à vérifier.';
 $('#welcomeCard').hidden=true;
}
function renderTalkTargets(){const selected=$('#talkTarget').value,targets=(state?.talkTargets||[]).filter(p=>p.id!==profile?.id);const roles=[...new Set(targets.map(p=>p.role))];const options='<option value="all">Tous les auditeurs autorisés</option>'+roles.map(role=>`<option value="role:${esc(role)}">Profil · ${esc(role)}</option>`).join('')+targets.map(p=>`<option value="${esc(p.id)}">${esc(p.name)} · ${esc(p.role)}</option>`).join('');for(const el of $$('#talkTarget,#liveTalkTarget')){if(el.innerHTML!==options)el.innerHTML=options;el.value=[...el.options].some(o=>o.value===selected)?selected:'all'}if($('#talkTarget').value!==selected)stopTalk()}
function renderDevices(){const users=state?.users;if(!users){$('#devices').textContent='Console régisseur requise.';return}$('#devices').innerHTML=users.length?`<table><thead><tr>${['Utilisateur','Appareil','Mix','Moteur','Talkback','Connexion','RTT','Latence audio'].map(x=>`<th>${x}</th>`).join('')}</tr></thead><tbody>${users.map(p=>`<tr><td>${esc(p.name)}</td><td title="${esc(p.device)}">${/Android/i.test(p.device)?'Android':/iPhone|iPad/i.test(p.device)?'iOS':p.connected?'PC / navigateur':'—'}</td><td>${esc(p.role)}</td><td>${p.monitoringEngine==='pcm'?'PCM 5 ms':p.connected?'Opus':'—'}${p.pcm?'<br>'+p.pcm.congestionDrops+' abandons serveur':''}</td><td>${p.talkAllowed?'Micro autorisé':'Écoute'}${p.talkListen?'':' OFF'}</td><td>${p.connected?esc(p.network):'Hors ligne'}</td><td>${fmt(p.metrics?.rtt)}</td><td>Non mesurée</td></tr>`).join('')}</tbody></table>`:'Aucun profil musicien. Rejoins le bridge sur un autre appareil.'}
function renderMatrix(){const users=state?.users;if(!admin||!users){$('#matrix').textContent='';return}$('#matrixHint').textContent='Assignations et permissions appliquées par le serveur. Mute et niveaux sont propres à chaque musicien.';if(!users.length){$('#matrix').textContent='Aucun profil musicien connecté.';return}
 $('#matrix').innerHTML=`<table><thead><tr><th>Entrée</th>${users.map(p=>`<th>${esc(p.name)}<br><label><input type="checkbox" data-permission="locked" data-user="${p.id}" ${p.locked?'checked':''}>Verrouiller</label><label><input type="checkbox" data-permission="talkAllowed" data-user="${p.id}" ${p.talkAllowed?'checked':''}>Micro</label><label><input type="checkbox" data-permission="talkListen" data-user="${p.id}" ${p.talkListen?'checked':''}>Écoute TB</label><button data-copy="${p.id}">COPIER MON MIX</button></th>`).join('')}</tr></thead><tbody>${(state.channels||[]).map((c,i)=>`<tr><th>${String(i+1).padStart(2,'0')} · ${esc(c.name)}</th>${users.map(p=>`<td><div class="matrix-cell"><input type="checkbox" data-assign="${i}" data-user="${p.id}" ${p.allowed[i]?'checked':''} aria-label="Assigner ${esc(c.name)} à ${esc(p.name)}"><input type="range" min="0" max="100" value="${Math.round(p.mix.channels[i].gain*100)}" data-matrix-gain="${i}" data-user="${p.id}" aria-label="Niveau ${esc(c.name)} pour ${esc(p.name)}"><button data-matrix-mute="${i}" data-user="${p.id}" class="${p.mix.channels[i].mute?'active':''}">M</button></div></td>`).join('')}</tr>`).join('')}</tbody></table>`;
}
function applyPreset(){if(profile?.locked){notice('Mix verrouillé.');return}const role=$('#preset').value;const preferences={Batteur:['kick','snare','batterie'],Bassiste:['bass','basse','kick'],Guitariste:['guit','chant','vocal'],Clavier:['piano','key','clavier','chant'],Chant:['chant','vocal','piano'],Chef:[],Régisseur:[],Personnalisé:[]}[role]||[];mix.channels.forEach((c,i)=>{const meta=state?.channels?.[i];const text=(meta?.name+' '+meta?.role).toLowerCase();c.gain=preferences.some(x=>text.includes(x))?.8:.4;c.mute=false;c.solo=false;c.pan=0});edit();notice('Preset appliqué selon les noms/types des canaux. Ajuste ton écoute.')}
async function outputs(){try{const devices=await navigator.mediaDevices?.enumerateDevices();$('#output').innerHTML='<option value="">Sortie système</option>'+(devices||[]).filter(d=>d.kind==='audiooutput').map((d,i)=>`<option value="${esc(d.deviceId)}">${esc(d.label||'Sortie '+(i+1))}</option>`).join('');$('#outputInfo').textContent=audio.setSinkId?'Sélectionne une sortie exposée par ton navigateur.':'Choisis la sortie dans les réglages du téléphone ; ce navigateur ne permet pas de la changer.';$('#output').disabled=outputContext?!outputContext.setSinkId:!audio.setSinkId}catch(e){notice(e.message)}}
function setupMediaSession(){if(!('mediaSession'in navigator))return;try{navigator.mediaSession.metadata=new MediaMetadata({title:'FOSA · Mon mix',artist:profile?.name||'Audio Network'});navigator.mediaSession.setActionHandler('pause',()=>{if(!profile?.locked){mix.muteAll=true;edit()}});navigator.mediaSession.setActionHandler('play',()=>{unlockOutput();if(!profile?.locked){mix.muteAll=false;edit()}if(!outputContext)audio.play().catch(()=>{})})}catch{}}
function bind(){
 document.addEventListener('click',async e=>{const t=e.target.closest('button');if(!t)return;
 if(t.dataset.view)show(t.dataset.view);
 if(t.dataset.step){const i=Number(t.dataset.i);mix.channels[i].gain=Math.max(0,Math.min(1,mix.channels[i].gain+Number(t.dataset.step)/100));edit()}
 if(t.dataset.mute!==undefined){const i=Number(t.dataset.mute);mix.channels[i].mute=!mix.channels[i].mute;edit()}
 if(t.dataset.rename!==undefined){const i=Number(t.dataset.rename);try{await api('channel',{body:{number:i+1,name:$(`[data-name="${i}"]`).value,role:$(`[data-role="${i}"]`).value}});await refresh();notice('Canal renommé.')}catch(err){notice(err.message)}}
 if(t.dataset.do==='listen')startAudio();if(t.dataset.do==='mute'&&!profile?.locked){mix.muteAll=!mix.muteAll;edit()}
 if(t.dataset.matrixMute!==undefined){const p=state.users.find(p=>p.id===t.dataset.user);p.mix.channels[Number(t.dataset.matrixMute)].mute=!p.mix.channels[Number(t.dataset.matrixMute)].mute;try{await api('mix',{body:{id:p.id,mix:p.mix}});await refresh()}catch(err){notice(err.message)}}
 if(t.dataset.copy){try{await api('mix',{body:{id:t.dataset.copy,mix:normalize(mix)}});await refresh();notice('Mix copié.')}catch(err){notice(err.message)}}
 });
 $('#mixer').addEventListener('input',e=>{const t=e.target;if(t.dataset.gain!==undefined)mix.channels[Number(t.dataset.gain)].gain=Number(t.value)/100;if(t.dataset.pan!==undefined)mix.channels[Number(t.dataset.pan)].pan=Number(t.value)/100;edit()});
 $$('#master,#liveMaster').forEach(e=>e.oninput=()=>{if(profile?.locked){syncMix();return}mix.master=Number(e.value)/100;edit()});
 $$('[data-solo]').forEach(b=>{b.onpointerdown=e=>{e.preventDefault();b.setPointerCapture(e.pointerId);mix.channels[Number(b.dataset.solo)].solo=true;edit()};const release=()=>{if(mix.channels[Number(b.dataset.solo)].solo){mix.channels[Number(b.dataset.solo)].solo=false;edit()}};b.onpointerup=release;b.onpointercancel=release;b.onlostpointercapture=release;b.onkeydown=e=>{if(e.key===' '){e.preventDefault();mix.channels[Number(b.dataset.solo)].solo=true;edit()}};b.onkeyup=release;b.onblur=release});
 $('#matrix').onchange=async e=>{const t=e.target,p=state?.users?.find(p=>p.id===t.dataset.user);if(!p)return;try{if(t.dataset.permission)await api('matrix',{body:{id:p.id,[t.dataset.permission]:t.checked}});if(t.dataset.assign!==undefined){p.allowed[Number(t.dataset.assign)]=t.checked;await api('matrix',{body:{id:p.id,allowed:p.allowed}})}if(t.dataset.matrixGain!==undefined){p.mix.channels[Number(t.dataset.matrixGain)].gain=Number(t.value)/100;await api('mix',{body:{id:p.id,mix:p.mix}})}await refresh()}catch(err){notice(err.message)}};
 $('#stopPcTalk').onclick=stopAudio;
 $$('.microphone-input').forEach(el=>el.onchange=async()=>{micDevice=el.value;put('fosa_talkback_device',micDevice);$$('.microphone-input').forEach(other=>other.value=micDevice);await disableMic();notice('Source micro choisie. Touche ACTIVER MON MICRO pour l’utiliser.')});
 $('#detect').onclick=detect;$('#joinNetwork').onclick=()=>join(false);$('#joinListen').onclick=()=>join(true);$('#adminLogin').onclick=adminLogin;$('#scanInterfaces').onclick=scanInterfaces;
 $('#lanAdapter').onchange=()=>updateSetup().catch(e=>notice(e.message));$('#shareMode').onchange=()=>updateSetup().catch(e=>notice(e.message));$('#testInput').onclick=testInput;
 $('#startCapture').onclick=async()=>{try{const s=await api('configure',{body:{device:Number($('#interfaceSelect').value),buffer:Number($('#bufferSelect').value)},timeout:12000});state=s;notice('Interface ouverte. Attente des premiers échantillons…');await refresh()}catch(e){notice(e.message)}};
 $('#startAudio').onclick=startAudio;$('#stopAudio').onclick=stopAudio;$('#muteAll').onclick=()=>{if(profile?.locked)return;mix.muteAll=!mix.muteAll;edit()};
 $('#saveMix').onclick=()=>{put(contextKey()+':saved',normalize(mix));notice('Mix sauvegardé sur cet appareil.')};$('#restoreMix').onclick=()=>{const saved=get(contextKey()+':saved',null);if(!saved){notice('Aucun mix sauvegardé pour ce profil.');return}if(profile?.locked)return;mix=normalize(saved);edit();notice('Mix restauré.')};$('#resetMix').onclick=()=>{if(profile?.locked)return;mix=blank();edit()};$('#applyPreset').onclick=applyPreset;
 $('#ducking').onchange=()=>{if(profile?.locked)return;mix.ducking=Number($('#ducking').value);edit()};$('#enableMic').onclick=enableMic;$('#liveEnableMic').onclick=enableMic;
 $$('#talkTarget,#liveTalkTarget').forEach(el=>el.onchange=()=>{stopTalk();$$('#talkTarget,#liveTalkTarget').forEach(other=>other.value=el.value)});
 $$('.talk').forEach(b=>{b.onkeydown=e=>{if([' ','Enter'].includes(e.key)&&!e.repeat)startTalk(e)};b.onkeyup=e=>{if([' ','Enter'].includes(e.key)){e.preventDefault();stopTalk()}}});
 $$('.talk').forEach(b=>{b.onpointerdown=startTalk;b.onpointerup=stopTalk;b.onpointercancel=stopTalk;b.onlostpointercapture=stopTalk});addEventListener('blur',stopTalk);document.addEventListener('visibilitychange',()=>{if(document.hidden){stopTalk();if(mix.channels.some(c=>c.solo)){mix.channels.forEach(c=>c.solo=false);edit()}}else{if(wake)requestWake();refresh()}});
 $$('.monitoring-engine').forEach(el=>el.onchange=()=>{const restart=desired;engine=el.value;put('fosa_monitoring_engine',engine);$$('.monitoring-engine').forEach(other=>other.value=engine);if(restart){stopAudio();startAudio();notice('Changement de moteur : l’écoute redémarre, le micro doit être réactivé.')}});
 $('#pcmTarget').value=String(get('fosa_pcm_target',10));$('#pcmTarget').onchange=()=>{const ms=Number($('#pcmTarget').value);put('fosa_pcm_target',ms);lowOutput?.target(ms)};
 $('#jitterTarget').onchange=applyJitter;$('#refreshOutputs').onclick=outputs;$('#output').onchange=async()=>{try{if(outputContext?.setSinkId)await outputContext.setSinkId($('#output').value);else if(!outputContext)await audio.setSinkId($('#output').value);else throw Error('Ce navigateur utilise la sortie système pour Web Audio');$('#outputInfo').textContent='Sortie sélectionnée : '+$('#output').selectedOptions[0].textContent}catch(e){notice('Sortie non modifiée : '+e.message)}};
 $('#wake').onclick=async()=>{if(wake){await wake.release();wake=null;$('#wake').classList.remove('active')}else requestWake()};
 $('#testLatency').onclick=async()=>{try{const start=performance.now();await api('health',{auth:false});$('#diagnosticNote').textContent=`Aller-retour commande (${direct?.ready?'liaison directe':relayClient?'signalisation Internet':'HTTP'}) : ${(performance.now()-start).toFixed(1)} ms. RTT WebRTC : ${fmt(telemetry.rtt)}. La latence audio bout-en-bout reste non mesurée : suis le test physique ci-dessous.`}catch(e){notice(e.message)}};
 $('#optimize').onclick=()=>{if(activeEngine==='pcm'){const target=telemetry.loss>1||telemetry.jitter>5?20:10;$('#pcmTarget').value=String(target);lowOutput?.target(target);put('fosa_pcm_target',target);$('#diagnosticNote').textContent=`Buffer PCM ciblé : ${target} ms. Le buffer ASIO se modifie après arrêt des écoutes. La latence totale reste à mesurer.`;return}const stable=state?.xruns>0||telemetry.loss>1||telemetry.jitter>15;$('#jitterTarget').value=stable?'40':'20';applyJitter();$('#diagnosticNote').textContent=stable?'Cible réception portée à 40 ms. Après arrêt des écoutes, essaie un buffer ASIO 512 si des craquements persistent.':'Cible réception 20 ms. Garde le buffer ASIO 256 pour les premiers essais ; diminue-le seulement après vérification sans craquements.'};
 $('#makeQr').onclick=async()=>{if(!admin){notice('Console régisseur requise pour générer le QR local.');return}try{serverNetwork=await api('network');const secured=$('#shareMode').value==='secure',url=new URL(secured?serverNetwork.joinUrl:serverNetwork.lanJoinUrl),params=new URLSearchParams(url.hash.slice(1));params.set('role',$('#qrRole').value);url.hash=params.toString();const address=$('#lanAdapter').value;if(!secured&&address)url.hostname=address;$('#joinLink').value=url.href;await sharedQr($('#qrBox'),$('#qrRole').value,address)}catch(e){notice(e.message)}};
 $('#copyLink').onclick=async()=>{try{if(!$('#joinLink').value)throw Error('Génère d’abord le lien.');await navigator.clipboard.writeText($('#joinLink').value);notice('Lien copié.')}catch(e){$('#joinLink').select();notice('Sélectionne et copie le lien. '+e.message)}};
 $('#forgetSession').onclick=()=>{stopAudio();delete credentials[server];put('fosa_network_credentials',credentials);admin='';regisseurCredentials=null;profile=null;state=null;show('audio');paintStatus();notice('Profil déconnecté sur cet appareil.')};
 $('#backFosa').onclick=()=>window.parent.postMessage({type:'fosa-network-close'},location.origin);
 addEventListener('message',e=>{if(e.source!==window.parent||e.origin!==location.origin)return;if(e.data?.type==='fosa-network-view')show(e.data.view);if(e.data?.type==='fosa-talkback-activity'){externalTalk=e.data.active===true;const gain=externalTalk?(mix.ducking===-99?0:10**(mix.ducking/20)):1;audio.volume=gain;if(outputGain)outputGain.gain.value=gain}});
 addEventListener('keydown',e=>{if(e.key==='Escape'&&embedded)window.parent.postMessage({type:'fosa-network-close'},location.origin)});addEventListener('beforeunload',e=>{if(desired){e.preventDefault();e.returnValue=''}});
}
async function microphoneInputs(){
 try{const devices=await navigator.mediaDevices?.enumerateDevices();const options='<option value="">Micro système</option>'+(devices||[]).filter(d=>d.kind==='audioinput'&&d.deviceId).map((d,i)=>`<option value="${esc(d.deviceId)}">${esc(d.label||'Micro '+(i+1))}</option>`).join('');for(const el of $$('.microphone-input')){if(el.innerHTML!==options)el.innerHTML=options;el.value=micDevice;if(micDevice&&el.value!==micDevice){el.add(new Option('Micro choisi · reconnecter ou changer',micDevice));el.value=micDevice}}}catch{}
}
async function requestWake(){try{wake=await navigator.wakeLock.request('screen');$('#wake').classList.add('active');wake.addEventListener('release',()=>$('#wake').classList.remove('active'))}catch{notice('Écran actif non disponible sur ce lien. Garde FOSA visible et règle la veille de l’appareil si nécessaire.')}}
async function boot(){
 buildMixer();bind();syncMix();if(engine==='pcm'&&(!isSecureContext||!window.AudioWorkletNode)){engine='opus';notice('Ce lien propose l’écoute Stable. Scanne le QR sécurisé pour utiliser Low-Latency.')}$$('.monitoring-engine').forEach(el=>{el.value=engine;el.querySelector('[value=pcm]').disabled=!isSecureContext||!window.AudioWorkletNode});$('#backFosa').hidden=!embedded;$('#profileName').value=get('fosa_name','')||getText('fosa_name');
 if(inviteValue('relay')){$$('#joinListen,#joinNetwork').forEach(b=>b.disabled=true);notice('Préparation de la connexion sécurisée…')}
 document.body.classList.toggle('musician',musician);
 if(inviteValue('role')&&[...$('#profileRole').options].some(o=>o.value===inviteValue('role')))$('#profileRole').value=inviteValue('role');
 if(inviteValue('code'))$('#joinCode').value=inviteValue('code');
 // The secure QR binds the bridge identity; no HTTPS page fetches a private HTTP URL.
 if(inviteValue('relay')){try{relayClient=await FosaRelay.create({relay:inviteValue('relay'),key:inviteValue('key'),epoch:inviteValue('epoch')});setServer('')}catch(e){notice('Connexion sécurisée : '+e.message);return}}
 else if(musician||query.has('console')||location.port==='8765'||['127.0.0.1','localhost'].includes(location.hostname)){server=location.origin;put('fosa_network_server',server)}
 $('#serverUrl').value=server;show(query.get('view')||'audio');paintStatus();renderDiagnostic();
 if(musician){$('#welcomeCard h1').textContent='Rejoindre FOSA';$('#serverAddressForm').hidden=true}
 try{await api('health',{auth:false,timeout:3000});if(query.has('console')&&['127.0.0.1','localhost'].includes(location.hostname)){
  const r=await fetch(server+'/api/local-console',{method:'POST',headers:{'X-FOSA-Console':'1'}});if(!r.ok)throw Error('Console locale indisponible');admin=(await r.json()).token;state=await api('state');$('#bufferSelect').value=String(state.buffer||state.requestedBuffer||256);paintStatus();await prepareRegisseur();await scanInterfaces();await updateSetup();if(query.has('regisseur')){show('live');notice('Régisseur PC prêt. Active ton micro pour parler ; MATRIX permet d’autoriser les musiciens.')}else notice('Console régisseur PC prête.');
 }}catch(e){notice('Bridge inaccessible : '+e.message)}
 if(token()&&!admin){try{const s=await api('state');state=s;profile=s.profile;if(profile){mix=normalize(profile.mix);changed=false;syncMix();show(query.get('view')||'live');if(get('fosa_network_resume:'+server,false)){desired=true;startAudio()}}}catch{}}
 $$('#joinListen,#joinNetwork').forEach(b=>b.disabled=false);
 setInterval(async()=>{await refresh();if(desired&&!connecting&&(!pc||['failed','disconnected','closed'].includes(pc.connectionState))&&Date.now()>=reconnectAt)startAudio()},1500);
 microphoneInputs();navigator.mediaDevices?.addEventListener?.('devicechange',microphoneInputs);
 setInterval(pollMeters,125);
 setInterval(()=>{stats();sendControl()},700);
 if(navigator.getBattery)navigator.getBattery().then(b=>{const paint=()=>$('#battery').textContent=Math.round(b.level*100)+' %'+(b.charging?' · charge':'');paint();b.addEventListener('levelchange',paint);b.addEventListener('chargingchange',paint)}).catch(()=>{});
}
boot();
})();
