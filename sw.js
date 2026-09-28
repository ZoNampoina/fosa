const CACHE='fosa-v0.8.0';
const CORE=['./','./index.html','./local.html','./manifest.webmanifest','./gate-processor.js','./v080.css','./v080.js','https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js','https://cdn.jsdelivr.net/npm/jsqr@1.4.0/dist/jsQR.min.js'];
const CDN='https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.117.2/+esm';
self.addEventListener('install',e=>e.waitUntil((async()=>{const c=await caches.open(CACHE);await c.addAll(CORE);try{await c.add(CDN)}catch{}await self.skipWaiting()})()));
self.addEventListener('activate',e=>e.waitUntil((async()=>{const ks=await caches.keys();await Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k)));await self.clients.claim()})()));
self.addEventListener('fetch',e=>{if(e.request.method!=='GET')return;const u=new URL(e.request.url);if(u.origin===location.origin){e.respondWith(fetch(e.request).then(r=>{const copy=r.clone();caches.open(CACHE).then(c=>c.put(e.request,copy));return r}).catch(()=>caches.match(e.request)));return}if(e.request.url===CDN){e.respondWith(caches.match(e.request).then(cached=>cached||fetch(e.request).then(r=>{const copy=r.clone();caches.open(CACHE).then(c=>c.put(e.request,copy));return r})));}});

self.addEventListener('notificationclick',e=>{e.notification.close();e.waitUntil((async()=>{const all=await clients.matchAll({type:'window',includeUncontrolled:true});for(const c of all){if('focus'in c)return c.focus()}if(clients.openWindow)return clients.openWindow('./')})())});
