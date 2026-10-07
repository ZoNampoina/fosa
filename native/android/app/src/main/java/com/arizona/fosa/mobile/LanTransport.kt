package com.arizona.fosa.mobile

import android.content.Context
import android.net.nsd.*
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean

/** DNS-SD plus bounded UDP query/reply. The UDP payload contains metadata only. */
class LanTransport(private val ctx:Context):LocalTransport {
    override val kind=TransportKind.LAN
    private val nsd=ctx.getSystemService(NsdManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    private var discovery:NsdManager.DiscoveryListener?=null
    private var registration:NsdManager.RegistrationListener?=null
    private val running=AtomicBoolean(false)
    private var beacon:DatagramSocket?=null
    @Volatile private var metadata:JSONObject?=null
    private var callback:((LocalSession)->Unit)?=null
    private val resolving=java.util.ArrayDeque<NsdServiceInfo>()
    private var busy=false
    override fun announce(metadata:JSONObject) {
        this.metadata=JSONObject(metadata.toString())
        registration?.let{try{nsd.unregisterService(it)}catch(_:Exception){}}
        val listener=object:NsdManager.RegistrationListener {
            override fun onServiceRegistered(s:NsdServiceInfo){}
            override fun onServiceUnregistered(s:NsdServiceInfo){}
            override fun onRegistrationFailed(s:NsdServiceInfo,e:Int){}
            override fun onUnregistrationFailed(s:NsdServiceInfo,e:Int){}
        }
        registration=listener
        val info=NsdServiceInfo().apply {
            serviceName="FOSA ${metadata.optString("sessionName").take(24)}";serviceType="_fosa-mobile._tcp."
            port=metadata.getInt("port")
            for(k in listOf("protocol","session","sessionName","code","hostName","hostRole","transport","version","members","device"))
                if(metadata.has(k))setAttribute(k,metadata.opt(k).toString().take(80))
        }
        try{nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,listener)}catch(_:Exception){}
        if(!running.compareAndSet(false,true))return
        try{beacon=DatagramSocket(null).apply{reuseAddress=true;bind(InetSocketAddress(48764));soTimeout=1000}}
        catch(_:Exception){running.set(false);return}
        Thread({val bytes=ByteArray(2048);while(running.get())try {
            val p=DatagramPacket(bytes,bytes.size);beacon?.receive(p)
            val ip=p.address.hostAddress ?: continue
            if(!LanAddress.privateV4(ip))continue
            val query=JSONObject(String(p.data,0,p.length,Charsets.UTF_8))
            if(query.optString("protocol")!="FOSA-DISCOVERY/2"||query.optString("op")!="find")continue
            val reply=metadata()?.put("query",query.optString("query").take(32)) ?: continue
            val out=reply.toString().toByteArray();if(out.size<=1800)beacon?.send(DatagramPacket(out,out.size,p.address,p.port))
        }catch(_:Exception){}},"FOSA-UDP-discovery").apply{isDaemon=true;start()}
    }
    private fun metadata():JSONObject?=metadata?.let{JSONObject(it.toString()).put("protocol","FOSA-DISCOVERY/2")}
    override fun discover(found:(LocalSession)->Unit,failed:(String)->Unit) {
        callback=found
        if(discovery==null) {
            val listener=object:NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t:String){}
                override fun onDiscoveryStopped(t:String){}
                override fun onStartDiscoveryFailed(t:String,e:Int){failed("Découverte locale indisponible ; recherche du hotspot en cours.")}
                override fun onStopDiscoveryFailed(t:String,e:Int){}
                override fun onServiceLost(s:NsdServiceInfo){}
                override fun onServiceFound(s:NsdServiceInfo){main.post{if(resolving.size<24){resolving.add(s);resolveNext()}}}
            }
            discovery=listener
            try{nsd.discoverServices("_fosa-mobile._tcp.",NsdManager.PROTOCOL_DNS_SD,listener)}catch(_:Exception){failed("Découverte locale indisponible")}
        }
        probe(found)
    }
    @Suppress("DEPRECATION") private fun resolveNext() {
        if(busy||resolving.isEmpty()||discovery==null)return
        busy=true
        try{nsd.resolveService(resolving.removeFirst(),object:NsdManager.ResolveListener {
            override fun onResolveFailed(s:NsdServiceInfo,e:Int){main.post{busy=false;resolveNext()}}
            override fun onServiceResolved(s:NsdServiceInfo){
                val ip=s.host?.hostAddress
                if(ip!=null&&LanAddress.privateV4(ip)) {
                    val q=JSONObject();s.attributes.forEach{(k,v)->q.put(k,String(v,Charsets.UTF_8))};q.put("port",s.port)
                    callback?.invoke(LocalSession.from(q,"http://$ip:${s.port}"))
                }
                main.post{busy=false;resolveNext()}
            }
        })}catch(_:Exception){busy=false;resolveNext()}
    }
    private fun probe(found:(LocalSession)->Unit) { Thread({
        val destinations=linkedSetOf("255.255.255.255")
        LanAddress.gateway(ctx)?.let{destinations.add(it)}
        try{NetworkInterface.getNetworkInterfaces().toList().filter{it.isUp&&!it.isLoopback}.forEach{n->n.interfaceAddresses.mapNotNull{it.broadcast?.hostAddress}.forEach{destinations.add(it)}}}catch(_:Exception){}
        try{DatagramSocket().use{s->s.broadcast=true;s.soTimeout=500
            val query=java.util.UUID.randomUUID().toString().take(16)
            val bytes=JSONObject().put("protocol","FOSA-DISCOVERY/2").put("op","find").put("query",query).toString().toByteArray()
            repeat(3){
                for(ip in destinations)try{s.send(DatagramPacket(bytes,bytes.size,InetAddress.getByName(ip),48764))}catch(_:Exception){}
                val until=System.currentTimeMillis()+600
                while(System.currentTimeMillis()<until)try{val p=DatagramPacket(ByteArray(2048),2048);s.receive(p);val ip=p.address.hostAddress ?: continue
                    val q=JSONObject(String(p.data,0,p.length,Charsets.UTF_8));val port=q.optInt("port")
                    if(LanAddress.privateV4(ip)&&q.optString("query")==query&&q.optString("protocol")=="FOSA-DISCOVERY/2"&&port in 1..65535)
                        found(LocalSession.from(q,"http://$ip:$port"))
                }catch(_:Exception){}
            }
        }}catch(_:Exception){}
    },"FOSA-LAN-probe").apply{isDaemon=true;start()} }
    override fun connect(session:LocalSession,connected:(String)->Unit,failed:(String)->Unit){if(session.address.isNotBlank())connected(session.address)else failed("Session locale sans adresse")}
    override fun close(){callback=null;discovery?.let{try{nsd.stopServiceDiscovery(it)}catch(_:Exception){}};discovery=null;registration?.let{try{nsd.unregisterService(it)}catch(_:Exception){}};registration=null;running.set(false);beacon?.close();resolving.clear()}
}
