package com.arizona.fosa.mobile

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.*
import android.net.nsd.*
import android.net.wifi.WifiManager
import android.os.*
import androidx.compose.runtime.*
import com.arizona.fosa.R
import org.json.*
import java.net.URL
import java.util.concurrent.*
import java.util.zip.*
import android.util.Base64

class MobileService:Service() {
    companion object { @Volatile var instance:MobileService?=null;var state by mutableStateOf(JSONObject().put("phase","idle"));private set }
    private val main=Handler(Looper.getMainLooper())
    private val publication=java.util.concurrent.atomic.AtomicLong();private var appliedPublication=0L
    private val work=Executors.newSingleThreadScheduledExecutor()
    private var room:LanSession?=null;private var http:LanHttp?=null;private var rtc:RtcMobile?=null
    private var profile=JSONObject();private var session=JSONObject();private var address="";private var ack=0L;private var ticks=0;private var talkUntil=0L
    private var routeWarning="";private var hadWired=false
    private val routes=object:AudioDeviceCallback(){override fun onAudioDevicesAdded(d:Array<out AudioDeviceInfo>){routeAudio()};override fun onAudioDevicesRemoved(d:Array<out AudioDeviceInfo>){routeAudio()}}
    private var error="";private var phase="idle";private var lastPoll=0L;private var mic=false
    private var registration:NsdManager.RegistrationListener?=null
    private var recovery:NsdManager.DiscoveryListener?=null
    private var wake:PowerManager.WakeLock?=null;private var wifi:WifiManager.WifiLock?=null;private var multicast:WifiManager.MulticastLock?=null
    private var pairAnswer="";private val manual=mutableSetOf<String>();private var networkIp=""
    private val cloudWork=Executors.newSingleThreadExecutor();private val cloudBusy=java.util.concurrent.atomic.AtomicBoolean(false)
    private var rendezvousSecret="";private val rendezvousSeen=ConcurrentHashMap.newKeySet<String>();private val rendezvousAnswers=ConcurrentHashMap<String,String>()
    private val rendezvousUrl="https://kgrrxhmzteefmdbgdbaf.supabase.co/functions/v1/pair-rendezvous"
    private val rendezvousKey="sb_publishable_xWl3rWRXfTrL9WGERJmkHQ_Gnjz8bv3"
    private val prefs by lazy{getSharedPreferences("mobile-session",MODE_PRIVATE)}
    private val audio by lazy{getSystemService(AudioManager::class.java)}
    override fun onBind(i:Intent?):IBinder?=null
    override fun onCreate(){super.onCreate();instance=this;audio.registerAudioDeviceCallback(routes,main);getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("fosa-mobile","FOSA Mobile",NotificationManager.IMPORTANCE_LOW))}
    override fun onStartCommand(i:Intent?,flags:Int,id:Int):Int {
        when(i?.action){
            "START"->{ foreground();enqueue{try{start(i)}catch(e:Exception){fail(e.message ?: "Impossible de démarrer")}} }
            "TALK"->push(!(rtc?.talking ?: false),5000)
            "MUTE"->panic(!(rtc?.muted ?: false))
            "STOP"->stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun foreground(){mic=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
        if(Build.VERSION.SDK_INT>=29)startForeground(114,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or if(mic)ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) else startForeground(114,notification())
    }
    private fun start(i:Intent){
        if(rtc!=null)return
        unadvertise();http?.close();http=null;room=null;wake?.let{if(it.isHeld)it.release()};wifi?.let{if(it.isHeld)it.release()};multicast?.let{if(it.isHeld)it.release()}
        networkIp=LanAddress.ip(this) ?: throw IllegalArgumentException("Connecte-toi au Wi-Fi local ou active ton hotspot. Internet n’est pas nécessaire.")
        val network=LanAddress.wifi(this);getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(network)
        audio.mode=AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        run {audio.isSpeakerphoneOn=!audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any{it.type in listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_USB_DEVICE)}}
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"FOSA:mobile").also{it.acquire()}
        val wm=applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        run{wifi=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"FOSA-mobile").also{it.acquire()}}
        multicast=wm.createMulticastLock("FOSA-mobile").also{it.acquire()}
        phase="Connexion locale";publish()
        if(i.getBooleanExtra("host",false)){
            room=LanSession(i.getStringExtra("session") ?: "BAND LIVE",i.getStringExtra("name") ?: "Musicien",i.getStringExtra("role") ?: "")
            http=LanHttp(networkIp,room!!);address="http://$networkIp:${http!!.port}";profile=room!!.ticket(room!!.owner);rendezvousSecret=java.util.UUID.randomUUID().toString()+java.util.UUID.randomUUID();advertise()
        }else {
            val requestedCode=i.getStringExtra("code")?.trim() ?: ""
            require(requestedCode.matches(Regex("\\d{6}"))){"Code session à 6 chiffres requis"}
            val name=i.getStringExtra("name") ?: "Musicien";val role=i.getStringExtra("role") ?: ""
            val raw=i.getStringExtra("address")?.trim().orEmpty()
            val saved=prefs.getString("profile",null);val savedAddress=prefs.getString("address",null);val savedCode=prefs.getString("code",null)
            var restored=false
            if(saved!=null&&savedAddress!=null&&savedCode==requestedCode&&(raw.isBlank()||normalizeAddress(raw)==savedAddress)){
                try{address=savedAddress;profile=JSONObject(saved);request("poll",JSONObject().put("after",0),profile.optString("token"));restored=true}catch(_:Exception){}
            }
            if(!restored){
                if(raw.isNotBlank()){address=normalizeAddress(raw);profile=request("join",JSONObject().put("code",requestedCode).put("name",name).put("role",role).put("client","Native Android"))}
                else {val found=discoverJoin(requestedCode,name,role);address=found.first;profile=found.second}
            }
            prefs.edit().putString("address",address).putString("profile",profile.toString()).putString("code",requestedCode).apply()
        }
        rtc=newRtc();main.post{routeAudio()};if(room==null)recoverDiscovery() else rendezvousCycle();phase="Session connectée";work.scheduleWithFixedDelay({tick()},0,500,TimeUnit.MILLISECONDS)
    }
    private fun enqueue(task:()->Unit){if(!work.isShutdown)try{work.execute{task()}}catch(_:RejectedExecutionException){}}
    private fun newRtc()=RtcMobile(this,profile.getString("id"),mic,{to,type,data->enqueue{try{call("signal",JSONObject().put("to",to).put("type",type).put("data",data))}catch(_:Exception){}}},{publish()},{id,q->
        val host=room ?: throw IllegalArgumentException("Coordinateur indisponible")
        host.call(q.getString("path"),q.optJSONObject("body") ?: JSONObject(),host.ticket(id).getString("token"),"rtc:$id")
    })
    private fun normalizeAddress(raw:String):String {val u=Uri.parse(if(raw.contains("://"))raw else "http://$raw");require(u.scheme=="http"&&LanAddress.privateV4(u.host ?: "")&&u.port in 1..65535&&u.userInfo==null){"Adresse locale invalide"};return "http://${u.host}:${u.port}"}
    @Suppress("DEPRECATION") private fun discoverJoin(code:String,name:String,role:String):Pair<String,JSONObject> {
        phase="Recherche de la session par code";publish()
        val nsd=getSystemService(NsdManager::class.java);val candidates=LinkedBlockingQueue<String>();val tried=mutableSetOf<String>()
        val listener=object:NsdManager.DiscoveryListener{
            override fun onDiscoveryStarted(t:String){}
            override fun onDiscoveryStopped(t:String){}
            override fun onStartDiscoveryFailed(t:String,e:Int){}
            override fun onStopDiscoveryFailed(t:String,e:Int){}
            override fun onServiceLost(i:NsdServiceInfo){}
            override fun onServiceFound(i:NsdServiceInfo){nsd.resolveService(i,object:NsdManager.ResolveListener{
                override fun onResolveFailed(i:NsdServiceInfo,e:Int){}
                override fun onServiceResolved(i:NsdServiceInfo){val ip=i.host?.hostAddress ?: return;if(LanAddress.privateV4(ip))candidates.offer("http://$ip:${i.port}")}
            })}
        }
        nsd.discoverServices("_fosa-mobile._tcp.",NsdManager.PROTOCOL_DNS_SD,listener)
        val deadline=SystemClock.elapsedRealtime()+6500
        try{
            while(SystemClock.elapsedRealtime()<deadline){
                val candidate=candidates.poll(700,TimeUnit.MILLISECONDS) ?: continue
                if(!tried.add(candidate))continue
                address=candidate
                try{return candidate to request("join",JSONObject().put("code",code).put("name",name).put("role",role).put("client","Native Android"))}catch(_:Exception){}
            }
        }finally{try{nsd.stopServiceDiscovery(listener)}catch(_:Exception){}}
        address=""
        throw IllegalArgumentException(if(tried.isEmpty())"Aucune session FOSA détectée. Vérifie que tous les appareils sont sur le même Wi-Fi ou hotspot." else "Aucune session ne correspond à ce code. Vérifie les 6 chiffres puis réessaie.")
    }
    private fun request(path:String,b:JSONObject,token:String=""):JSONObject {
        val url=URL("$address/lan/$path");val c=(LanAddress.wifi(this)?.openConnection(url) ?: url.openConnection()) as java.net.HttpURLConnection
        c.connectTimeout=1500;c.readTimeout=2000;c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");if(token.isNotEmpty())c.setRequestProperty("Authorization","Bearer $token")
        try{c.outputStream.use{it.write(b.toString().toByteArray())};val result=JSONObject((if(c.responseCode<400)c.inputStream else c.errorStream).bufferedReader().use{it.readText()});if(result.has("error"))throw IllegalArgumentException(result.getString("error"));return result}finally{c.disconnect()}
    }
    private fun call(path:String,b:JSONObject)=room?.call(path,b,profile.optString("token")) ?: request(path,b,profile.optString("token"))
    private fun rendezvousRequest(body:JSONObject):JSONObject {
        val cm=getSystemService(ConnectivityManager::class.java);val url=URL(rendezvousUrl)
        val internet=cm.allNetworks.firstOrNull{n->cm.getNetworkCapabilities(n)?.let{c->c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)&&c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}==true}
            ?:cm.allNetworks.firstOrNull{n->cm.getNetworkCapabilities(n)?.let{c->c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}==true}
        val c=((internet?.openConnection(url)) ?: url.openConnection()) as java.net.HttpURLConnection
        c.connectTimeout=1800;c.readTimeout=2600;c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("apikey",rendezvousKey)
        try{c.outputStream.use{it.write(body.toString().toByteArray())};val stream=if(c.responseCode<400)c.inputStream else c.errorStream;val result=JSONObject(stream.bufferedReader().use{it.readText()});if(c.responseCode>=400||result.has("error"))throw IllegalArgumentException(result.optString("error","Rendez-vous indisponible"));return result}finally{c.disconnect()}
    }
    private fun rendezvousCycle(){
        val host=room ?: return;if(rendezvousSecret.isBlank()||!cloudBusy.compareAndSet(false,true)||cloudWork.isShutdown)return
        cloudWork.execute{try{
            rendezvousRequest(JSONObject().put("action","host-register").put("code",host.code).put("secret",rendezvousSecret).put("session",host.id))
            val rows=rendezvousRequest(JSONObject().put("action","host-poll").put("code",host.code).put("secret",rendezvousSecret)).optJSONArray("requests") ?: JSONArray()
            for(n in 0 until rows.length()){val r=rows.getJSONObject(n);val requestId=r.getString("id");val cached=rendezvousAnswers[requestId]
                if(cached!=null)postRendezvousAnswer(requestId,cached) else if(rendezvousSeen.add(requestId))enqueue{pairCloud(requestId,r.getString("offer"))}}
        }catch(_:Exception){}finally{cloudBusy.set(false)}}
    }
    private fun pairCloud(requestId:String,encoded:String){try{
        val host=room ?: return;val q=unpack(encoded);require(q.getString("type")=="offer"&&q.optString("code")==host.code){"Invitation Web invalide"}
        val p=host.call("join",q.put("client","Web code"),remote="web-code");val id=p.getString("id");host.reservePair(id);manual.add(id)
        rtc!!.receive(id,"offer",JSONObject().put("sdp",q.getString("sdp")))
        main.postDelayed({enqueue{try{val sdp=rtc?.links?.get(id)?.pc?.localDescription?.description ?: throw IllegalStateException("Appairage non prêt")
            val answer=pack(JSONObject().put("type","answer").put("sdp",LanAddress.sdp(sdp)).put("profile",p).put("host",profile.getString("id")))
            rendezvousAnswers[requestId]=answer;postRendezvousAnswer(requestId,answer)
        }catch(_:Exception){rendezvousSeen.remove(requestId)}}},2600)
    }catch(_:Exception){rendezvousSeen.remove(requestId)}}
    private fun postRendezvousAnswer(requestId:String,answer:String){if(cloudWork.isShutdown)return;cloudWork.execute{try{
        val host=room ?: return@execute;rendezvousRequest(JSONObject().put("action","host-answer").put("code",host.code).put("secret",rendezvousSecret).put("id",requestId).put("answer",answer));rendezvousAnswers.remove(requestId);rendezvousSeen.remove(requestId)
    }catch(_:Exception){}}}
    private fun tick(){try {
        val engine=rtc ?: return
        if(engine.talking&&SystemClock.elapsedRealtime()>talkUntil)engine.push(false)
        val ip=LanAddress.ip(this)
        if(ip==null){engine.push(false);phase="Réseau perdu · reconnexion";publish();return}
        if(ip!=networkIp){engine.push(false);engine.reset();networkIp=ip;getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(LanAddress.wifi(this));if(room!=null){val oldPort=http?.port ?: 0;http?.close();http=LanHttp(ip,room!!,oldPort);address="http://$ip:${http!!.port}";unadvertise();advertise()}}
        session=call("poll",JSONObject().put("after",ack).put("talk",engine.talking).put("target",engine.target).put("level",if(engine.talking)engine.level else JSONObject.NULL))
        lastPoll=SystemClock.elapsedRealtime();error="";phase="Session connectée"
        val members=session.getJSONArray("members");val list=(0 until members.length()).map{members.getJSONObject(it)}
        engine.sync(list)
        val signals=session.getJSONArray("signals")
        for(n in 0 until signals.length()){val s=signals.getJSONObject(n);engine.receive(s.getString("from"),s.getString("type"),s.getJSONObject("data"));ack=maxOf(ack,s.getLong("seq"))}
        if(++ticks%4==0){engine.stats();getSystemService(NotificationManager::class.java).notify(114,notification())};if(room!=null&&ticks%3==0)rendezvousCycle()
        // Recreate failed links after a bounded interval. The smaller UUID offers, avoiding glare.
        if(ticks%16==0)engine.links.filter{!it.value.connected&&!manual.contains(it.key)}.keys.forEach{id->call("signal",JSONObject().put("to",id).put("type","reset").put("data",JSONObject()));engine.receive(id,"reset",JSONObject())}
        publish()
    }catch(e:Exception){rtc?.push(false);phase="Reconnexion locale";error="Hôte inaccessible. Même Wi-Fi, sans isolation des clients ?";publish()}}
    fun push(active:Boolean,ttl:Long=30000){talkUntil=SystemClock.elapsedRealtime()+ttl;rtc?.push(active);publish()}
    fun target(value:String){rtc?.let{it.push(false,value)};publish()}
    fun panic(active:Boolean){if(!active)routeWarning="";rtc?.panic(active);publish();getSystemService(NotificationManager::class.java).notify(114,notification())}
    fun volume(value:Double){rtc?.volume(value);publish()}
    fun memberMute(id:String,value:Boolean){rtc?.memberMute(id,value);publish()}
    fun group(id:String,value:String){enqueue{try{call("group",JSONObject().put("id",id).put("group",value))}catch(e:Exception){error=e.message ?: "Groupe indisponible";publish()}}}
    fun armMic(){if(!mic&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){foreground();enqueue{rtc?.close();rtc=newRtc();manual.clear();publish()}}}
    fun importWeb(value:String){enqueue{try{
        val host=room ?: throw IllegalArgumentException("Ouvre ce lien sur le téléphone qui a créé la session.")
        val encoded=if(value.startsWith("fosa:"))Uri.parse(value).getQueryParameter("data") ?: "" else value.trim()
        val q=unpack(encoded);require(q.getString("type")=="offer"){"Invitation Web invalide"}
        val p=host.call("join",q.put("client","Web fallback"),remote="web-pair");val id=p.getString("id");host.reservePair(id);manual.add(id)
        rtc!!.receive(id,"offer",JSONObject().put("sdp",q.getString("sdp")))
        // Gather host candidates into the answer; manual pairing cannot trickle before its channel opens.
        main.postDelayed({enqueue{try{val sdp=rtc?.links?.get(id)?.pc?.localDescription?.description ?: throw IllegalStateException("Appairage non prêt. Réessaie.")
            pairAnswer="https://zonampoina.github.io/fosa/mobile/#answer="+pack(JSONObject().put("type","answer").put("sdp",LanAddress.sdp(sdp)).put("profile",p).put("host",profile.getString("id")))
            publish()
        }catch(e:Exception){error=e.message ?: "Appairage impossible";publish()}}},3000)
    }catch(e:Exception){error=e.message ?: "Appairage impossible";publish()}}}
    private fun advertise(){val nsd=getSystemService(NsdManager::class.java);registration=object:NsdManager.RegistrationListener{
        override fun onServiceRegistered(i:NsdServiceInfo){}
        override fun onRegistrationFailed(i:NsdServiceInfo,e:Int){error="Découverte indisponible. Utilise le QR ou l’adresse avancée.";publish()}
        override fun onServiceUnregistered(i:NsdServiceInfo){}
        override fun onUnregistrationFailed(i:NsdServiceInfo,e:Int){}
    };nsd.registerService(NsdServiceInfo().apply{serviceName="FOSA ${room!!.name.take(30)}";serviceType="_fosa-mobile._tcp.";port=http!!.port;setAttribute("session",room!!.id)},NsdManager.PROTOCOL_DNS_SD,registration)}
    @Suppress("DEPRECATION") private fun recoverDiscovery(){
        val nsd=getSystemService(NsdManager::class.java)
        recovery=object:NsdManager.DiscoveryListener{
            override fun onDiscoveryStarted(t:String){}
            override fun onDiscoveryStopped(t:String){}
            override fun onStartDiscoveryFailed(t:String,c:Int){}
            override fun onStopDiscoveryFailed(t:String,c:Int){}
            override fun onServiceLost(i:NsdServiceInfo){}
            override fun onServiceFound(i:NsdServiceInfo){nsd.resolveService(i,object:NsdManager.ResolveListener{
                override fun onResolveFailed(i:NsdServiceInfo,c:Int){}
                override fun onServiceResolved(i:NsdServiceInfo){val sid=i.attributes["session"]?.toString(Charsets.UTF_8);val ip=i.host?.hostAddress ?: return;if(sid==profile.optString("session")&&LanAddress.privateV4(ip))enqueue{address="http://$ip:${i.port}";prefs.edit().putString("address",address).apply()}}
            })}
        };nsd.discoverServices("_fosa-mobile._tcp.",NsdManager.PROTOCOL_DNS_SD,recovery)
    }
    private fun unadvertise(){registration?.let{try{getSystemService(NsdManager::class.java).unregisterService(it)}catch(_:Exception){}};registration=null}
    fun joinLink():String="fosa://mobile?address=${Uri.encode(address)}&code=${room?.code ?: ""}&session=${room?.id ?: ""}"
    @Suppress("DEPRECATION") private fun routeAudio(){
        if(instance!==this||rtc==null)return
        val types=listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_USB_DEVICE)
        val wired=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any{it.type in types}
        if(hadWired&&!wired){rtc?.panic(true);routeWarning="Écouteurs débranchés : mute local. Vérifie la sortie avant UNMUTE."}
        hadWired=wired
        try{if(Build.VERSION.SDK_INT>=31){val devices=audio.availableCommunicationDevices;val chosen=devices.firstOrNull{it.type in types} ?: devices.firstOrNull{it.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER};if(chosen!=null)audio.setCommunicationDevice(chosen)}else audio.isSpeakerphoneOn=!wired}catch(_:SecurityException){}
        publish()
    }
    private fun output():String {
        val route=try{if(Build.VERSION.SDK_INT>=31)audio.communicationDevice else null}catch(_:SecurityException){null}
        val type=route?.type
        return when(type){
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_HEADSET->"Bluetooth · latence supplémentaire"
            AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_USB_DEVICE->"USB audio / DAC"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_WIRED_HEADSET->"Écouteurs filaires"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER->"Haut-parleur · attention au larsen"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE->"Écouteur du téléphone"
            else->"Sortie système · route non mesurée"
        }
    }
    private fun publish(){val version=publication.incrementAndGet();val engine=rtc;val members=session.optJSONArray("members") ?: JSONArray()
        val metrics=JSONArray();engine?.links?.forEach{(id,l)->metrics.put(JSONObject(l.stats.toString()).put("id",id).put("connected",l.connected))}
        val s=JSONObject().put("phase",phase).put("error",engine?.error?.takeIf{it.isNotBlank()} ?: error.ifBlank{routeWarning}).put("active",engine!=null).put("host",room!=null).put("sessionName",profile.optString("sessionName")).put("name",profile.optString("name")).put("role",profile.optString("role")).put("id",profile.optString("id"))
            .put("members",members).put("metrics",metrics).put("talk",engine?.talking ?: false).put("target",engine?.target ?: "all").put("muted",engine?.muted ?: false).put("mic",mic).put("level",engine?.level ?: JSONObject.NULL).put("output",output()).put("address",address).put("code",room?.code ?: "").put("join",if(room!=null)joinLink() else "").put("answer",pairAnswer)
            .put("master",engine?.master ?: .75).put("mutedMembers",JSONArray(engine?.mutedMembers() ?: emptyList<String>())).put("audioPlayback",engine?.playing ?: false).put("latency",JSONObject.NULL).put("internetRequired",false).put("battery",getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("local",LanAddress.ip(this)!=null).put("controlConnected",lastPoll>0&&SystemClock.elapsedRealtime()-lastPoll<5000)
        val apply={if(instance===this&&version>appliedPublication){appliedPublication=version;state=s}}
        if(Looper.myLooper()==Looper.getMainLooper())apply()else main.post{apply()}
    }
    private fun fail(value:String){error=value;phase="Connexion impossible";publish()}
    private fun notification():Notification {val open=PendingIntent.getActivity(this,0,Intent(this,MobileActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(value:String)=PendingIntent.getService(this,value.hashCode(),Intent(this,MobileService::class.java).setAction(value),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,"fosa-mobile").setSmallIcon(R.drawable.fosa_logo).setContentTitle("FOSA MOBILE · ${profile.optString("sessionName","Local")}")
            .setContentText(if(rtc?.talking==true)"TALK · arrêt automatique après 5 s" else "LAN · ${(session.optJSONArray("members")?.length() ?: 1)} membres · ${if(rtc?.muted==true)"MUTED" else "Écoute active"}")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"TALK",action("TALK")).build()).addAction(Notification.Action.Builder(null,"MUTE",action("MUTE")).build()).addAction(Notification.Action.Builder(null,"OPEN",open).build()).build() }
    fun disconnect(){push(false);panic(true);prefs.edit().clear().apply();enqueue{try{if(profile.has("token"))call("leave",JSONObject())}catch(_:Exception){};main.post{stopSelf()}}}
    fun forget(){prefs.edit().clear().apply()}
    override fun onDestroy(){audio.unregisterAudioDeviceCallback(routes);rtc?.push(false);unadvertise();recovery?.let{try{getSystemService(NsdManager::class.java).stopServiceDiscovery(it)}catch(_:Exception){}};http?.close();cloudWork.shutdownNow();work.shutdownNow();rtc?.close();wake?.let{if(it.isHeld)it.release()};wifi?.let{if(it.isHeld)it.release()};multicast?.let{if(it.isHeld)it.release()};getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null);audio.mode=AudioManager.MODE_NORMAL;instance=null;state=JSONObject().put("phase","idle");super.onDestroy()}
    private fun pack(q:JSONObject):String {val out=java.io.ByteArrayOutputStream();DeflaterOutputStream(out).use{it.write(q.toString().toByteArray())};return Base64.encodeToString(out.toByteArray(),Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)}
    private fun unpack(s:String):JSONObject {require(s.length<18000);val stream=InflaterInputStream(java.io.ByteArrayInputStream(Base64.decode(s,Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)));val out=java.io.ByteArrayOutputStream();val b=ByteArray(1024);stream.use{while(true){val n=it.read(b);if(n<0)break;require(out.size()+n<=32768);out.write(b,0,n)}};return JSONObject(out.toString("UTF-8"))}
}
