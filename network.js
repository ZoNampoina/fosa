(() => {
'use strict';
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const get = (k,d) => {try{return JSON.parse(localStorage.getItem(k))??d}catch{return d}};
const getText=k=>{try{return localStorage.getItem(k)||''}catch{return ''}};
const put = (k,v) => {try{localStorage.setItem(k,JSON.stringify(v))}catch{notice('Stockage local indisponible. Garde la session ouverte.')}};
const blank = (count=18) => ({master:.5,muteAll:false,ducking:-6,monitorGainDb:0,mono:false,mainChannel:0,channels:Array.from({length:count},()=>({gain:.5,pan:0,mute:false,solo:false}))});
const normalize = value => {
 const m=blank(Array.isArray(value?.channels)?Math.max(1,Math.min(64,value.channels.length)):18),num=(x,d,min,max)=>Number.isFinite(Number(x))?Math.max(min,Math.min(max,Number(x))):d;
 if(!value||!Array.isArray(value.channels)||value.channels.length<1||value.channels.length>64)return m;
 m.monitorGainDb=num(value.monitorGainDb,0,0,12);m.mono=value.mono===true;m.mainChannel=num(value.mainChannel,0,0,m.channels.length-1);
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
let phoneMicSaving=false,phonePermissionRevision=0;
let micDevice=get('fosa_talkback_device','');
let engine=inviteValue('engine')==='pcm'?'pcm':get('fosa_monitoring_engine','opus');
if(!['opus','pcm'].includes(engine))engine='opus';
let activeEngine=null,lowOutput=null,lowMetrics=null,previousLowStats=null;
const native=window.FosaAndroid;let nativeState={},localPanic=false,localLock=false,levelRecommendation=null;
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
 if(direct?.ready&&['health','state','meters','mix','control','panic','auto-level','more-me'].includes(path)&&(!admin||['health','meters','control','auto-level','more-me'].includes(path)))return direct.request(path,body,timeout);
 if(relayClient){if(['control','meters'].includes(path))throw Error('Liaison locale directe indisponible');return relayClient.request(path,body,auth?token():'',Math.max(timeout,12000))}
 const headers={};if(auth)headers.Authorization='Bearer '+(['offer','control','disconnect','auto-level','more-me'].includes(path)?personalToken():token());if(body!==undefined)headers['Content-Type']='application/json';
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),timeout);
 try{const r=await fetch(server+'/api/'+path,{method:body===undefined?'GET':'POST',headers,body:body===undefined?undefined:JSON.stringify(body),cache:'no-store',signal:controller.signal});
 const j=await r.json().catch(()=>({error:'Réponse du serveur illisible'}));if(!r.ok)throw Error(j.error||'Erreur serveur '+r.status);return j;}finally{clearTimeout(timer)}
}
function show(view){
 if(!$$('[data-panel]').some(x=>x.dataset.panel===view))view='audio';page=view;
 $$('[data-panel]').forEach(x=>x.hidden=x.dataset.panel!==view);$$('.net-nav [data-view]').forEach(x=>x.classList.toggle('selected',x.dataset.view===view));
 if(view==='live')renderPhoneMicrophones();if(view==='talkback')renderTalkTargets();if(view==='mix')syncMix();if(view==='matrix')renderMatrix();if(view==='devices')renderDevices();if(view==='diagnostic')renderDiagnostic();
}
function networkType(){if(native)return nativeState.network||'LAN / UDP';const t=navigator.connection?.type;return ({ethernet:'Ethernet',wifi:'Wi-Fi',cellular:'Données mobiles',bluetooth:'Bluetooth'})[t]||'Réseau inconnu'}
const fmt=(v,suffix=' ms')=>Number.isFinite(v)?v.toFixed(1)+suffix:'—';
function quality(){if(!(native?nativeState.connected:pc?.connectionState==='connected')||telemetry.jitter===null)return'EN ATTENTE';if(telemetry.loss>5||telemetry.jitter>30||telemetry.rtt>150)return'CRITIQUE';if(telemetry.loss>1||telemetry.jitter>15||telemetry.rtt>70)return'INSTABLE';return telemetry.jitter<5&&telemetry.loss===0&&telemetry.rtt!==null&&telemetry.rtt<20?'EXCELLENT':'BON'}
function paintStatus(){
 if(embedded)window.parent.postMessage({type:'fosa-network-state',active:desired},location.origin);const reachable=!!state;$('#bridgeStatus').textContent=reachable?(state.connected?'MR18 / INTERFACE CONNECTÉE':'BRIDGE CONNECTÉ · ENTRÉES ABSENTES'):'NON CONNECTÉ';$('#bridgeStatus').classList.toggle('online',!!state?.connected);
 const incoming=receivedPackets>0&&Date.now()-lastPacketsAt<3500;
 $('#streamStatus').textContent=(native?nativeState.connected:pc?.connectionState==='connected')?(state?.connected?(playbackReady&&incoming?'AUDIO EN LECTURE':!playbackReady?'TOUCHE ÉCOUTER POUR LA LECTURE':'LIAISON PRÊTE · ATTENTE AUDIO'):'LIAISON PRÊTE · SOURCE ABSENTE'):connecting?'CONNEXION AUDIO…':desired?'RECONNEXION…':'AUDIO ARRÊTÉ';
 $('#netType').textContent=networkType();$('#liveQuality').textContent=quality();$('#liveQuality').style.color=['CRITIQUE','INSTABLE'].includes(quality())?'var(--amber)':'';
 $('#liveLatency').textContent=(activeEngine==='pcm'?'Low-Latency · PCM 5 ms · ':activeEngine==='opus'?'Stable · Opus · ':'')+'Latence audio : non mesurée';
 $('#captureSummary').textContent=state?.connected?`${state.device?.name} · ${state.device?.driver} · ${state.sampleRate} Hz · ${state.buffer} échantillons · ${state.inputs} entrées`:(state?.error||'NON CONNECTÉ · aucune entrée détectée');
 $('#pcMicrophones').hidden=!admin;$('#pcRegisseur').hidden=!regisseurCredentials;$('#welcomeCard').hidden=!!regisseurCredentials;
 $('#scanInterfaces').disabled=!admin;$('#startCapture').disabled=!admin;$('#interfaceSelect').disabled=!admin;
 $$('#master,#liveMaster,#muteAll,#liveMute,#ducking,#applyPreset,#restoreMix,#resetMix').forEach(el=>el.disabled=localLock||profile?.locked===true);$('#mixScope').textContent=profile?`${profile.name} · ${profile.role}${profile.locked?' · VERROUILLÉ':''}`:'Réglages locaux';
 const micAvailable=!!native||!!navigator.mediaDevices?.getUserMedia;
 const blockedNote=microphoneBlocked();
 const micNote=blockedNote|| (micPending?((native?nativeState.connected:direct?.ready)?'Autorise le microphone dans la demande du navigateur.':'Préparation de la liaison talkback…'):!(native?nativeState.connected:direct?.ready)?'Micro autorisé. Touche ACTIVER MON MICRO pour préparer la liaison talkback.':!micEnabled?'Touche ACTIVER MON MICRO, accepte la demande du navigateur, puis maintiens TALK.':talking?(talkConfirmed?'PAROLE TRANSMISE · '+$('#talkTarget').selectedOptions[0]?.textContent:'Ouverture du talkback…'):'Micro prêt · maintiens TALK pour parler.');
 $$('#liveTalkNote,#talkNote').forEach(el=>{el.textContent=micNote;el.classList.toggle('warning',!!blockedNote)});
 $$('#enableMic,#liveEnableMic').forEach(b=>{b.textContent=!micAvailable?'MICRO · LIEN SÉCURISÉ REQUIS':!profile?.talkAllowed?'MICRO BLOQUÉ · AUTORISATION PC':micPending?((native?nativeState.connected:direct?.ready)?'AUTORISATION EN COURS…':'PRÉPARATION DU MICRO…'):micEnabled?'COUPER MON MICRO':'ACTIVER MON MICRO';b.disabled=!micAvailable||!profile?.talkAllowed||micPending});
 $$('.talk').forEach(b=>{b.disabled=!micAvailable||!profile?.talkAllowed||!micEnabled||!(native?nativeState.connected:direct?.ready);b.classList.toggle('talking',talking&&talkConfirmed);b.textContent=talking?(talkConfirmed?'PAROLE TRANSMISE':'CONNEXION TALK…'):'MAINTENIR TALK POUR PARLER'});
 paintBodypack();
 $('#mixNotice').textContent=changed?'Modifications locales en attente du serveur.':profile?'Mix enregistré sur cet appareil et sur le serveur LAN.':'Réglages locaux : rejoins le serveur pour les appliquer au son.';
}
function buildMixer(){
 const count=mix.channels.length;
 $('#mixer').innerHTML=Array.from({length:count},(_,i)=>`<article class="channel" data-channel="${i}"><span class="num">${String(i+1).padStart(2,'0')}</span><strong data-channel-name>CH ${String(i+1).padStart(2,'0')}</strong><output data-volume>50 %</output><div class="fader"><button data-step="-5" data-i="${i}" aria-label="Diminuer canal ${i+1}">−</button><input data-gain="${i}" type="range" min="0" max="100" value="50" aria-label="Volume canal ${i+1}"><button data-step="5" data-i="${i}" aria-label="Augmenter canal ${i+1}">+</button></div><div class="ch-actions"><button data-mute="${i}" aria-label="Mute canal ${i+1}" aria-pressed="false">M</button><button data-solo="${i}" aria-label="Solo temporaire canal ${i+1}" aria-pressed="false">S</button><label class="pan">PAN<input data-pan="${i}" type="range" min="-100" max="100" value="0" aria-label="Panoramique canal ${i+1}"></label></div><div class="meter"><i></i></div><span class="reading">NON CONNECTÉ</span></article>`).join('');
 $('#channelNames').innerHTML=Array.from({length:count},(_,i)=>`<div class="channel-name"><span>CH ${String(i+1).padStart(2,'0')}</span><input data-name="${i}" value="CH ${String(i+1).padStart(2,'0')}" maxlength="60" aria-label="Nom canal ${i+1}" disabled><input data-role="${i}" placeholder="Type : basse, chant…" maxlength="60" aria-label="Type canal ${i+1}" disabled><button data-rename="${i}" disabled>OK</button><details><summary>Routing / Groupe</summary><label>Ordre visuel<input type="number" min="1" max="${count}" value="${i+1}" data-order="${i}"></label><label><input type="checkbox" data-disabled="${i}">Entrée désactivée</label></details></div>`).join('');
 $$('.meter').forEach(m=>m.insertAdjacentHTML('beforeend','<b aria-hidden="true"></b>'));
 $('#physicalInput').innerHTML=Array.from({length:count},(_,i)=>`<option value="${i+1}">Entrée ${i+1}</option>`).join('');
 bindSolo();
}

function syncMix(){
 if($$('.channel').length!==mix.channels.length)buildMixer();
 $('#monitorGain').value=mix.monitorGainDb||0;$('#monitorGain').max=profile?.limits?.gainMaxDb??12;$('#monitorGainValue').value='+'+(mix.monitorGainDb||0)+' dB';$('#monoMix').checked=!!mix.mono;
 const options=mix.channels.map((_,i)=>`<option value="${i}">${esc(state?.channels?.[i]?.name||'CH '+(i+1))}</option>`).join('');if($('#mainChannel').innerHTML!==options)$('#mainChannel').innerHTML=options;$('#mainChannel').value=mix.mainChannel||0;$('#master').max=$('#liveMaster').max=Math.round((profile?.limits?.masterMax??1)*100);
 $('#monitorGain').disabled=$('#monoMix').disabled=$('#mainChannel').disabled=$('#moreMe').disabled=localLock||profile?.locked===true;
 $('#master').value=$('#liveMaster').value=Math.round(mix.master*100);$('#masterValue').value=$('#liveMasterValue').value=(mix.master>0?(20*Math.log10(mix.master)).toFixed(1):'−∞')+' dB';$('#ducking').value=mix.ducking;
 $('#muteAll').classList.toggle('active',mix.muteAll);$('#liveMute').classList.toggle('active',mix.muteAll);
 $$('.channel').forEach((el,i)=>{const c=mix.channels[i];el.querySelector('[data-gain]').value=Math.round(c.gain*100);el.querySelector('[data-pan]').value=Math.round(c.pan*100);el.querySelector('[data-volume]').value=Math.round(c.gain*100)+' %';for(const key of ['mute','solo']){const b=el.querySelector('[data-'+key+']');b.classList.toggle('active',c[key]);b.setAttribute('aria-pressed',String(c[key]))}const locked=localLock||profile?.locked===true||profile?.allowed?.[i]===false;el.classList.toggle('unassigned',locked);el.querySelectorAll('input,button').forEach(b=>b.disabled=locked);for(const key of ['gain','pan','mute','solo'])if(profile?.permissions?.[key]===false)el.querySelector('[data-'+key+']').disabled=true});
}
function updateMeters(){
 $$('.channel').forEach((el,i)=>{const c=state?.channels?.[i],name=c?.name||`CH ${String(i+1).padStart(2,'0')}`;el.style.order=c?.order??i;el.querySelector('[data-channel-name]').textContent=name;el.classList.toggle('clipping',!!c?.clipping);el.querySelector('.meter i').style.width=(c?.active?Math.max(0,Math.min(100,(c.rmsDb+60)/60*100)):0)+'%';el.querySelector('.reading').textContent=!c?.active?'NON CONNECTÉ':c.clipping?'CLIPPING':`${c.signal?'Signal':'Silence'} · ${fmt(c.rmsDb,' dBFS')} · peak ${fmt(c.peakDb,' dBFS')}`;
 const n=$(`[data-name="${i}"]`),r=$(`[data-role="${i}"]`);if(document.activeElement!==n)n.value=name;if(document.activeElement!==r)r.value=c?.group||c?.role||'';const order=$(`[data-order="${i}"]`),disabled=$(`[data-disabled="${i}"]`);if(document.activeElement!==order)order.value=(c?.order??i)+1;if(document.activeElement!==disabled)disabled.checked=!!c?.disabled;order.disabled=disabled.disabled=!admin;n.disabled=r.disabled=$(`[data-rename="${i}"]`).disabled=!admin;
 });
 $$('.channel').forEach((el,i)=>{const c=state?.channels?.[i];el.querySelector('.meter b').style.left=(c?.active?Math.max(0,Math.min(99,(c.peakDb+60)/60*100)):0)+'%'});
}
let persistTimer;
function edit(){localRevision++;changed=true;syncMix();const durable=normalize(mix);put(contextKey(),durable);if(!profile)put('fosa_network_draft',durable);clearTimeout(persistTimer);persistTimer=setTimeout(flush,120);paintStatus()}
async function flush(){
 if(sending||!changed||!profile||!state)return;sending=true;const sent=JSON.stringify(mix);
 try{const ack=await api('mix',{body:admin?{id:profile.id,mix}:{mix}});confirmedMixVersion=ack.mixVersion||0;if(JSON.stringify(mix)===sent){changed=false;mix=ack.mix;syncMix()}}catch(e){notice(e.message)}finally{sending=false;paintStatus();if(changed&&JSON.stringify(mix)!==sent)flush()}
}
async function pollMeters(){
 if(!token()||!state||meterPolling||document.hidden||!['mix','live','diagnostic'].includes(page))return;
 meterPolling=true;
 try{
  const m=await api('meters',{timeout:1500});
  state.connected=!!m.connected;if(profile)profile.levels=m.levels;
  if(Array.isArray(m.channels)&&Array.isArray(state.channels)){
   state.channels=state.channels.map((c,i)=>Object.assign({},c,m.channels[i]||{}));
  }
  paintStatus();updateMeters();if(page==='diagnostic')renderMapping();
 }catch{}
 finally{meterPolling=false}
}
async function refresh(){
 if(!token()||polling)return;polling=true;
 try{const revision=localRevision,permissionRevision=phonePermissionRevision,dirtyAtStart=changed||sending;const next=await api('state');if(permissionRevision!==phonePermissionRevision)return;state=next;profile=admin&&regisseurCredentials?next.users?.find(p=>p.id===regisseurCredentials.id)||null:next.profile;
  if(profile){if(!profile.talkAllowed&&(micEnabled||micPending))await disableMic();if(!dirtyAtStart&&!changed&&!sending&&revision===localRevision&&(profile.mixVersion||0)>=confirmedMixVersion&&JSON.stringify(profile.mix)!==JSON.stringify(mix)){mix=profile.mix;syncMix()}if(changed&&!profile.locked)await flush()}
  paintStatus();updateMeters();if(page==='diagnostic')renderDiagnostic();if(page==='devices')renderDevices();
  if(profile?.locked){mix=profile.mix;changed=false;syncMix()}if(page==='matrix'&&document.activeElement?.closest('#matrix')==null)renderMatrix();if(regisseurCredentials)microphoneInputs();renderTalkTargets();renderPhoneMicrophones();
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
 try{setServer($('#serverUrl').value.trim()||server);if(regisseurCredentials){stopAudio();regisseurCredentials=null}admin='';const b=await api('join',{auth:false,body:{code:$('#joinCode').value.trim().toUpperCase(),session:inviteValue('session'),name:$('#profileName').value.trim()||'Musicien',role:$('#profileRole').value}});credentials[server]={token:b.token,id:b.profile.id};put('fosa_network_credentials',credentials);profile=b.profile;mix=normalize(b.profile.mix);changed=false;await refresh();syncMix();show(native?'mix':'live');if(invite.has('code')){const clean=new URLSearchParams(location.hash.slice(1));clean.delete('code');history.replaceState(null,'',location.pathname+location.search+(relayClient?'#'+clean.toString():''))}notice('Profil rejoint.');if(listen)await startAudio()}
 catch(e){notice('Connexion impossible : '+e.message)}finally{joining=false;$('#joinListen').disabled=false}
}
async function adminLogin(){try{setServer($('#serverUrl').value.trim()||server);admin=$('#adminKey').value.trim();const s=await api('state');if(!s.admin)throw Error('Clé régisseur incorrecte');stopAudio();regisseurCredentials=null;state=s;profile=null;$('#adminKey').value='';if(server===location.origin&&['127.0.0.1','localhost'].includes(location.hostname))await prepareRegisseur();paintStatus();await scanInterfaces();notice('Console régisseur ouverte. Les permissions sont vérifiées par le bridge.')}catch(e){admin='';notice(e.message)}}
async function scanInterfaces(){try{const b=await api('devices'),alternative=$('#alternativeDevice').checked,devices=b.devices.filter(d=>alternative||d.mr18||d.midasUsb);$('#interfaceSelect').innerHTML=devices.length?devices.map(d=>`<option value="${d.id}" ${(!alternative&&d.inputs<18)||d.usable===false?'disabled':''}>${esc(d.name)} · ${esc(d.driver)} · ${d.inputs} IN${d.recommended?' · RECOMMENDED':alternative?' · ALTERNATIVE':''}</option>`).join(''):'<option value="">MR18 not detected · RETRY</option>';const preferred=devices.find(d=>d.usable!==false&&(d.mr18||d.midasUsb)&&d.asio&&!d.asio4all)||devices.find(d=>d.usable!==false&&(alternative||d.mr18||d.midasUsb));if(preferred)$('#interfaceSelect').value=preferred.id;if(!devices.length)notice('MR18 not detected. RETRY ou Settings → Advanced Audio → Use another audio device.');}catch(e){notice(e.message)}}
async function waitIce(connection){if(connection.iceGatheringState==='complete')return;await new Promise((resolve,reject)=>{const timer=setTimeout(()=>{connection.removeEventListener('icegatheringstatechange',done);reject(Error('Délai de collecte réseau dépassé'))},8000);function done(){if(connection.iceGatheringState==='complete'){clearTimeout(timer);connection.removeEventListener('icegatheringstatechange',done);resolve()}}connection.addEventListener('icegatheringstatechange',done)})}
function tuneStereo(sdp){return sdp.replace(/a=fmtp:(\d+) ([^\r\n]+)/g,(line,pt,params)=>new RegExp('a=rtpmap:'+pt+' opus/','i').test(sdp)?`a=fmtp:${pt} ${params};stereo=1;sprop-stereo=1;maxaveragebitrate=128000`:line)}
async function startAudio(){
 if(native){if(!profile){notice('Rejoins le serveur avant de lancer le monitoring.');show('audio');return}if(native.start(server,personalToken(),Number($('#pcmTarget').value))===false){notice('Démarrage Android refusé : reconnecte le serveur LAN.');return}desired=true;activeEngine='native';put('fosa_network_resume:'+server,true);stats();return}
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
function stopAudio(){if(native)native.stop();desired=false;connectingGeneration++;connecting=false;put('fosa_network_resume:'+server,false);stopTalk();lowOutput?.close();lowOutput=null;lowMetrics=null;activeEngine=null;pc?.close();pc=null;direct=null;transceiver=null;outputSource?.disconnect();outputSource=null;audio.srcObject=null;receivedPackets=0;playbackReady=false;telemetry={rtt:null,jitter:null,loss:null,buffer:null};micRequest++;mic?.getTracks().forEach(t=>t.stop());mic=null;micEnabled=false;micPending=false;if(token())api('disconnect',{body:{}}).catch(()=>{});paintStatus()}
// Resume Web Audio in the user's original gesture, before joining/ICE/network awaits.
// This receive-only path never opens a microphone and works on an HTTP LAN origin.
function unlockOutput(){
 if(native)return;
 try{const C=window.AudioContext||window.webkitAudioContext;if(!C)return;
  if(!outputContext){try{outputContext=new C({latencyHint:'interactive',sampleRate:48000})}catch{outputContext=new C({latencyHint:'interactive'})};outputGain=outputContext.createGain();outputGain.gain.value=localPanic?0:1;outputGain.connect(outputContext.destination);outputContext.onstatechange=()=>{lowOutput?.reset();playbackReady=outputContext.state==='running';paintStatus()}}
  outputContext.resume().then(()=>{playbackReady=outputContext.state==='running';paintStatus()}).catch(()=>notice('Touche ÉCOUTER pour autoriser la sortie audio.'));
 }catch(e){notice('Sortie audio : '+e.message)}
}
function attachOutput(stream){
 outputSource?.disconnect();audio.srcObject=stream;
 if(outputContext){audio.muted=true;outputSource=outputContext.createMediaStreamSource(stream);outputSource.connect(outputGain);audio.play().catch(()=>{});playbackReady=outputContext.state==='running'}
 else{audio.muted=false;audio.play().then(()=>{playbackReady=true;paintStatus()}).catch(()=>notice('Audio reçu : touche ÉCOUTER pour autoriser la lecture.'))}
}
function applyJitter(){for(const r of pc?.getReceivers()||[])if('jitterBufferTarget'in r)try{r.jitterBufferTarget=Number($('#jitterTarget').value)}catch{}}
function mr18Talk(){return !!regisseurCredentials&&micDevice.startsWith('mr18:')}
function microphoneBlocked(){
 if(mr18Talk())return state?.connected&&profile?.talkAllowed?'':'Entrée MR18 ou permission Talkback indisponible.';
 if(native)return !profile?.talkAllowed?'Micro bloqué par le régisseur. Autorise-le depuis la console PC.':'';
 if(!isSecureContext)return 'Ce lien HTTP permet seulement l’écoute. Sur le PC, choisis le QR « Écoute + talkback · sécurisé », puis ouvre-le dans Chrome sur Android ou Safari sur iPhone.';
 if(!navigator.mediaDevices?.getUserMedia)return 'Ce navigateur ne permet pas l’accès au micro. Ouvre le QR sécurisé dans Chrome sur Android ou Safari sur iPhone, plutôt que dans une application de messagerie.';
 if(!profile)return 'Rejoins le PC serveur avec ton profil avant d’activer le micro.';
 if(!profile.talkAllowed)return 'Micro bloqué côté FOSA. Sur le PC : LIVE → AUTORISER LES MICROS CONNECTÉS, ou cocher Micro sous ton nom dans MATRIX.';
 return '';
}
async function enableMic(){
 if(native){if(!profile?.talkAllowed){notice(microphoneBlocked());return}if(!desired)await startAudio();native.microphone(!micEnabled);notice('Autorise le microphone Android, puis maintiens TALK.');return}
 if(micEnabled){await disableMic();return}if(micPending)return;
 const blocked=microphoneBlocked();if(blocked){notice(blocked);return}
 if(!direct?.ready){
  unlockOutput();const preparation=++micRequest;micPending=true;paintStatus();
  try{await startAudio();const deadline=Date.now()+8000;while(!direct?.ready&&desired&&Date.now()<deadline&&preparation===micRequest)await new Promise(r=>setTimeout(r,50));if(preparation!==micRequest||!profile?.talkAllowed)return;if(!direct?.ready)throw Error('La liaison talkback ne répond pas. Vérifie la connexion au PC puis réessaie.')}catch(e){notice(e.message);return}finally{if(preparation===micRequest)micPending=false;paintStatus()}
 }
 if(mr18Talk()&&direct?.ready){micEnabled=true;paintStatus();return}
 if(!direct?.ready||!transceiver){notice('Touche ÉCOUTER avant d’activer ton micro.');return}
 const request=++micRequest,sender=transceiver.sender;micPending=true;paintStatus();let captured;
 try{captured=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:false,channelCount:1,...(micDevice?{deviceId:{exact:micDevice}}:{})}});captured.getTracks().forEach(t=>t.enabled=false);if(request!==micRequest||sender!==transceiver?.sender||!profile?.talkAllowed||!direct?.ready){captured.getTracks().forEach(t=>t.stop());return}await sender.replaceTrack(captured.getAudioTracks()[0]);if(request!==micRequest||sender!==transceiver?.sender||!profile?.talkAllowed||!direct?.ready){captured.getTracks().forEach(t=>t.stop());await sender.replaceTrack(null);return}mic=captured;micEnabled=true;mic.getAudioTracks()[0].onended=()=>{disableMic();notice('Micro interrompu par le système. Active-le à nouveau pour parler.')};notice('Micro prêt : '+(mic.getAudioTracks()[0].label||'micro système')+'. Maintiens TALK pour parler.');await microphoneInputs()}
 catch(e){captured?.getTracks().forEach(t=>t.stop());if(request===micRequest)notice(e.name==='NotAllowedError'?'Micro refusé. Autorise le microphone pour FOSA dans le navigateur et les réglages du téléphone ou du PC, puis réessaie.':e.name==='NotFoundError'?'Aucun microphone disponible sur cet appareil.':e.name==='NotReadableError'?'Ce micro est occupé ou bloqué par le système. Ferme les autres applications utilisant le micro, ou choisis une autre source puis réessaie.':e.name==='OverconstrainedError'?'Le micro choisi n’est plus disponible. Sélectionne Micro système puis réessaie.':'Micro : '+e.message)}finally{if(request===micRequest)micPending=false;await microphoneInputs();paintStatus()}
}
async function disableMic(){if(native)native.microphone(false);micRequest++;stopTalk();mic?.getTracks().forEach(t=>t.stop());mic=null;micEnabled=false;micPending=false;try{await transceiver?.sender.replaceTrack(null)}catch{}paintStatus()}
function startTalk(e){if(!micEnabled||!profile?.talkAllowed||!(native?nativeState.connected:direct?.ready)){notice('Active d’abord ton micro après autorisation du régisseur.');return}e?.preventDefault();if(e?.pointerId!==undefined)e.currentTarget?.setPointerCapture?.(e.pointerId);talking=true;talkConfirmed=false;mic?.getTracks().forEach(t=>t.enabled=true);sendControl();paintStatus()}
function stopTalk(){if(!talking)return;talking=false;talkConfirmed=false;mic?.getTracks().forEach(t=>t.enabled=false);sendControl();paintStatus()}
function sendControl(){if(native){native.control(talking,$('#talkTarget').value);talkConfirmed=talking&&!!nativeState.talk;return}if(!desired||!profile||!state)return;const sequence=++controlSequence;api('control',{body:{sequence,talk:talking,target:$('#talkTarget').value,talkSource:mr18Talk()?Number(micDevice.split(':')[1]):null,metrics:{...telemetry,network:networkType(),audioLatency:null,playback:playbackReady,packets:receivedPackets,pcm:activeEngine==='pcm'?lowMetrics:null}},timeout:2000}).then(reply=>{if(sequence!==controlSequence)return;talkConfirmed=talking&&reply.talkActive===true;if(!reply.talkAllowed&&(micEnabled||micPending))disableMic();paintStatus()}).catch(()=>{if(talking){stopTalk();notice('TALK coupé : liaison locale interrompue.')}})}
async function stats(){if(native){try{nativeState=JSON.parse(native.status());telemetry={rtt:nativeState.rtt??null,jitter:nativeState.jitter??null,loss:nativeState.loss??null,buffer:nativeState.buffer??null};playbackReady=!!nativeState.playback;micEnabled=!!nativeState.mic;if(nativeState.packets>receivedPackets)lastPacketsAt=Date.now();receivedPackets=nativeState.packets||0;if(nativeState.panic)localPanic=true;if(nativeState.error)notice(nativeState.error);paintStatus();if(page==='diagnostic')renderDiagnostic()}catch{}return}if(!pc||pc.connectionState!=='connected')return;try{const rows=await pc.getStats();let pair,rtp;rows.forEach(r=>{if(r.type==='transport'&&r.selectedCandidatePairId)pair=rows.get(r.selectedCandidatePairId);if(r.type==='inbound-rtp'&&r.kind==='audio')rtp=r});if(pair)telemetry.rtt=Number.isFinite(pair.currentRoundTripTime)?pair.currentRoundTripTime*1000:null;if(activeEngine==='pcm'&&lowMetrics){telemetry.jitter=lowMetrics.jitterMs;telemetry.buffer=lowMetrics.bufferedMs;if(previousLowStats){const missing=Math.max(0,lowMetrics.missing-previousLowStats.missing),received=Math.max(0,lowMetrics.received-previousLowStats.received);telemetry.loss=missing+received>0?Math.min(100,missing/(missing+received)*100):null}previousLowStats={missing:lowMetrics.missing,received:lowMetrics.received}}else if(rtp){if((rtp.packetsReceived||0)>receivedPackets)lastPacketsAt=Date.now();receivedPackets=rtp.packetsReceived||0;telemetry.jitter=Number.isFinite(rtp.jitter)?rtp.jitter*1000:null;if(previousStats&&previousStats.id===rtp.id){const lost=Math.max(0,(rtp.packetsLost||0)-previousStats.lost),received=Math.max(0,(rtp.packetsReceived||0)-previousStats.received),n=(rtp.jitterBufferEmittedCount||0)-previousStats.emitted;telemetry.loss=lost+received>0?lost/(lost+received)*100:null;telemetry.buffer=n>0?((rtp.jitterBufferDelay||0)-previousStats.delay)/n*1000:null}previousStats={id:rtp.id,lost:rtp.packetsLost||0,received:rtp.packetsReceived||0,emitted:rtp.jitterBufferEmittedCount||0,delay:rtp.jitterBufferDelay||0}}paintStatus();if(page==='diagnostic')renderDiagnostic()}catch{}}
function renderDiagnostic(){
 const s=state,d=s?.device;const metrics=[['MR18 / interface',s?.connected?'Connectée':'NON CONNECTÉE'],['Driver',d?.driver||'—'],['ASIO',d?.asio?'OUI':'NON / absent'],['Fréquence',s?.sampleRate?s.sampleRate+' Hz':'—'],['Buffer réel',s?.buffer?s.buffer+' éch.':'—'],['Entrées',s?.inputs??'—'],['Canaux avec signal',s?s.channels.filter(c=>c.signal).length:'—'],['CPU bridge',fmt(s?.cpu,' %')],['RAM bridge',fmt(s?.ramMB,' Mo')],['Réseau',networkType()],[native?'RTT contrôle HTTP · MEASURED':'RTT WebRTC · MEASURED',fmt(telemetry.rtt)],[native||activeEngine==='pcm'?'Variation d’arrivée PCM':'Jitter',fmt(telemetry.jitter)],[native?'Indisponibles à la lecture / session':activeEngine==='pcm'?'Indisponibles à la lecture / intervalle':'Pertes / intervalle',fmt(telemetry.loss,' %')],['Buffer réception',fmt(telemetry.buffer)],['Latence driver',fmt(s?.captureLatencyMs)],['Moteur écoute',native?'PCM / UDP Android':activeEngine==='pcm'?'PCM · direct':activeEngine==='opus'?'Stable · Opus':'Arrêté'],['Durée du bloc audio',native||activeEngine==='pcm'?'5 ms':s?s.opusFrameMs+' ms (Opus)':'—'],['Buffer PCM ciblé',native||activeEngine==='pcm'?$('#pcmTarget').value+' ms':'—'],['Attente callback → émission',fmt(native?nativeState.captureQueueMs:lowMetrics?.captureQueueMs)],['Sortie Web Audio · estimation OS',fmt(Number.isFinite(outputContext?.baseLatency)?outputContext.baseLatency*1000:null)],['Périphérique sortie · estimation OS',fmt(Number.isFinite(outputContext?.outputLatency)?outputContext.outputLatency*1000:null)],['PCM absents / tardifs / sautés',lowMetrics?`${lowMetrics.missing} / ${lowMetrics.late} / ${lowMetrics.skipped}`:'—'],['Sous-alimentation PCM',lowMetrics?`${lowMetrics.underrunFrames} échantillons`:'—'],['XRUN / pertes capture',s?`${s.xruns} / ${s.captureDrops}`:'—'],['Qualité réseau',quality()],['Paquets audio reçus',receivedPackets],['Sortie navigateur',outputContext?.state|| (playbackReady?'Lecture active':'En attente')],['Audio DSP load',fmt(s?.audioCpu,' %')],['Callback capture',fmt(s?.callbackTimeMs)],['DSP + worker scheduling',fmt(s?.processingMs)],['Output route',nativeState.output||'UNKNOWN'],['Android output buffer',native?fmt(nativeState.outputBufferMs):'—'],['Android underruns',native?nativeState.underruns??'—':'—'],['Android Low Latency accordé',native&&nativeState.playback?(nativeState.lowLatencyMode?'OUI':'NON'):'—'],['Latence audio bout-en-bout','UNKNOWN · NON MESURÉE · test physique requis']];$('#diagnostics').innerHTML=metrics.map(([k,v])=>`<div class="metric"><span>${esc(k)}</span><b>${esc(v)}</b></div>`).join('');renderMapping();
}
function renderMapping(){
 $('#inputMapping').innerHTML='<table><thead><tr><th>Sélecteur ASIO demandé</th><th>FOSA</th><th>RMS / peak dBFS</th></tr></thead><tbody>'+(state?.channels||[]).map((c,i)=>`<tr><td>Input ${i+1}</td><td>CH${i+1}</td><td>${fmt(c.rmsDb,'')} / ${fmt(c.peakDb,'')}</td></tr>`).join('')+'</tbody></table>';
}
async function testInput(){
 if(!state?.connected){notice('Ouvre d’abord les 18 entrées ASIO.');return}
 const expected=Number($('#physicalInput').value);$('#testInput').disabled=true;
 $('#mappingResult').textContent=`Parle ou envoie un signal uniquement dans l’entrée physique ${expected}, pendant 4 secondes…`;
 const peaks=Array(state.channelCount||18).fill(-120);
 try{for(let n=0;n<32;n++){const m=await api('meters',{timeout:1500});if(!m.connected)throw Error('Capture interrompue');m.channels.forEach((c,i)=>{peaks[i]=Math.max(peaks[i],c.rmsDb??-120)});await new Promise(r=>setTimeout(r,125))}
  const order=peaks.map((db,i)=>({db,i})).sort((a,b)=>b.db-a.db),loud=order[0];
  if(loud.db<-60)$('#mappingResult').textContent='Aucun signal assez fort détecté. Vérifie le gain et le routage USB MR18, puis recommence.';
  else if(order.length>1&&loud.db-order[1].db<10)$('#mappingResult').textContent='Plusieurs canaux reçoivent du signal. Isole cette entrée avant de conclure sur son mapping.';
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
 if(!serverNetwork.secure?.enabled&&$('#shareMode').value==='secure')$('#shareMode').value='lan';
 const secured=$('#shareMode').value==='secure',nativeShare=$('#shareMode').value==='native';$('#adapterLabel').hidden=secured||adapters.length<2;
 const link=new URL(secured?serverNetwork.joinUrl:serverNetwork.lanJoinUrl);if(!secured&&select.value)link.hostname=select.value;
 if(nativeShare){const params=new URLSearchParams(link.hash.slice(1));link.href='fosa://bodypack?'+new URLSearchParams({server:link.origin,code:params.get('code')||'',session:params.get('session')||''})}$('#setupAddress').value=link.href;$('#setupCode').textContent=serverNetwork.joinCode;
 $('#shareModeInfo').textContent=nativeShare?'Installe FOSA Android, puis scanne ce QR avec la caméra du téléphone ou saisis l’IP dans l’app. Audio + Talkback sur le LAN, sans Internet.':secured?'Écoute + talkback · Internet pour établir la session · micro autorisé dans MATRIX.':serverNetwork.secure?.enabled?'Écoute LAN sans Internet · micro indisponible sur téléphone avec ce QR.':'Écoute LAN · lance start-mobile-windows.cmd pour obtenir le QR sécurisé avec talkback.';
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
  [connected.length>0,'Liaison audio',connected.length?connected.length+' Bodypack(s) relié(s)':'En attente de ÉCOUTER sur le téléphone'],
  [false,'Latence physique','Non mesurée · test dans DIAGNOSTIC']
 ];
 $('#setupChecks').innerHTML=checks.map(([ok,name,detail])=>`<li><b class="${ok?'check-ok':'check-wait'}">${ok?'✓':'…'} ${esc(name)}</b> · ${esc(detail)}</li>`).join('');
 $('#setupReady').textContent=!state?.connected?'ENTRÉES À VÉRIFIER':connected.length?'MUSICIEN RELIÉ':'PRÊT À CONNECTER';
 $('#networkHelp').textContent=serverNetwork.lastClient?'Le serveur a reçu une requête d’un autre appareil. Si le son manque, consulte DIAGNOSTIC : paquets reçus, lecture et source sont contrôlés séparément.':'Le contrôle HTTP depuis ce PC ne prouve pas l’accès depuis le téléphone. Si le QR ne s’ouvre pas : même Wi-Fi, réseau invité et isolation des appareils, VPN ou autorisation réseau local du navigateur sont à vérifier.';
 $('#welcomeCard').hidden=true;
}
function connectedReceivers(){return (state?.users||[]).filter(p=>p.connected&&p.id!==regisseurCredentials?.id)}
function renderPhoneMicrophones(){
 const card=$('#pcMicrophones');card.hidden=!admin;if(!admin)return;
 const users=connectedReceivers(),blocked=users.filter(p=>!p.talkAllowed),button=$('#allowConnectedMics');
 button.disabled=phoneMicSaving||!blocked.length;
 button.textContent=phoneMicSaving?'AUTORISATION EN COURS…':'AUTORISER LES MICROS CONNECTÉS';
 $('#phoneMicSummary').textContent=!users.length?'Les récepteurs apparaissent ici après avoir touché ÉCOUTER.':blocked.length?`${blocked.length} micro(s) à autoriser sur ${users.length} récepteur(s) connecté(s).`:`${users.length} micro(s) autorisé(s) côté FOSA. Active ensuite le micro sur chaque appareil.`;
 if(phoneMicSaving){$$('#phoneMicPermissions input').forEach(el=>el.disabled=true);return}
 const html=users.map(p=>`<label class="phone-microphone"><input type="checkbox" data-phone-mic="${esc(p.id)}" ${p.talkAllowed?'checked':''} aria-label="Autoriser le micro de ${esc(p.name)}"><span><strong>${esc(p.name)}</strong><small>${esc(p.role)} · ${p.talkAllowed?'Micro autorisé':'Micro bloqué'}</small></span><b>Micro</b></label>`).join('');
 const list=$('#phoneMicPermissions'),signature=JSON.stringify(users.map(p=>[p.id,p.name,p.role,p.talkAllowed]));
 if(list.dataset.signature!==signature){list.innerHTML=html;list.dataset.signature=signature}
 $$('[data-phone-mic]').forEach(el=>{el.disabled=false;el.checked=users.find(p=>p.id===el.dataset.phoneMic)?.talkAllowed===true});
}
async function setPhoneMicrophones(ids,allowed){
 if(!admin||phoneMicSaving||!ids.length)return;
 phoneMicSaving=true;phonePermissionRevision++;renderPhoneMicrophones();let done=0;
 try{for(const id of ids){const updated=await api('matrix',{body:{id,talkAllowed:allowed}});phonePermissionRevision++;const user=state?.users?.find(p=>p.id===id);if(user)Object.assign(user,updated);done++}await refresh();notice(allowed?'Micro(s) autorisé(s). Sur les appareils : ACTIVER MON MICRO, autoriser le navigateur, puis maintenir TALK.':'Autorisation micro retirée. La parole est coupée côté serveur.')}catch(e){notice(`${done}/${ids.length} autorisation(s) modifiée(s). `+e.message)}finally{phoneMicSaving=false;renderPhoneMicrophones()}
}
function renderTalkTargets(){const selected=$('#talkTarget').value,targets=(state?.talkTargets||[]).filter(p=>p.id!==profile?.id);const roles=[...new Set(targets.map(p=>p.role))];const options='<option value="all">Tous les auditeurs autorisés</option>'+roles.map(role=>`<option value="role:${esc(role)}">Profil · ${esc(role)}</option>`).join('')+targets.map(p=>`<option value="${esc(p.id)}">${esc(p.name)} · ${esc(p.role)}</option>`).join('');for(const el of $$('#talkTarget,#liveTalkTarget')){if(el.innerHTML!==options)el.innerHTML=options;el.value=[...el.options].some(o=>o.value===selected)?selected:'all'}if($('#talkTarget').value!==selected)stopTalk()}
function renderDevices(){const users=state?.users;if(!users){$('#devices').textContent='Console régisseur requise.';return}$('#devices').innerHTML=users.length?`<table><thead><tr>${['Utilisateur','Appareil','Mix','Moteur','Talkback','Connexion','RTT','Gain / Master','Output / Limiter','Jitter / Loss','Buffer / Battery','Latence audio'].map(x=>`<th>${x}</th>`).join('')}</tr></thead><tbody>${users.map(p=>`<tr><td>${esc(p.name)}</td><td title="${esc(p.device)}">${/Android/i.test(p.device)?'Android':/iPhone|iPad/i.test(p.device)?'iOS':p.connected?'PC / navigateur':'—'}</td><td>${esc(p.role)}</td><td>${p.monitoringEngine==='native'?'PCM / UDP':p.monitoringEngine==='pcm'?'PCM 5 ms':p.connected?'Opus':'—'}${p.pcm?'<br>'+p.pcm.congestionDrops+' abandons serveur':''}</td><td>${p.talkAllowed?'Micro autorisé':'Écoute'}${p.talkListen?'':' OFF'}</td><td>${p.connected?esc(p.network):'Hors ligne'}</td><td>${fmt(p.metrics?.rtt)}</td><td>+${p.mix.monitorGainDb||0} dB / ${p.mix.master>0?(20*Math.log10(p.mix.master)).toFixed(1):'−∞'} dB</td><td>${fmt(p.levels?.outputPeakDb,' dBFS')} / ${fmt(p.levels?.limiterReductionDb,' dB GR')}</td><td>${fmt(p.metrics?.jitter)} / ${fmt(p.metrics?.loss,' %')}</td><td>${fmt(p.metrics?.buffer)} / ${fmt(p.metrics?.battery,' %')}</td><td>UNKNOWN</td></tr>`).join('')}</tbody></table>`:'Aucun profil musicien. Rejoins le bridge sur un autre appareil.'}
function renderMatrix(){const users=state?.users;if(!admin||!users){$('#matrix').textContent='';return}$('#matrixHint').textContent='Assignations et permissions appliquées par le serveur. Mute et niveaux sont propres à chaque musicien.';if(!users.length){$('#matrix').textContent='Aucun profil musicien connecté.';return}
 $('#matrix').innerHTML=`<table><thead><tr><th>Entrée</th>${users.map(p=>`<th>${esc(p.name)}<br><label><input type="checkbox" data-permission="locked" data-user="${p.id}" ${p.locked?'checked':''}>Verrouiller</label><label><input type="checkbox" data-permission="talkAllowed" data-user="${p.id}" ${p.talkAllowed?'checked':''}>Micro</label><label><input type="checkbox" data-permission="talkListen" data-user="${p.id}" ${p.talkListen?'checked':''}>Écoute TB</label><label>Boost max (dB)<input type="number" data-limit="gainMaxDb" data-user="${p.id}" min="0" max="12" value="${p.limits?.gainMaxDb??12}"></label><label>Master max (0–1)<input type="number" data-limit="masterMax" data-user="${p.id}" min="0" max="1" step=".05" value="${p.limits?.masterMax??1}"></label><label>More Me max (dB)<input type="number" data-limit="moreMeMaxDb" data-user="${p.id}" min="0" max="6" value="${p.limits?.moreMeMaxDb??6}"></label>${['gain','pan','solo','mute'].map(key=>`<label><input type="checkbox" data-control-permission="${key}" data-user="${p.id}" ${p.permissions?.[key]!==false?'checked':''}>${key.toUpperCase()}</label>`).join('')}<button data-copy="${p.id}">COPIER MON MIX</button></th>`).join('')}</tr></thead><tbody>${(state.channels||[]).map((c,i)=>`<tr><th>${String(i+1).padStart(2,'0')} · ${esc(c.name)}</th>${users.map(p=>`<td><div class="matrix-cell"><input type="checkbox" data-assign="${i}" data-user="${p.id}" ${p.allowed[i]?'checked':''} aria-label="Assigner ${esc(c.name)} à ${esc(p.name)}"><input type="range" min="0" max="100" value="${Math.round(p.mix.channels[i].gain*100)}" data-matrix-gain="${i}" data-user="${p.id}" aria-label="Niveau ${esc(c.name)} pour ${esc(p.name)}"><button data-matrix-mute="${i}" data-user="${p.id}" class="${p.mix.channels[i].mute?'active':''}">M</button></div></td>`).join('')}</tr>`).join('')}</tbody></table>`;
}
function applyPreset(){if(profile?.locked){notice('Mix verrouillé.');return}const role=$('#preset').value;const preferences={Batteur:['kick','snare','batterie'],Bassiste:['bass','basse','kick'],Guitariste:['guit','chant','vocal'],Clavier:['piano','key','clavier','chant'],Chant:['chant','vocal','piano'],Chef:[],Régisseur:[],Personnalisé:[]}[role]||[];mix.channels.forEach((c,i)=>{const meta=state?.channels?.[i];const text=(meta?.name+' '+meta?.role).toLowerCase();c.gain=preferences.some(x=>text.includes(x))?.8:.4;c.mute=false;c.solo=false;c.pan=0});edit();notice('Preset appliqué selon les noms/types des canaux. Ajuste ton écoute.')}
async function outputs(){try{const devices=await navigator.mediaDevices?.enumerateDevices();$('#output').innerHTML='<option value="">Sortie système</option>'+(devices||[]).filter(d=>d.kind==='audiooutput').map((d,i)=>`<option value="${esc(d.deviceId)}">${esc(d.label||'Sortie '+(i+1))}</option>`).join('');$('#outputInfo').textContent=audio.setSinkId?'Sélectionne une sortie exposée par ton navigateur.':'Choisis la sortie dans les réglages du téléphone ; ce navigateur ne permet pas de la changer.';$('#output').disabled=outputContext?!outputContext.setSinkId:!audio.setSinkId}catch(e){notice(e.message)}}
function setupMediaSession(){if(!('mediaSession'in navigator))return;try{navigator.mediaSession.metadata=new MediaMetadata({title:'FOSA · Mon mix',artist:profile?.name||'Audio Network'});navigator.mediaSession.setActionHandler('pause',()=>{if(!profile?.locked){mix.muteAll=true;edit()}});navigator.mediaSession.setActionHandler('play',()=>{unlockOutput();if(!profile?.locked){mix.muteAll=false;edit()}if(!outputContext)audio.play().catch(()=>{})})}catch{}}
function bindSolo(){
 $$('[data-solo]').forEach(b=>{b.onpointerdown=e=>{e.preventDefault();b.setPointerCapture(e.pointerId);mix.channels[Number(b.dataset.solo)].solo=true;edit()};const release=()=>{if(mix.channels[Number(b.dataset.solo)].solo){mix.channels[Number(b.dataset.solo)].solo=false;edit()}};b.onpointerup=release;b.onpointercancel=release;b.onlostpointercapture=release;b.onkeydown=e=>{if(e.key===' '){e.preventDefault();mix.channels[Number(b.dataset.solo)].solo=true;edit()}};b.onkeyup=release;b.onblur=release});
}
function bind(){
 bindBodypack();
 document.addEventListener('click',async e=>{const t=e.target.closest('button');if(!t)return;
 if(t.dataset.view)show(t.dataset.view);
 if(t.dataset.step){const i=Number(t.dataset.i);mix.channels[i].gain=Math.max(0,Math.min(1,mix.channels[i].gain+Number(t.dataset.step)/100));edit()}
 if(t.dataset.mute!==undefined){const i=Number(t.dataset.mute);mix.channels[i].mute=!mix.channels[i].mute;edit()}
 if(t.dataset.rename!==undefined){const i=Number(t.dataset.rename);try{await api('channel',{body:{number:i+1,name:$(`[data-name="${i}"]`).value,role:$(`[data-role="${i}"]`).value,group:$(`[data-role="${i}"]`).value,order:Number($(`[data-order="${i}"]`).value)-1,disabled:$(`[data-disabled="${i}"]`).checked}});await refresh();notice('Canal renommé.')}catch(err){notice(err.message)}}
 if(t.dataset.do==='listen')startAudio();if(t.dataset.do==='mute'&&!profile?.locked){mix.muteAll=!mix.muteAll;edit()}
 if(t.dataset.matrixMute!==undefined){const p=state.users.find(p=>p.id===t.dataset.user);p.mix.channels[Number(t.dataset.matrixMute)].mute=!p.mix.channels[Number(t.dataset.matrixMute)].mute;try{await api('mix',{body:{id:p.id,mix:p.mix}});await refresh()}catch(err){notice(err.message)}}
 if(t.dataset.copy){try{await api('mix',{body:{id:t.dataset.copy,mix:normalize(mix)}});await refresh();notice('Mix copié.')}catch(err){notice(err.message)}}
 });
 $('#mixer').addEventListener('input',e=>{const t=e.target;if(t.dataset.gain!==undefined)mix.channels[Number(t.dataset.gain)].gain=Number(t.value)/100;if(t.dataset.pan!==undefined)mix.channels[Number(t.dataset.pan)].pan=Number(t.value)/100;edit()});
 $$('#master,#liveMaster').forEach(e=>e.oninput=()=>{if(profile?.locked){syncMix();return}mix.master=Number(e.value)/100;edit()});
 bindSolo();
 $('#matrix').onchange=async e=>{const t=e.target,p=state?.users?.find(p=>p.id===t.dataset.user);if(!p)return;try{if(t.dataset.limit)await api('matrix',{body:{id:p.id,limits:{[t.dataset.limit]:Number(t.value)}}});if(t.dataset.controlPermission)await api('matrix',{body:{id:p.id,permissions:{...p.permissions,[t.dataset.controlPermission]:t.checked}}});if(t.dataset.permission)await api('matrix',{body:{id:p.id,[t.dataset.permission]:t.checked}});if(t.dataset.assign!==undefined){p.allowed[Number(t.dataset.assign)]=t.checked;await api('matrix',{body:{id:p.id,allowed:p.allowed}})}if(t.dataset.matrixGain!==undefined){p.mix.channels[Number(t.dataset.matrixGain)].gain=Number(t.value)/100;await api('mix',{body:{id:p.id,mix:p.mix}})}await refresh()}catch(err){notice(err.message)}};
 $('#allowConnectedMics').onclick=()=>setPhoneMicrophones(connectedReceivers().filter(p=>!p.talkAllowed).map(p=>p.id),true);
 $('#phoneMicPermissions').onchange=e=>{const id=e.target.dataset.phoneMic;if(id)setPhoneMicrophones([id],e.target.checked)};
 $('#stopPcTalk').onclick=stopAudio;
 $$('.microphone-input').forEach(el=>el.onchange=async()=>{micDevice=el.value;put('fosa_talkback_device',micDevice);$$('.microphone-input').forEach(other=>other.value=micDevice);await disableMic();notice('Source micro choisie. Touche ACTIVER MON MICRO pour l’utiliser.')});
 $('#detect').onclick=detect;$('#joinNetwork').onclick=()=>join(false);$('#joinListen').onclick=()=>join(true);$('#adminLogin').onclick=adminLogin;$('#scanInterfaces').onclick=scanInterfaces;
 $('#lanAdapter').onchange=()=>updateSetup().catch(e=>notice(e.message));$('#shareMode').onchange=()=>updateSetup().catch(e=>notice(e.message));$('#testInput').onclick=testInput;
 $('#startCapture').onclick=async()=>{try{const s=await api('configure',{body:{device:Number($('#interfaceSelect').value),buffer:Number($('#bufferSelect').value),alternative:$('#alternativeDevice').checked},timeout:12000});state=s;notice('Interface ouverte. Attente des premiers échantillons…');await refresh()}catch(e){notice(e.message)}};
 $('#startAudio').onclick=startAudio;$('#stopAudio').onclick=stopAudio;$('#muteAll').onclick=()=>{if(profile?.locked)return;mix.muteAll=!mix.muteAll;edit()};
 $('#saveMix').onclick=()=>{put(contextKey()+':saved',normalize(mix));notice('Mix sauvegardé sur cet appareil.')};$('#restoreMix').onclick=()=>{const saved=get(contextKey()+':saved',null);if(!saved){notice('Aucun mix sauvegardé pour ce profil.');return}if(profile?.locked)return;mix=normalize(saved);edit();notice('Mix restauré.')};$('#resetMix').onclick=()=>{if(profile?.locked)return;mix=blank();edit()};$('#applyPreset').onclick=applyPreset;
 $('#ducking').onchange=()=>{if(profile?.locked)return;mix.ducking=Number($('#ducking').value);edit()};$('#enableMic').onclick=enableMic;$('#liveEnableMic').onclick=enableMic;
 $$('#talkTarget,#liveTalkTarget').forEach(el=>el.onchange=()=>{stopTalk();$$('#talkTarget,#liveTalkTarget').forEach(other=>other.value=el.value)});
 $$('.talk').forEach(b=>{b.onkeydown=e=>{if([' ','Enter'].includes(e.key)&&!e.repeat)startTalk(e)};b.onkeyup=e=>{if([' ','Enter'].includes(e.key)){e.preventDefault();stopTalk()}}});
 $$('.talk').forEach(b=>{b.onpointerdown=startTalk;b.onpointerup=stopTalk;b.onpointercancel=stopTalk;b.onlostpointercapture=stopTalk});addEventListener('blur',stopTalk);document.addEventListener('visibilitychange',()=>{if(document.hidden){stopTalk();if(mix.channels.some(c=>c.solo)){mix.channels.forEach(c=>c.solo=false);edit()}}else{if(wake)requestWake();refresh()}});
 $$('.monitoring-engine').forEach(el=>el.onchange=()=>{const restart=desired;engine=el.value;put('fosa_monitoring_engine',engine);$$('.monitoring-engine').forEach(other=>other.value=engine);if(restart){stopAudio();startAudio();notice('Changement de moteur : l’écoute redémarre, le micro doit être réactivé.')}});
 $('#pcmTarget').value=String(get('fosa_pcm_target',10));$('#pcmTarget').onchange=()=>{const ms=Number($('#pcmTarget').value);put('fosa_pcm_target',ms);lowOutput?.target(ms);native?.target(ms);$('#latencyProfile').value='custom'};
 $('#jitterTarget').onchange=applyJitter;$('#refreshOutputs').onclick=outputs;$('#output').onchange=async()=>{try{if(outputContext?.setSinkId)await outputContext.setSinkId($('#output').value);else if(!outputContext)await audio.setSinkId($('#output').value);else throw Error('Ce navigateur utilise la sortie système pour Web Audio');$('#outputInfo').textContent='Sortie sélectionnée : '+$('#output').selectedOptions[0].textContent}catch(e){notice('Sortie non modifiée : '+e.message)}};
 $('#wake').onclick=async()=>{if(wake){await wake.release();wake=null;$('#wake').classList.remove('active')}else requestWake()};
 $('#testLatency').onclick=async()=>{try{const start=performance.now();await api('health',{auth:false});$('#diagnosticNote').textContent=`Aller-retour commande (${direct?.ready?'liaison directe':relayClient?'signalisation Internet':'HTTP'}) : ${(performance.now()-start).toFixed(1)} ms. RTT WebRTC : ${fmt(telemetry.rtt)}. La latence audio bout-en-bout reste non mesurée : suis le test physique ci-dessous.`}catch(e){notice(e.message)}};
 $('#optimize').onclick=()=>{const known=Number.isFinite(telemetry.loss)&&Number.isFinite(telemetry.jitter);$('#diagnosticNote').textContent=known?'Suggestion : '+(telemetry.loss>1||telemetry.jitter>5||state?.xruns?'SAFE':'LIVE')+'. Aucun changement appliqué. Choisis le profil dans SETTINGS après vérification.':'Mesure réseau requise avant une recommandation. Aucun changement appliqué.'};
 $('#makeQr').onclick=async()=>{if(!admin){notice('Console régisseur requise pour générer le QR local.');return}try{serverNetwork=await api('network');const secured=$('#shareMode').value==='secure',url=new URL(secured?serverNetwork.joinUrl:serverNetwork.lanJoinUrl),params=new URLSearchParams(url.hash.slice(1));params.set('role',$('#qrRole').value);url.hash=params.toString();const address=$('#lanAdapter').value;if(!secured&&address)url.hostname=address;$('#joinLink').value=url.href;await sharedQr($('#qrBox'),$('#qrRole').value,address)}catch(e){notice(e.message)}};
 $('#copyLink').onclick=async()=>{try{if(!$('#joinLink').value)throw Error('Génère d’abord le lien.');await navigator.clipboard.writeText($('#joinLink').value);notice('Lien copié.')}catch(e){$('#joinLink').select();notice('Sélectionne et copie le lien. '+e.message)}};
 $('#forgetSession').onclick=()=>{stopAudio();delete credentials[server];put('fosa_network_credentials',credentials);admin='';regisseurCredentials=null;profile=null;state=null;show('audio');paintStatus();notice('Profil déconnecté sur cet appareil.')};
 $('#backFosa').onclick=()=>window.parent.postMessage({type:'fosa-network-close'},location.origin);
 addEventListener('message',e=>{if(e.source!==window.parent||e.origin!==location.origin)return;if(e.data?.type==='fosa-network-view')show(e.data.view);if(e.data?.type==='fosa-talkback-activity'){externalTalk=e.data.active===true;const gain=externalTalk?(mix.ducking===-99?0:10**(mix.ducking/20)):1;audio.volume=gain;if(outputGain&&!localPanic)outputGain.gain.setTargetAtTime(gain,outputContext.currentTime,.002)}});
 addEventListener('keydown',e=>{if(e.key==='Escape'&&embedded)window.parent.postMessage({type:'fosa-network-close'},location.origin)});addEventListener('beforeunload',e=>{if(desired){e.preventDefault();e.returnValue=''}});
}

function localMute(active){localPanic=active;if(native)native.panic(active);if(outputGain){const now=outputContext.currentTime;outputGain.gain.cancelScheduledValues(now);outputGain.gain.setValueAtTime(outputGain.gain.value,now);outputGain.gain.linearRampToValueAtTime(active?0:1,now+.005)}else audio.muted=active;paintBodypack()}
function paintBodypack(){
 document.body.classList.toggle('engineer',!!admin);$('#productMode').textContent=admin?'ENGINEER':'BODYPACK';$('#bodypackIdentity').textContent=profile?profile.name+' — '+profile.role:'FOSA STAGE';
 $('#panicMute').classList.toggle('engaged',localPanic||!!state?.panic);$('#releasePanic').hidden=!localPanic&&!state?.panic;$('#releasePanic').disabled=!!state?.panic&&!admin;$('#releasePanic').textContent=state?.panic&&!admin?'MUTE RÉGISSEUR':'RÉTABLIR L’ÉCOUTE';
 const levels=profile?.levels,users=(state?.users||[]).filter(p=>p.connected&&p.id!==regisseurCredentials?.id),net=quality();
 const rows=admin?[['AUDIO ENGINE',state?.connected?'READY':'WAITING'],['MONITOR ENGINE',state?.connected?'READY':'WAITING'],['BODYPACKS',users.length],['AUDIO LATENCY','UNKNOWN'],['LIMITERS ACTIVE',users.filter(p=>p.levels?.limiterReductionDb>.1).length],['DSP LOAD',fmt(state?.audioCpu,' %')]]:[['SERVER',state?'CONNECTED':'OFFLINE'],['AUDIO',desired&&state?.connected&&playbackReady&&receivedPackets&&Date.now()-lastPacketsAt<1500&&(native?nativeState.connected:pc?.connectionState==='connected')?'STREAMING':'STOPPED'],['LATENCY','UNKNOWN'],['NETWORK',net],['OUTPUT',nativeState.output||'SYSTEM / UNKNOWN'],['FORMAT',native?'PCM / UDP':activeEngine==='pcm'?'PCM / WEBRTC':activeEngine==='opus'?'OPUS':'—']];
 $('#healthStrip').innerHTML=rows.map(([key,value])=>`<div><small>${esc(key)}</small><b class="${['READY','CONNECTED','STREAMING','EXCELLENT'].includes(value)?'good':''}">${esc(value)}</b></div>`).join('');
 $('#limiterState').textContent=levels?(levels.limiterReductionDb>.1?'LIMITER −'+levels.limiterReductionDb.toFixed(1)+' dB':'LIMITER OK'):'WAITING FOR AUDIO';
 $('#outputMeters').innerHTML=[['MIX PEAK',levels?.mixPeakDb],['MIX RMS',levels?.mixRmsDb],['OUTPUT L',levels?.outputLDb],['OUTPUT R',levels?.outputRDb]].map(([label,v])=>`<div><small>${label} · MEASURED</small><b>${fmt(v,' dBFS')}</b><meter min="-60" max="0" low="-6" high="-3" optimum="-12" value="${Number.isFinite(v)?v:-60}"></meter></div>`).join('');
 const groups=[...new Set((state?.channels||[]).map(c=>c.group||c.role).filter(Boolean))],markup=groups.map(g=>`<button data-group="${esc(g)}" ${localLock||profile?.locked?'disabled':''}>${esc(g)} +3 dB</button>`).join('');if($('#quickGroups').innerHTML!==markup)$('#quickGroups').innerHTML=markup;
 $('#autoLevel').disabled=!profile||!desired||localLock||profile?.locked===true;$('#exportLogs').disabled=!admin;
}
async function refreshPresets(){try{const b=await api('presets');$('#sessionPresetList').innerHTML=b.presets.map(name=>`<option>${esc(name)}</option>`).join('')}catch(e){notice(e.message)}}
function bindBodypack(){
 $('#refreshPresets').onclick=refreshPresets;
 $('#saveSessionPreset').onclick=async()=>{try{await api('presets',{body:{action:'save',name:$('#sessionPresetName').value}});await refreshPresets();notice('Session sauvegardée sur le PC.')}catch(e){notice(e.message)}};
 $('#loadSessionPreset').onclick=async()=>{try{await api('presets',{body:{action:'load',name:$('#sessionPresetList').value}});await refresh();syncMix();notice('Session restaurée.')}catch(e){notice(e.message)}};
 $('#quickGroups').onclick=e=>{const button=e.target.closest('button[data-group]');if(!button||localLock||profile?.locked)return;const group=button.dataset.group;for(let i=0;i<mix.channels.length;i++){const meta=state?.channels?.[i];if((meta?.group||meta?.role)===group&&profile?.allowed?.[i]!==false&&profile?.permissions?.gain!==false)mix.channels[i].gain=Math.min(1,mix.channels[i].gain*10**(.15))}edit()};
 $('#panicMute').onclick=()=>{localMute(true);if(profile||admin)api('panic',{body:{active:true}}).catch(e=>notice('MUTE LOCAL ACTIF · serveur : '+e.message));};
 $('#releasePanic').onclick=async()=>{try{if(profile||admin)await api('panic',{body:{active:false}});mix.muteAll=false;localMute(false);await refresh();syncMix()}catch(e){notice(e.message)}};
 $('#monitorGain').oninput=()=>{mix.monitorGainDb=Number($('#monitorGain').value);edit()};$('#monoMix').onchange=()=>{mix.mono=$('#monoMix').checked;edit()};
 $('#mainChannel').onchange=()=>{mix.mainChannel=Number($('#mainChannel').value);edit()};
 $('#moreMe').onclick=async()=>{try{await flush();const b=await api('more-me',{body:{db:3}});mix=b.mix;confirmedMixVersion=b.mixVersion;syncMix();notice('MORE ME : canal principal augmenté, limité à 0 dB.')}catch(e){notice(e.message)}};
 $('#lockMix').onclick=()=>{localLock=!localLock;$('#lockMix').textContent=localLock?'UNLOCK MIX':'LOCK MIX';$('#lockMix').classList.toggle('active',localLock);syncMix();paintStatus()};
 $('#performanceMode').onchange=()=>{document.body.classList.toggle('performance',$('#performanceMode').checked);put('fosa_performance',$('#performanceMode').checked)};$('#performanceMode').checked=get('fosa_performance',false);$('#performanceMode').onchange();
 $('#alternativeDevice').onchange=scanInterfaces;
 $('#latencyProfile').onchange=()=>{const ms={ultra:5,live:10,safe:20}[$('#latencyProfile').value];if(ms){$('#pcmTarget').value=String(ms);lowOutput?.target(ms);native?.target(ms);put('fosa_pcm_target',ms);notice('Buffer réception ciblé : '+ms+' ms. La latence totale reste non mesurée. Le buffer ASIO est indépendant.')}};
 $('#autoLevel').onclick=async()=>{try{const b=await api('auto-level');if(!b.available){$('#levelAdvice').textContent=b.reason;return}levelRecommendation=b.recommendedGainDb;$('#levelAdvice').textContent=`Analyse ${b.seconds.toFixed(1)} s · RMS ${b.averageDb} dBFS · Peak ${b.peakDb} dBFS. Gain proposé +${b.recommendedGainDb} dB (headroom 3 dB).`;const button=document.createElement('button');button.textContent='APPLIQUER +'+b.recommendedGainDb+' dB';button.onclick=()=>{mix.monitorGainDb=levelRecommendation;edit();button.remove()};$('#levelAdvice').append(' ',button)}catch(e){notice(e.message)}};
 $('#networkTest').onclick=async()=>{const button=$('#networkTest');button.disabled=true;const results=[];let failed=0;$('#testResult').textContent='Mesure des allers-retours de commande pendant 5 secondes…';for(let i=0;i<10;i++){const start=performance.now();try{await api('health',{auth:false,timeout:1200});results.push(performance.now()-start)}catch{failed++}await new Promise(r=>setTimeout(r,Math.max(0,500-(performance.now()-start))))}const mean=results.reduce((a,b)=>a+b,0)/(results.length||1),jitter=results.length>1?Math.max(...results)-Math.min(...results):null;$('#testResult').textContent=`COMMAND RTT · ${direct?.ready?'LAN WebRTC':relayClient?'Internet relay':'LAN HTTP'} · ${results.length?mean.toFixed(1):'UNKNOWN'} ms moyen · variation ${fmt(jitter)} · échecs ${failed}/10. Audio loss ${fmt(telemetry.loss,' %')} · qualité ${quality()}. Bande passante non mesurée ; PCM stéréo : 1,536 Mbit/s utile/client.`;button.disabled=false};
 $('#calibrate').onclick=()=>{const l=profile?.levels;$('#testResult').textContent=`CALIBRATION · ${state?.device?.driver||'driver absent'} · ${state?.sampleRate||'—'} Hz · ${state?.buffer||'—'} samples · XRuns ${state?.xruns??'—'} · RMS ${fmt(l?.mixRmsDb,' dBFS')} · Peak ${fmt(l?.mixPeakDb,' dBFS')}. Parle à niveau normal dans CH1, règle le volume physique du téléphone à faible niveau puis utilise OPTIMIZE LEVEL dans MIX après au moins 3 s. Aucun signal de test n’est injecté.`};
 $('#exportLogs').onclick=async()=>{try{const b=await api('logs'),url=URL.createObjectURL(new Blob([JSON.stringify(b,null,2)],{type:'application/json'})),a=document.createElement('a');a.href=url;a.download='fosa-diagnostics.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}catch(e){notice(e.message)}};
 if(native){$$('.monitoring-engine').forEach(el=>{el.innerHTML='<option>PCM / UDP · Android native</option>';el.disabled=true});$('#engineInfo').textContent='PCM stéréo 48 kHz / 5 ms · UDP chiffré · AudioTrack Low Latency demandé · lecture gérée par le service Android.';$$('.microphone-input,#jitterTarget,#output,#refreshOutputs').forEach(el=>el.disabled=true);$('#jitterInfo').textContent='WebRTC non utilisé pour la lecture native. Le buffer PCM se règle ci-dessus.';$('#outputInfo').textContent='Sortie gérée par Android : brancher les écouteurs ou le DAC. La route réelle apparaît dans STATUS.';$('#backgroundInfo').textContent='Le service Android maintient la lecture écran verrouillé et en arrière-plan. MUTE / OPEN / STOP restent disponibles dans la notification.'}
}
async function microphoneInputs(){
 try{const devices=await navigator.mediaDevices?.enumerateDevices();const options='<option value="">Micro système</option>'+(regisseurCredentials?(state?.channels||[]).map((c,i)=>`<option value="mr18:${i}">MR18 CH${i+1} · ${esc(c.name)}</option>`).join(''):'')+(devices||[]).filter(d=>d.kind==='audioinput'&&d.deviceId).map((d,i)=>`<option value="${esc(d.deviceId)}">${esc(d.label||'Micro '+(i+1))}</option>`).join('');for(const el of $$('.microphone-input')){if(el.innerHTML!==options)el.innerHTML=options;el.value=micDevice;if(micDevice&&el.value!==micDevice){el.add(new Option('Micro choisi · reconnecter ou changer',micDevice));el.value=micDevice}}}catch{}
}
async function requestWake(){try{wake=await navigator.wakeLock.request('screen');$('#wake').classList.add('active');wake.addEventListener('release',()=>$('#wake').classList.remove('active'))}catch{notice('Écran actif non disponible sur ce lien. Garde FOSA visible et règle la veille de l’appareil si nécessaire.')}}
async function boot(){
 buildMixer();bind();syncMix();if(!native&&engine==='pcm'&&(!isSecureContext||!window.AudioWorkletNode)){engine='opus';notice('Ce lien propose l’écoute Stable. Scanne le QR sécurisé pour utiliser Low-Latency.')}if(!native)$$('.monitoring-engine').forEach(el=>{el.value=engine;el.querySelector('[value=pcm]').disabled=!isSecureContext||!window.AudioWorkletNode});$('#backFosa').hidden=!embedded;$('#profileName').value=get('fosa_name','')||getText('fosa_name');
 if(inviteValue('relay')){$$('#joinListen,#joinNetwork').forEach(b=>b.disabled=true);notice('Préparation de la connexion sécurisée…')}
 document.body.classList.toggle('musician',musician);if(query.has('bodypack'))$('#shareMode').value='native';
 if(inviteValue('role')&&[...$('#profileRole').options].some(o=>o.value===inviteValue('role')))$('#profileRole').value=inviteValue('role');
 if(inviteValue('code'))$('#joinCode').value=inviteValue('code');
 // The secure QR binds the bridge identity; no HTTPS page fetches a private HTTP URL.
 if(inviteValue('relay')){try{relayClient=await FosaRelay.create({relay:inviteValue('relay'),key:inviteValue('key'),epoch:inviteValue('epoch')});setServer('')}catch(e){notice('Connexion sécurisée : '+e.message);return}}
 else if(musician||query.has('console')||location.port==='8765'||['127.0.0.1','localhost'].includes(location.hostname)){server=location.origin;put('fosa_network_server',server)}
 $('#serverUrl').value=server;show(query.get('view')||'audio');paintStatus();renderDiagnostic();
 if(musician){$('#welcomeCard h1').textContent='Rejoindre FOSA';$('#serverAddressForm').hidden=true}
 try{await api('health',{auth:false,timeout:3000});if(query.has('console')&&['127.0.0.1','localhost'].includes(location.hostname)){
  const r=await fetch(server+'/api/local-console',{method:'POST',headers:{'X-FOSA-Console':'1'}});if(!r.ok)throw Error('Console locale indisponible');admin=(await r.json()).token;state=await api('state');$('#bufferSelect').value=String(state.buffer||state.requestedBuffer||256);paintStatus();await prepareRegisseur();await scanInterfaces();await updateSetup();if(query.has('regisseur')){show('live');notice('Régisseur PC prêt. Autorise les micros des récepteurs dans LIVE ; active ton micro pour parler.')}else notice('Console régisseur PC prête.');
 }}catch(e){notice('Bridge inaccessible : '+e.message)}
 if(token()&&!admin){try{const s=await api('state');state=s;profile=s.profile;if(profile){mix=normalize(profile.mix);changed=false;syncMix();show(query.get('view')||(native?'mix':'live'));if(get('fosa_network_resume:'+server,false)){desired=true;startAudio()}}}catch{}}
 $$('#joinListen,#joinNetwork').forEach(b=>b.disabled=false);
 setInterval(async()=>{await refresh();if(!native&&desired&&!connecting&&(!pc||['failed','disconnected','closed'].includes(pc.connectionState))&&Date.now()>=reconnectAt)startAudio()},1500);
 microphoneInputs();navigator.mediaDevices?.addEventListener?.('devicechange',microphoneInputs);
 setInterval(()=>{if(!document.body.classList.contains('performance'))pollMeters()},125);
 setInterval(()=>{stats();sendControl()},700);
 if(navigator.getBattery)navigator.getBattery().then(b=>{const paint=()=>$('#battery').textContent=Math.round(b.level*100)+' %'+(b.charging?' · charge':'');paint();b.addEventListener('levelchange',paint);b.addEventListener('chargingchange',paint)}).catch(()=>{});
}
boot();
})();
