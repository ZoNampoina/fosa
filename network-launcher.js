(() => {
'use strict';
let frame, panel, returnFocus, active=false;
function open(view='audio'){
 returnFocus=document.activeElement;
 if(!frame){
  panel=document.createElement('div');panel.id='fosaNetworkPanel';panel.setAttribute('role','dialog');panel.setAttribute('aria-label','FOSA Audio Network');panel.setAttribute('aria-modal','true');
  Object.assign(panel.style,{position:'fixed',inset:'0',zIndex:'1000',background:'#07090b'});
  frame=document.createElement('iframe');frame.title='FOSA Audio Network';frame.src='./network.html?view='+encodeURIComponent(view);frame.allow='microphone; autoplay; screen-wake-lock';
  Object.assign(frame.style,{border:'0',width:'100%',height:'100%'});panel.appendChild(frame);document.body.appendChild(panel);
 }else{panel.hidden=false;frame.contentWindow.postMessage({type:'fosa-network-view',view},location.origin)}
 document.body.style.overflow='hidden';frame.focus();
}
function close(){if(panel)panel.hidden=true;document.body.style.overflow='';returnFocus?.focus()}
const nav=document.querySelector('#postNav');if(nav){const b=document.createElement('button');b.textContent='AUDIO';b.onclick=()=>open();nav.appendChild(b);nav.style.gridTemplateColumns='1fr 1fr 1fr'}
const entry=document.createElement('section');entry.className='card setupOnly';entry.innerHTML='<h2>FOSA Audio Network <span class="ver">EXPÉRIMENTAL</span></h2><p class="small">MR18 USB · 18 entrées · mix personnel sur réseau local</p><button type="button">OUVRIR AUDIO NETWORK</button>';entry.querySelector('button').onclick=()=>open();document.querySelector('header').after(entry);
const menu=document.createElement('section');menu.className='card menuPage';menu.innerHTML='<h2>Audio Network</h2><div class="row"></div>';for(const [view,label]of [['live','LIVE AUDIO'],['audio','AUDIO'],['mix','MON MIX'],['talkback','TALKBACK LAN'],['matrix','MATRIX'],['devices','APPAREILS'],['diagnostic','DIAGNOSTIC'],['settings','PARAMÈTRES']]){const b=document.createElement('button');b.textContent=label;b.onclick=()=>open(view);menu.querySelector('.row').appendChild(b)}document.querySelector('.app').appendChild(menu);
addEventListener('message',e=>{if(e.origin!==location.origin||e.source!==frame?.contentWindow)return;if(e.data?.type==='fosa-network-close')close();if(e.data?.type==='fosa-network-state')active=e.data.active===true});
addEventListener('fosa-talkback-audible',e=>frame?.contentWindow.postMessage({type:'fosa-talkback-activity',active:e.detail.active},location.origin));
addEventListener('beforeunload',e=>{if(active){e.preventDefault();e.returnValue=''}});
addEventListener('keydown',e=>{if(e.key==='Escape'&&panel&&!panel.hidden){e.preventDefault();close()}});
})();
