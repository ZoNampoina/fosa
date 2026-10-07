package com.arizona.fosa.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.*

/** Owns discovery/network selection, not microphone or session membership.
 * Blocking entry points run on MobileService's worker, never the UI thread. */
class FosaConnectionManager(private val ctx:Context,private val changed:(ConnectionPhase,TransportKind,String)->Unit):AutoCloseable {
    val lan=LanTransport(ctx)
    val direct=AndroidWifiDirectTransport(ctx)
    val hotspot=LocalHotspot(ctx)
    private val sessions=ConcurrentHashMap<String,LocalSession>()
    private val queue=LinkedBlockingQueue<LocalSession>()
    @Volatile var transport=TransportKind.LAN;private set
    @Volatile private var closed=false
    private var listener:((List<LocalSession>)->Unit)?=null
    fun phase(p:ConnectionPhase,message:String=""){if(!closed)changed(p,transport,message)}
    fun nearby(found:(List<LocalSession>)->Unit){listener=found;sessions.clear();scanLan();android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({if(!closed)direct.discover(::record){ /* LAN discovery remains usable after P2P failure. */ }},2500)}
    private fun record(s:LocalSession){if(closed||s.id.isBlank())return;val key=s.id+":"+s.kind;sessions[key]=s;queue.offer(s);listener?.invoke(sessions.values.filter{!it.device}.sortedWith(compareBy({it.kind.ordinal},{it.name})))}
    private fun scanLan(){lan.discover(::record){}}
    fun announce(q:JSONObject){lan.announce(q);direct.announce(q)}
    fun hostNetwork():String {
        LanAddress.ip(ctx)?.let{if(transport!=TransportKind.HOTSPOT)transport=TransportKind.LAN;return it}
        transport=TransportKind.WIFI_DIRECT;phase(ConnectionPhase.CONNECTING_NETWORK)
        awaitNetwork{ok,fail->direct.createGroup(ok,fail)}
        return LanAddress.ip(ctx) ?: throw IllegalArgumentException("Le groupe Wi-Fi Direct ne fournit pas d’adresse locale")
    }
    private fun awaitNetwork(start:((String)->Unit,(String)->Unit)->Unit):String {
        val done=CountDownLatch(1);var address="";var error=""
        start({address=it;done.countDown()},{error=it;done.countDown()})
        require(done.await(20,TimeUnit.SECONDS)){"Wi-Fi Direct : délai de connexion dépassé"}
        require(error.isBlank()){error};return address
    }
    fun resolve(code:String,preferred:String="",sessionId:String=""):LocalSession {
        require(code.matches(Regex("[0-9]{6}"))){"Code session à 6 chiffres requis"}
        transport=TransportKind.LAN
        phase(ConnectionPhase.DISCOVERING)
        if(preferred.isNotBlank())probe(preferred,code,sessionId)?.let{return it}
        LanAddress.gateway(ctx)?.let{probe("http://$it:48765",code,sessionId)?.let{return it}}
        scanLan()
        val deadline=System.currentTimeMillis()+4000
        val tried=mutableSetOf<String>()
        while(!closed&&System.currentTimeMillis()<deadline){val s=queue.poll(300,TimeUnit.MILLISECONDS) ?: continue
            if(s.device||s.kind!=TransportKind.LAN||s.address.isBlank()||!tried.add(s.address)||s.code.isNotBlank()&&s.code!=code)continue
            probe(s.address,code,sessionId)?.let{phase(ConnectionPhase.FOUND);return it}
        }
        transport=TransportKind.WIFI_DIRECT;phase(ConnectionPhase.DISCOVERING)
        var p2pError="";direct.discover(::record){p2pError=it}
        val p2pDeadline=System.currentTimeMillis()+9000
        while(!closed&&System.currentTimeMillis()<p2pDeadline){val s=queue.poll(300,TimeUnit.MILLISECONDS) ?: continue
            if(s.device||s.kind!=TransportKind.WIFI_DIRECT||s.code!=code||sessionId.isNotBlank()&&s.id!=sessionId)continue
            phase(ConnectionPhase.FOUND);phase(ConnectionPhase.CONNECTING_NETWORK)
            val address=awaitNetwork{ok,fail->direct.connect(s,ok,fail)}
            bind(address)
            // The app host normally owns its autonomous group. Never confuse a
            // different group owner with the Session coordinator: verify ID.
            probe(address,code,s.id)?.let{return it.copy(kind=TransportKind.WIFI_DIRECT)}
            scanLan();val end=System.currentTimeMillis()+5000
            while(System.currentTimeMillis()<end){val local=queue.poll(300,TimeUnit.MILLISECONDS) ?: continue
                if(local.address.isNotBlank())probe(local.address,code,s.id)?.let{return it.copy(kind=TransportKind.WIFI_DIRECT)}
            }
            throw IllegalArgumentException("Groupe Wi-Fi Direct créé, mais hôte FOSA introuvable")
        }
        transport=TransportKind.HOTSPOT;phase(ConnectionPhase.FAILED)
        throw IllegalArgumentException("Session non trouvée. Vérifie le code et le Wi-Fi. ${p2pError.ifBlank{"L’hôte peut créer un réseau FOSA local."}}")
    }
    private fun probe(address:String,code:String,id:String):LocalSession?=try {
        val q=request(address,"info",JSONObject())
        if(id.isNotBlank()&&q.optString("session")!=id||q.optString("code").isNotBlank()&&q.optString("code")!=code)null
        else LocalSession.from(q,address,transport)
    }catch(_:Exception){null}
    fun join(found:LocalSession,body:JSONObject):JSONObject {
        transport=found.kind;phase(ConnectionPhase.AUTHENTICATING)
        val info=request(found.address,"info",JSONObject())
        val q=JSONObject(body.toString()).put("session",found.id)
        if(info.optInt("protocolVersion",1)>=2){val nonce=request(found.address,"challenge",JSONObject()).getString("nonce");q.put("nonce",nonce).put("protocolVersion",2)}
        val ticket=request(found.address,"join",q);phase(ConnectionPhase.SIGNALING);return ticket
    }
    fun select(kind:TransportKind){transport=kind}
    fun bind(address:String) {val host=Uri.parse(address).host.orEmpty();ctx.getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(LanAddress.forHost(ctx,host))}
    fun request(address:String,path:String,body:JSONObject,token:String=""):JSONObject {
        val u=Uri.parse(address)
        require(u.scheme=="http"&&LanAddress.privateV4(u.host.orEmpty())&&u.port in 1..65535&&u.userInfo==null){"Adresse locale invalide"}
        val url=URL(address+if(path.startsWith("/pair/"))path else "/lan/$path")
        val c=(LanAddress.forHost(ctx,u.host.orEmpty())?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        c.connectTimeout=1100;c.readTimeout=2500;c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json")
        if(token.isNotBlank())c.setRequestProperty("Authorization","Bearer $token")
        try{c.outputStream.use{it.write(body.toString().toByteArray())};val bytes=(if(c.responseCode<400)c.inputStream else c.errorStream).use{stream->val out=java.io.ByteArrayOutputStream();val b=ByteArray(2048);while(true){val n=stream.read(b);if(n<0)break;require(out.size()+n<=32768);out.write(b,0,n)};out.toByteArray()}
            val q=JSONObject(String(bytes,Charsets.UTF_8));if(q.has("error"))throw IllegalArgumentException(q.getString("error"));return q
        }finally{c.disconnect()}
    }
    fun createHotspot(ready:(String,String)->Unit,failed:(String)->Unit){transport=TransportKind.HOTSPOT;direct.releaseGroup{hotspot.create(ready,failed)}}
    fun findDevice(id:String):LocalSession {
        scanLan();val end=System.currentTimeMillis()+3500
        while(System.currentTimeMillis()<end){val s=queue.poll(250,TimeUnit.MILLISECONDS) ?: continue;if(s.device&&s.id==id&&s.address.isNotBlank())return s}
        direct.discover(::record){}
        val p2pEnd=System.currentTimeMillis()+9000
        while(System.currentTimeMillis()<p2pEnd){val s=queue.poll(250,TimeUnit.MILLISECONDS) ?: continue
            if(!s.device||s.id!=id||s.kind!=TransportKind.WIFI_DIRECT)continue
            transport=TransportKind.WIFI_DIRECT;val go=awaitNetwork{ok,fail->direct.connect(s,ok,fail)};bind(go);scanLan()
            val localEnd=System.currentTimeMillis()+5000
            while(System.currentTimeMillis()<localEnd){val local=queue.poll(250,TimeUnit.MILLISECONDS) ?: continue;if(local.device&&local.id==id&&local.address.isNotBlank())return local}
        }
        throw IllegalArgumentException("Appareil non trouvé. Garde son QR affiché et rapproche les téléphones.")
    }
    override fun close(){closed=true;listener=null;lan.close();direct.close();hotspot.close()}
}
