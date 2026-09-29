(() => {
'use strict';
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const get = (k,d) => {try{return JSON.parse(localStorage.getItem(k))??d}catch{return d}};
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
let desired=false,connecting=false,connectingGeneration=0,reconnectAt=0,retry=0,changed=false,sending=false,polling=false,refreshId=0;
let localRevision=0, confirmedMixVersion=0;
let telemetry={rtt:null,jitter:null,loss:null,buffer:null},previousStats=null,wake=null,externalTalk=false,qrObjectUrl=null;
const audio=$('#monitorAudio'), query=new URLSearchParams(location.search);
const embedded=window.parent!==window;
const notice=t=>$('#notice').textContent=t;
function contextKey(){return 'fosa_network_mix:'+server+':'+(profile?.id||'draft')}
function token(){return admin||credentials[server]?.token||''}
function setServer(value){
 const u=new URL(value);if(!['http:','https:'].includes(u.protocol)||u.username||u.password)throw Error('Adresse HTTP(S) invalide');
 const next=u.origin;if(server!==next){stopAudio();state=null;profile=null;admin=''}server=next;put('fosa_network_server',server);$('#serverUrl').value=server;
}
async function api(path,{body,auth=true,timeout=6000}={}){
 if(!server)throw Error('Renseigne l’adresse du PC serveur.');
 const headers={};if(auth)headers.Authorization='Bearer '+token();if(body!==undefined)headers['Content-Type']='application/json';
 const r=await fetch(server+'/api/'+path,{method:body===undefined?'GET':'POST',headers,body:body===undefined?undefined:JSON.stringify(body),cache:'no-store',signal:AbortSignal.timeout(timeout)});
 const j=await r.json().catch(()=>({error:'Réponse du serveur illisible'}));if(!r.ok)throw Error(j.error||'Erreur serveur '+r.status);return j;
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
 $('#streamStatus').textContent=pc?.connectionState==='connected'?(state?.connected?'AUDIO CONNECTÉ':'AUDIO CONNECTÉ · SOURCE ABSENTE'):desired?'RECONNEXION…':'AUDIO ARRÊTÉ';
 $('#netType').textContent=networkType();$('#liveQuality').textContent=quality();$('#liveQuality').style.color=['CRITIQUE','INSTABLE'].includes(quality())?'var(--amber)':'';
 $('#liveLatency').textContent='Latence audio : non mesurée · RTT '+fmt(telemetry.rtt);
 $('#captureSummary').textContent=state?.connected?`${state.device?.name} · ${state.device?.driver} · ${state.sampleRate} Hz · ${state.buffer} échantillons · ${state.inputs} entrées`:(state?.error||'NON CONNECTÉ · aucune entrée détectée');
 $('#scanInterfaces').disabled=!admin;$('#startCapture').disabled=!admin;$('#interfaceSelect').disabled=!admin;
 $$('#master,#liveMaster,#muteAll,#liveMute,#ducking,#applyPreset,#restoreMix,#resetMix').forEach(el=>el.disabled=profile?.locked===true);$('#mixScope').textContent=profile?`${profile.name} · ${profile.role}${profile.locked?' · VERROUILLÉ':''}`:'Réglages locaux';
 $('#liveTalkNote').textContent=profile?.talkAllowed?'Micro LAN '+(micEnabled?'actif':'à activer dans TALKBACK'):'Autorisation talkback requise dans MATRIX.';
 $('#enableMic').textContent=micEnabled?'COUPER MON MICRO':'ACTIVER MON MICRO';
 $('#mixNotice').textContent=changed?'Modifications locales en attente du serveur.':profile?'Mix enregistré sur cet appareil et sur le serveur LAN.':'Réglages locaux : rejoins le serveur pour les appliquer au son.';
}
function buildMixer(){
 $('#mixer').innerHTML=Array.from({length:18},(_,i)=>`<article class="channel" data-channel="${i}"><span class="num">${String(i+1).padStart(2,'0')}</span><strong data-channel-name>CH ${String(i+1).padStart(2,'0')}</strong><output data-volume>50 %</output><div class="fader"><button data-step="-5" data-i="${i}" aria-label="Diminuer canal ${i+1}">−</button><input data-gain="${i}" type="range" min="0" max="100" value="50" aria-label="Volume canal ${i+1}"><button data-step="5" data-i="${i}" aria-label="Augmenter canal ${i+1}">+</button></div><div class="ch-actions"><button data-mute="${i}" aria-label="Mute canal ${i+1}" aria-pressed="false">M</button><button data-solo="${i}" aria-label="Solo temporaire canal ${i+1}" aria-pressed="false">S</button><label class="pan">PAN<input data-pan="${i}" type="range" min="-100" max="100" value="0" aria-label="Panoramique canal ${i+1}"></label></div><div class="meter"><i></i></div><span class="reading">NON CONNECTÉ</span></article>`).join('');
 $('#channelNames').innerHTML=Array.from({length:18},(_,i)=>`<div class="channel-name"><span>CH ${String(i+1).padStart(2,'0')}</span><input data-name="${i}" value="CH ${String(i+1).padStart(2,'0')}" maxlength="60" aria-label="Nom canal ${i+1}" disabled><input data-role="${i}" placeholder="Type : basse, chant…" maxlength="60" aria-label="Type canal ${i+1}" disabled><button data-rename="${i}" disabled>OK</button></div>`).join('');
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
}
let persistTimer;
function edit(){localRevision++;changed=true;syncMix();const durable=normalize(mix);put(contextKey(),durable);if(!profile)put('fosa_network_draft',durable);clearTimeout(persistTimer);persistTimer=setTimeout(flush,120);paintStatus()}
async function flush(){
 if(sending||!changed||!profile||!state||admin)return;sending=true;const sent=JSON.stringify(mix);
 try{const ack=await api('mix',{body:{mix}});confirmedMixVersion=ack.mixVersion||0;if(JSON.stringify(mix)===sent)changed=false}catch(e){notice(e.message)}finally{sending=false;paintStatus();if(changed&&JSON.stringify(mix)!==sent)flush()}
}
async function refresh(){
 if(!token()||polling)return;polling=true;
 try{const revision=localRevision,dirtyAtStart=changed||sending;const next=await api('state');state=next;profile=next.profile;
  if(profile){if(!dirtyAtStart&&!changed&&!sending&&revision===localRevision&&(profile.mixVersion||0)>=confirmedMixVersion&&JSON.stringify(profile.mix)!==JSON.stringify(mix)){mix=profile.mix;syncMix()}if(changed&&!profile.locked)await flush()}
  paintStatus();updateMeters();if(page==='diagnostic')renderDiagnostic();if(page==='devices')renderDevices();
  if(profile?.locked){mix=profile.mix;changed=false;syncMix()}if(page==='matrix'&&document.activeElement?.closest('#matrix')==null)renderMatrix();if(page==='talkback')renderTalkTargets();
 }catch(e){state=null;paintStatus();updateMeters();if(page==='diagnostic')renderDiagnostic();if(desired)notice('Serveur momentanément inaccessible. Le dernier mix est conservé.');}
 finally{polling=false}
}
async function detect(){
 try{setServer($('#serverUrl').value.trim()||server||'http://127.0.0.1:8765');const h=await api('health',{auth:false,timeout:2500});if(h.service!=='fosa-audio-bridge'||h.protocol!==1)throw Error('Ce serveur n’est pas un bridge FOSA compatible.');$('#detectInfo').textContent='Bridge détecté · '+h.version;notice('Bridge trouvé. Rejoins avec le code affiché sur le PC.');await refresh()}
 catch(e){$('#detectInfo').textContent='NON CONNECTÉ';notice('Bridge introuvable. Vérifie le lancement sur le PC, l’adresse et le certificat HTTPS. '+e.message)}
}
async function join(){
 try{setServer($('#serverUrl').value.trim()||server);admin='';const expected=query.get('session');const b=await api('join',{auth:false,body:{code:$('#joinCode').value.trim().toUpperCase(),name:$('#profileName').value.trim()||'Musicien',role:$('#profileRole').value}});if(expected&&b.session!==expected)throw Error('Ce serveur ne correspond pas à la session du lien.');credentials[server]={token:b.token,id:b.profile.id};put('fosa_network_credentials',credentials);profile=b.profile;mix=normalize(get(contextKey(),mix));changed=true;await refresh();syncMix();show('mix');notice('Profil connecté. Appuie sur ÉCOUTER pour lancer le son.')}
 catch(e){notice(e.message)}
}
async function adminLogin(){try{setServer($('#serverUrl').value.trim()||server);admin=$('#adminKey').value.trim();const s=await api('state');if(!s.admin)throw Error('Clé régisseur incorrecte');stopAudio();state=s;profile=null;$('#adminKey').value='';paintStatus();await scanInterfaces();notice('Console régisseur ouverte. Les permissions sont vérifiées par le bridge.')}catch(e){admin='';notice(e.message)}}
async function scanInterfaces(){try{const b=await api('devices');$('#interfaceSelect').innerHTML=b.devices.length?b.devices.map(d=>`<option value="${d.id}" ${d.inputs<18?'disabled':''}>${esc(d.name)} · ${esc(d.driver)} · ${d.inputs} IN</option>`).join(''):'<option>Aucune interface audio détectée</option>';const preferred=b.devices.find(d=>d.mr18&&d.asio)||b.devices.find(d=>d.mr18)||b.devices.find(d=>d.inputs>=18);if(preferred)$('#interfaceSelect').value=preferred.id;}catch(e){notice(e.message)}}
async function waitIce(connection){if(connection.iceGatheringState==='complete')return;await new Promise((resolve,reject)=>{const timer=setTimeout(()=>{connection.removeEventListener('icegatheringstatechange',done);reject(Error('Délai de collecte réseau dépassé'))},8000);function done(){if(connection.iceGatheringState==='complete'){clearTimeout(timer);connection.removeEventListener('icegatheringstatechange',done);resolve()}}connection.addEventListener('icegatheringstatechange',done)})}
function tuneStereo(sdp){return sdp.replace(/a=fmtp:(\d+) ([^\r\n]+)/g,(line,pt,params)=>new RegExp('a=rtpmap:'+pt+' opus/','i').test(sdp)?`a=fmtp:${pt} ${params};stereo=1;sprop-stereo=1;maxaveragebitrate=128000`:line)}
async function startAudio(){
 if(connecting)return;if(!profile||admin){notice('Rejoins le bridge avec un profil musicien.');show('audio');return}if(pc?.connectionState==='connected'){await audio.play().catch(()=>notice('Appuie à nouveau sur ÉCOUTER.'));return}
 desired=true;put('fosa_network_resume:'+server,true);connecting=true;const generation=++connectingGeneration;
 try{pc?.close();previousStats=null;const next=new RTCPeerConnection({iceServers:[]});pc=next;transceiver=next.addTransceiver('audio',{direction:'sendrecv'});if(micEnabled&&mic)await transceiver.sender.replaceTrack(mic.getAudioTracks()[0]);
 const opus=RTCRtpReceiver.getCapabilities?.('audio')?.codecs.filter(c=>c.mimeType.toLowerCase()==='audio/opus');if(opus?.length)transceiver.setCodecPreferences(opus);
 next.ontrack=e=>{if(pc!==next)return;audio.srcObject=e.streams[0]||new MediaStream([e.track]);applyJitter();audio.play().catch(()=>notice('Audio prêt : touche ÉCOUTER pour autoriser la lecture.'));};
 next.onconnectionstatechange=()=>{if(pc!==next)return;paintStatus();if(next.connectionState==='connected'){retry=0;notice(state?.connected?'Écoute connectée.':'Liaison prête, en attente de la MR18.')}if(['failed','disconnected'].includes(next.connectionState)){reconnectAt=Date.now()+Math.min(8000,1000*2**Math.min(retry++,3))}};
 const offer=await next.createOffer();offer.sdp=tuneStereo(offer.sdp);await next.setLocalDescription(offer);await waitIce(next);
 const answer=await api('offer',{body:{type:'offer',sdp:next.localDescription.sdp,device:navigator.userAgent.slice(0,100)},timeout:15000});
 if(generation!==connectingGeneration||!desired){next.close();return}await next.setRemoteDescription(answer);setupMediaSession();
 }catch(e){notice('Audio non connecté : '+e.message);pc?.close();pc=null;reconnectAt=Date.now()+4000}finally{connecting=false;paintStatus()}
}
function stopAudio(){desired=false;connectingGeneration++;put('fosa_network_resume:'+server,false);stopTalk();pc?.close();pc=null;audio.srcObject=null;telemetry={rtt:null,jitter:null,loss:null,buffer:null};mic?.getTracks().forEach(t=>t.stop());mic=null;micEnabled=false;api('disconnect',{body:{}}).catch(()=>{});paintStatus()}
function applyJitter(){for(const r of pc?.getReceivers()||[])if('jitterBufferTarget'in r)try{r.jitterBufferTarget=Number($('#jitterTarget').value)}catch{}}
async function enableMic(){
 if(micEnabled){stopTalk();mic?.getTracks().forEach(t=>t.stop());mic=null;micEnabled=false;await transceiver?.sender.replaceTrack(null);paintStatus();return}
 if(!profile?.talkAllowed){notice('Le régisseur doit autoriser ton micro dans MATRIX.');return}if(!navigator.mediaDevices?.getUserMedia){notice('Micro indisponible : utilise une adresse HTTPS avec un certificat reconnu.');return}
 try{if(!desired)await startAudio();if(!transceiver)throw Error('Connecte l’audio d’abord');mic=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:false,channelCount:1}});mic.getTracks().forEach(t=>t.enabled=false);await transceiver.sender.replaceTrack(mic.getAudioTracks()[0]);micEnabled=true;paintStatus();notice('Micro prêt. Maintiens TALK pour parler.')}catch(e){notice('Micro : '+e.message)}
}
function startTalk(e){if(!micEnabled||!profile?.talkAllowed){notice('Active d’abord le micro autorisé dans TALKBACK.');return}e?.preventDefault();e?.currentTarget?.setPointerCapture?.(e.pointerId);talking=true;mic?.getTracks().forEach(t=>t.enabled=true);$$('.talk').forEach(b=>b.classList.add('talking'));sendControl()}
function stopTalk(){if(!talking)return;talking=false;mic?.getTracks().forEach(t=>t.enabled=false);$$('.talk').forEach(b=>b.classList.remove('talking'));sendControl()}
function sendControl(){if(!desired||!profile||!state)return;api('control',{body:{talk:talking,target:$('#talkTarget').value,metrics:{...telemetry,network:networkType(),audioLatency:null}},timeout:2000}).catch(()=>{})}
async function stats(){if(!pc||pc.connectionState!=='connected')return;try{const rows=await pc.getStats();let pair,rtp;rows.forEach(r=>{if(r.type==='transport'&&r.selectedCandidatePairId)pair=rows.get(r.selectedCandidatePairId);if(r.type==='inbound-rtp'&&r.kind==='audio')rtp=r});if(pair)telemetry.rtt=Number.isFinite(pair.currentRoundTripTime)?pair.currentRoundTripTime*1000:null;if(rtp){telemetry.jitter=Number.isFinite(rtp.jitter)?rtp.jitter*1000:null;if(previousStats&&previousStats.id===rtp.id){const lost=Math.max(0,(rtp.packetsLost||0)-previousStats.lost),received=Math.max(0,(rtp.packetsReceived||0)-previousStats.received),n=(rtp.jitterBufferEmittedCount||0)-previousStats.emitted;telemetry.loss=lost+received>0?lost/(lost+received)*100:null;telemetry.buffer=n>0?((rtp.jitterBufferDelay||0)-previousStats.delay)/n*1000:null}previousStats={id:rtp.id,lost:rtp.packetsLost||0,received:rtp.packetsReceived||0,emitted:rtp.jitterBufferEmittedCount||0,delay:rtp.jitterBufferDelay||0}}paintStatus();if(page==='diagnostic')renderDiagnostic()}catch{}}
function renderDiagnostic(){
 const s=state,d=s?.device;const metrics=[['MR18 / interface',s?.connected?'Connectée':'NON CONNECTÉE'],['Driver',d?.driver||'—'],['Fréquence',s?.sampleRate?s.sampleRate+' Hz':'—'],['Buffer réel',s?.buffer?s.buffer+' éch.':'—'],['Entrées',s?.inputs??'—'],['Canaux avec signal',s?s.channels.filter(c=>c.signal).length:'—'],['CPU bridge',fmt(s?.cpu,' %')],['RAM bridge',fmt(s?.ramMB,' Mo')],['Réseau',networkType()],['RTT WebRTC',fmt(telemetry.rtt)],['Jitter',fmt(telemetry.jitter)],['Pertes / intervalle',fmt(telemetry.loss,' %')],['Buffer réception',fmt(telemetry.buffer)],['Latence driver',fmt(s?.captureLatencyMs)],['Paquet Opus',s?s.opusFrameMs+' ms':'—'],['XRUN / pertes capture',s?`${s.xruns} / ${s.captureDrops}`:'—'],['Qualité réseau',quality()],['Latence audio bout-en-bout','NON MESURÉE']];$('#diagnostics').innerHTML=metrics.map(([k,v])=>`<div class="metric"><span>${esc(k)}</span><b>${esc(v)}</b></div>`).join('');
}
function renderTalkTargets(){const el=$('#talkTarget'),selected=el.value;const roles=['Batteur','Bassiste','Guitariste','Clavier','Chant','Chef','Régisseur'];if(el.options.length===1){for(const role of roles){const o=document.createElement('option');o.value='role:'+role;o.textContent=role;el.appendChild(o)}}el.value=selected}
function renderDevices(){const users=state?.users;if(!users){$('#devices').textContent='Console régisseur requise.';return}$('#devices').innerHTML=users.length?`<table><thead><tr>${['Utilisateur','Appareil','Mix','Talkback','Connexion','RTT','Latence audio'].map(x=>`<th>${x}</th>`).join('')}</tr></thead><tbody>${users.map(p=>`<tr><td>${esc(p.name)}</td><td title="${esc(p.device)}">${/Android/i.test(p.device)?'Android':/iPhone|iPad/i.test(p.device)?'iOS':p.connected?'PC / navigateur':'—'}</td><td>${esc(p.role)}</td><td>${p.talkAllowed?'Micro autorisé':'Écoute'}${p.talkListen?'':' OFF'}</td><td>${p.connected?esc(p.network):'Hors ligne'}</td><td>${fmt(p.metrics?.rtt)}</td><td>Non mesurée</td></tr>`).join('')}</tbody></table>`:'Aucun profil musicien. Rejoins le bridge sur un autre appareil.'}
function renderMatrix(){const users=state?.users;if(!admin||!users){$('#matrix').textContent='';return}$('#matrixHint').textContent='Assignations et permissions appliquées par le serveur. Mute et niveaux sont propres à chaque musicien.';if(!users.length){$('#matrix').textContent='Aucun profil musicien connecté.';return}
 $('#matrix').innerHTML=`<table><thead><tr><th>Entrée</th>${users.map(p=>`<th>${esc(p.name)}<br><label><input type="checkbox" data-permission="locked" data-user="${p.id}" ${p.locked?'checked':''}>Verrouiller</label><label><input type="checkbox" data-permission="talkAllowed" data-user="${p.id}" ${p.talkAllowed?'checked':''}>Micro</label><label><input type="checkbox" data-permission="talkListen" data-user="${p.id}" ${p.talkListen?'checked':''}>Écoute TB</label><button data-copy="${p.id}">COPIER MON MIX</button></th>`).join('')}</tr></thead><tbody>${(state.channels||[]).map((c,i)=>`<tr><th>${String(i+1).padStart(2,'0')} · ${esc(c.name)}</th>${users.map(p=>`<td><div class="matrix-cell"><input type="checkbox" data-assign="${i}" data-user="${p.id}" ${p.allowed[i]?'checked':''} aria-label="Assigner ${esc(c.name)} à ${esc(p.name)}"><input type="range" min="0" max="100" value="${Math.round(p.mix.channels[i].gain*100)}" data-matrix-gain="${i}" data-user="${p.id}" aria-label="Niveau ${esc(c.name)} pour ${esc(p.name)}"><button data-matrix-mute="${i}" data-user="${p.id}" class="${p.mix.channels[i].mute?'active':''}">M</button></div></td>`).join('')}</tr>`).join('')}</tbody></table>`;
}
function applyPreset(){if(profile?.locked){notice('Mix verrouillé.');return}const role=$('#preset').value;const preferences={Batteur:['kick','snare','batterie'],Bassiste:['bass','basse','kick'],Guitariste:['guit','chant','vocal'],Clavier:['piano','key','clavier','chant'],Chant:['chant','vocal','piano'],Chef:[],Régisseur:[],Personnalisé:[]}[role]||[];mix.channels.forEach((c,i)=>{const meta=state?.channels?.[i];const text=(meta?.name+' '+meta?.role).toLowerCase();c.gain=preferences.some(x=>text.includes(x))?.8:.4;c.mute=false;c.solo=false;c.pan=0});edit();notice('Preset appliqué selon les noms/types des canaux. Ajuste ton écoute.')}
async function outputs(){try{const devices=await navigator.mediaDevices?.enumerateDevices();$('#output').innerHTML='<option value="">Sortie système</option>'+(devices||[]).filter(d=>d.kind==='audiooutput').map((d,i)=>`<option value="${esc(d.deviceId)}">${esc(d.label||'Sortie '+(i+1))}</option>`).join('');$('#outputInfo').textContent=audio.setSinkId?'Sélectionne une sortie exposée par ton navigateur.':'Choisis la sortie dans les réglages du téléphone ; ce navigateur ne permet pas de la changer.';$('#output').disabled=!audio.setSinkId}catch(e){notice(e.message)}}
function setupMediaSession(){if(!('mediaSession'in navigator))return;try{navigator.mediaSession.metadata=new MediaMetadata({title:'FOSA · Mon mix',artist:profile?.name||'Audio Network'});navigator.mediaSession.setActionHandler('pause',()=>{mix.muteAll=true;edit()});navigator.mediaSession.setActionHandler('play',()=>{mix.muteAll=false;edit();audio.play()})}catch{}}
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
 $('#detect').onclick=detect;$('#joinNetwork').onclick=join;$('#adminLogin').onclick=adminLogin;$('#scanInterfaces').onclick=scanInterfaces;
 $('#startCapture').onclick=async()=>{try{const s=await api('configure',{body:{device:Number($('#interfaceSelect').value),buffer:Number($('#bufferSelect').value)},timeout:12000});state=s;notice('Interface ouverte. Attente des premiers échantillons…');await refresh()}catch(e){notice(e.message)}};
 $('#startAudio').onclick=startAudio;$('#stopAudio').onclick=stopAudio;$('#muteAll').onclick=()=>{if(profile?.locked)return;mix.muteAll=!mix.muteAll;edit()};
 $('#saveMix').onclick=()=>{put(contextKey()+':saved',normalize(mix));notice('Mix sauvegardé sur cet appareil.')};$('#restoreMix').onclick=()=>{const saved=get(contextKey()+':saved',null);if(!saved){notice('Aucun mix sauvegardé pour ce profil.');return}if(profile?.locked)return;mix=normalize(saved);edit();notice('Mix restauré.')};$('#resetMix').onclick=()=>{if(profile?.locked)return;mix=blank();edit()};$('#applyPreset').onclick=applyPreset;
 $('#ducking').onchange=()=>{if(profile?.locked)return;mix.ducking=Number($('#ducking').value);edit()};$('#enableMic').onclick=enableMic;
 $$('.talk').forEach(b=>{b.onpointerdown=startTalk;b.onpointerup=stopTalk;b.onpointercancel=stopTalk;b.onlostpointercapture=stopTalk});addEventListener('blur',stopTalk);document.addEventListener('visibilitychange',()=>{if(document.hidden){stopTalk();if(mix.channels.some(c=>c.solo)){mix.channels.forEach(c=>c.solo=false);edit()}}else{if(wake)requestWake();refresh()}});
 $('#jitterTarget').onchange=applyJitter;$('#refreshOutputs').onclick=outputs;$('#output').onchange=async()=>{try{await audio.setSinkId($('#output').value);$('#outputInfo').textContent='Sortie sélectionnée : '+$('#output').selectedOptions[0].textContent}catch(e){notice('Sortie non modifiée : '+e.message)}};
 $('#wake').onclick=async()=>{if(wake){await wake.release();wake=null;$('#wake').classList.remove('active')}else requestWake()};
 $('#testLatency').onclick=async()=>{try{const start=performance.now();await api('health',{auth:false});$('#diagnosticNote').textContent=`Aller-retour HTTP : ${(performance.now()-start).toFixed(1)} ms. RTT WebRTC : ${fmt(telemetry.rtt)}. La latence audio bout-en-bout reste non mesurée : suis le test physique ci-dessous.`}catch(e){notice(e.message)}};
 $('#optimize').onclick=()=>{const stable=state?.xruns>0||telemetry.loss>1||telemetry.jitter>15;$('#jitterTarget').value=stable?'40':'20';applyJitter();$('#diagnosticNote').textContent=stable?'Cible réception portée à 40 ms. Après arrêt des écoutes, essaie un buffer ASIO 512 si des craquements persistent.':'Cible réception 20 ms. Garde le buffer ASIO 256 pour les premiers essais ; diminue-le seulement après vérification sans craquements.'};
 $('#makeQr').onclick=async()=>{if(!admin){notice('Console régisseur requise pour générer le QR local.');return}try{const url=new URL(server+'/network.html');url.searchParams.set('session',state.session);url.searchParams.set('role',$('#qrRole').value);$('#joinLink').value=url.href;const r=await fetch(server+'/api/qr?role='+encodeURIComponent($('#qrRole').value),{headers:{Authorization:'Bearer '+token()}});if(!r.ok)throw Error('QR indisponible');if(qrObjectUrl)URL.revokeObjectURL(qrObjectUrl);qrObjectUrl=URL.createObjectURL(await r.blob());$('#qrBox').innerHTML='<img alt="QR de connexion FOSA">';$('#qrBox img').src=qrObjectUrl}catch(e){notice(e.message)}};
 $('#copyLink').onclick=async()=>{try{if(!$('#joinLink').value)throw Error('Génère d’abord le lien.');await navigator.clipboard.writeText($('#joinLink').value);notice('Lien copié.')}catch(e){$('#joinLink').select();notice('Sélectionne et copie le lien. '+e.message)}};
 $('#forgetSession').onclick=()=>{stopAudio();delete credentials[server];put('fosa_network_credentials',credentials);admin='';profile=null;state=null;show('audio');paintStatus();notice('Profil déconnecté sur cet appareil.')};
 $('#backFosa').onclick=()=>window.parent.postMessage({type:'fosa-network-close'},location.origin);
 addEventListener('message',e=>{if(e.source!==window.parent||e.origin!==location.origin)return;if(e.data?.type==='fosa-network-view')show(e.data.view);if(e.data?.type==='fosa-talkback-activity'){externalTalk=e.data.active===true;audio.volume=externalTalk?(mix.ducking===-99?0:10**(mix.ducking/20)):1}});
 addEventListener('keydown',e=>{if(e.key==='Escape'&&embedded)window.parent.postMessage({type:'fosa-network-close'},location.origin)});addEventListener('beforeunload',e=>{if(desired){e.preventDefault();e.returnValue=''}});
}
async function requestWake(){try{wake=await navigator.wakeLock.request('screen');$('#wake').classList.add('active');wake.addEventListener('release',()=>$('#wake').classList.remove('active'))}catch{notice('Écran actif non disponible dans ce navigateur.')}}
async function boot(){
 buildMixer();bind();syncMix();$('#backFosa').hidden=!embedded;$('#profileName').value=get('fosa_name','')||localStorage.fosa_name||'';
 if(query.has('role')&&[...$('#profileRole').options].some(o=>o.value===query.get('role')))$('#profileRole').value=query.get('role');
 // LAN page identifies its own server. Cloud app requires an explicit local URL.
 if(location.port==='8765'||['127.0.0.1','localhost'].includes(location.hostname)){server=location.origin;put('fosa_network_server',server)}
 $('#serverUrl').value=server;show(query.get('view')||'audio');paintStatus();renderDiagnostic();
 if(token()){try{const s=await api('state');state=s;profile=s.profile;if(profile){mix=normalize(get(contextKey(),profile.mix));changed=true;syncMix();await flush();show(query.get('view')||'mix');if(get('fosa_network_resume:'+server,false)){desired=true;startAudio()}}}catch{}}
 setInterval(async()=>{await refresh();if(desired&&!connecting&&(!pc||['failed','disconnected','closed'].includes(pc.connectionState))&&Date.now()>=reconnectAt)startAudio()},1500);
 setInterval(()=>{stats();sendControl()},700);
 if(navigator.getBattery)navigator.getBattery().then(b=>{const paint=()=>$('#battery').textContent=Math.round(b.level*100)+' %'+(b.charging?' · charge':'');paint();b.addEventListener('levelchange',paint);b.addEventListener('chargingchange',paint)}).catch(()=>{});
}
boot();
})();
