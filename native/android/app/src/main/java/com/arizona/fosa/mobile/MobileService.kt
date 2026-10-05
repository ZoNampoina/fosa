package com.arizona.fosa.mobile

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
    private val work=Executors.newSingleThreadScheduledExecutor()
    private var room:LanSession?=null;private var http:LanHttp?=null;private var rtc:RtcMobile?=null
    private var profile=JSONObject();private var session=JSONObject();private var address="";private var ack=0L;private var ticks=0;private var talkUntil=0L
    private var error="";private var phase="idle";private var lastPoll=0L;private var mic=false
    private var registration:NsdManager.RegistrationListener?=null
    private var recovery:NsdManager.DiscoveryListener?=null
    private var wake:PowerManager.WakeLock?=null;private var wifi:WifiManager.WifiLock?=null;private var multicast:WifiManager.MulticastLock?=null
    private var pairAnswer="";private val manual=mutableSetOf<String>();private var networkIp=""
    private val prefs by lazy{getSharedPreferences("mobile-session",MODE_PRIVATE)}
    private val audio by lazy{getSystemService(AudioManager::class.java)}
    override fun onBind(i:Intent?):IBinder?=null
    override fun onCreate(){super.onCreate();instance=this;getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("fosa-mobile","FOSA Mobile",NotificationManager.IMPORTANCE_LOW))}
    override fun onStartCommand(i:Intent?,flags:Int,id:Int):Int {
        when(i?.action){
            "START"->{ foreground();work.execute{try{start(i)}catch(e:Exception){fail(e.message ?: "Impossible de démarrer")}} }
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
            http=LanHttp(networkIp,room!!);address="http://$networkIp:${http!!.port}";profile=room!!.ticket(room!!.owner);advertise()
        }else {
            val raw=i.getStringExtra("address") ?: "";val u=Uri.parse(if(raw.contains("://"))raw else "http://$raw")
            require(u.scheme=="http"&&LanAddress.privateV4(u.host ?: "")&&u.port in 1..65535&&u.userInfo==null){"Adresse locale invalide"};address="http://${u.host}:${u.port}"
            val saved=prefs.getString("profile",null)
            profile=if(prefs.getString("address",null)==address&&saved!=null)JSONObject(saved).also{try{request("poll",JSONObject().put("after",0),it.optString("token"))}catch(_:Exception){throw IllegalArgumentException("Session précédente expirée. Quitte-la puis rejoins avec le code.")}}
            else request("join",JSONObject().put("code",i.getStringExtra("code")).put("name",i.getStringExtra("name")).put("role",i.getStringExtra("role")).put("client","Native Android"))
            prefs.edit().putString("address",address).putString("profile",profile.toString()).apply()
        }
        rtc=newRtc();if(room==null)recoverDiscovery();phase="Session connectée";work.scheduleWithFixedDelay({tick()},0,500,TimeUnit.MILLISECONDS)
    }
    private fun newRtc()=RtcMobile(this,profile.getString("id"),mic,{to,type,data->work.execute{try{call("signal",JSONObject().put("to",to).put("type",type).put("data",data))}catch(_:Exception){}}},{publish()},{id,q->
        val host=room ?: throw IllegalArgumentException("Coordinateur indisponible")
        host.call(q.getString("path"),q.optJSONObject("body") ?: JSONObject(),host.ticket(id).getString("token"),"rtc:$id")
    })
    private fun request(path:String,b:JSONObject,token:String=""):JSONObject {
        val url=URL("$address/lan/$path");val c=(LanAddress.wifi(this)?.openConnection(url) ?: url.openConnection()) as java.net.HttpURLConnection
        c.connectTimeout=1500;c.readTimeout=2000;c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");if(token.isNotEmpty())c.setRequestProperty("Authorization","Bearer $token")
        try{c.outputStream.use{it.write(b.toString().toByteArray())};val result=JSONObject((if(c.responseCode<400)c.inputStream else c.errorStream).bufferedReader().use{it.readText()});if(result.has("error"))throw IllegalArgumentException(result.getString("error"));return result}finally{c.disconnect()}
    }
    private fun call(path:String,b:JSONObject)=room?.call(path,b,profile.optString("token")) ?: request(path,b,profile.optString("token"))
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
        if(++ticks%4==0){engine.stats();getSystemService(NotificationManager::class.java).notify(114,notification())}
        // Recreate failed links after a bounded interval. The smaller UUID offers, avoiding glare.
        if(ticks%16==0)engine.links.filter{!it.value.connected&&!manual.contains(it.key)}.keys.forEach{id->call("signal",JSONObject().put("to",id).put("type","reset").put("data",JSONObject()));engine.receive(id,"reset",JSONObject())}
        publish()
    }catch(e:Exception){rtc?.push(false);phase="Reconnexion locale";error="Hôte inaccessible. Même Wi-Fi, sans isolation des clients ?";publish()}}
    fun push(active:Boolean,ttl:Long=30000){talkUntil=SystemClock.elapsedRealtime()+ttl;rtc?.push(active);publish()}
    fun target(value:String){rtc?.let{it.push(false,value)};publish()}
    fun panic(active:Boolean){rtc?.panic(active);publish();getSystemService(NotificationManager::class.java).notify(114,notification())}
    fun volume(value:Double){rtc?.volume(value)}
    fun memberMute(id:String,value:Boolean){rtc?.memberMute(id,value)}
    fun group(id:String,value:String){work.execute{try{call("group",JSONObject().put("id",id).put("group",value))}catch(e:Exception){error=e.message ?: "Groupe indisponible";publish()}}}
    fun armMic(){if(!mic&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){foreground();work.execute{rtc?.close();rtc=newRtc();manual.clear();publish()}}}
    fun importWeb(value:String){work.execute{try{
        val host=room ?: throw IllegalArgumentException("Ouvre ce lien sur le téléphone qui a créé la session.")
        val encoded=if(value.startsWith("fosa:"))Uri.parse(value).getQueryParameter("data") ?: "" else value.trim()
        val q=unpack(encoded);require(q.getString("type")=="offer"){"Invitation Web invalide"}
        val p=host.call("join",q.put("client","Web fallback"),remote="web-pair");val id=p.getString("id");host.reservePair(id);manual.add(id)
        rtc!!.receive(id,"offer",JSONObject().put("sdp",q.getString("sdp")))
        // Gather host candidates into the answer; manual pairing cannot trickle before its channel opens.
        main.postDelayed({work.execute{try{val sdp=rtc?.links?.get(id)?.pc?.localDescription?.description ?: throw IllegalStateException("Appairage non prêt. Réessaie.")
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
                override fun onServiceResolved(i:NsdServiceInfo){val sid=i.attributes["session"]?.toString(Charsets.UTF_8);val ip=i.host?.hostAddress ?: return;if(sid==profile.optString("session")&&LanAddress.privateV4(ip))work.execute{address="http://$ip:${i.port}";prefs.edit().putString("address",address).apply()}}
            })}
        };nsd.discoverServices("_fosa-mobile._tcp.",NsdManager.PROTOCOL_DNS_SD,recovery)
    }
    private fun unadvertise(){registration?.let{try{getSystemService(NsdManager::class.java).unregisterService(it)}catch(_:Exception){}};registration=null}
    fun joinLink():String="fosa://mobile?address=${Uri.encode(address)}&code=${room?.code ?: ""}&session=${room?.id ?: ""}"
    private fun output():String {
        val route=if(Build.VERSION.SDK_INT>=31)audio.communicationDevice else null
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
    private fun publish(){val engine=rtc;val members=session.optJSONArray("members") ?: JSONArray()
        val metrics=JSONArray();engine?.links?.forEach{(id,l)->metrics.put(JSONObject(l.stats.toString()).put("id",id).put("connected",l.connected))}
        val s=JSONObject().put("phase",phase).put("error",engine?.error?.takeIf{it.isNotBlank()} ?: error).put("active",engine!=null).put("host",room!=null).put("sessionName",profile.optString("sessionName")).put("name",profile.optString("name")).put("role",profile.optString("role")).put("id",profile.optString("id"))
            .put("members",members).put("metrics",metrics).put("talk",engine?.talking ?: false).put("target",engine?.target ?: "all").put("muted",engine?.muted ?: false).put("mic",mic).put("level",engine?.level ?: JSONObject.NULL).put("output",output()).put("address",address).put("code",room?.code ?: "").put("join",if(room!=null)joinLink() else "").put("answer",pairAnswer)
            .put("latency",JSONObject.NULL).put("internetRequired",false).put("battery",getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("local",LanAddress.ip(this)!=null).put("controlConnected",lastPoll>0&&SystemClock.elapsedRealtime()-lastPoll<5000)
        main.post{state=s}
    }
    private fun fail(value:String){error=value;phase="Connexion impossible";publish()}
    private fun notification():Notification {val open=PendingIntent.getActivity(this,0,Intent(this,MobileActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(value:String)=PendingIntent.getService(this,value.hashCode(),Intent(this,MobileService::class.java).setAction(value),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,"fosa-mobile").setSmallIcon(R.drawable.fosa_logo).setContentTitle("FOSA MOBILE · ${profile.optString("sessionName","Local")}")
            .setContentText(if(rtc?.talking==true)"TALK · arrêt automatique après 5 s" else "LAN · ${(session.optJSONArray("members")?.length() ?: 1)} membres · ${if(rtc?.muted==true)"MUTED" else "Écoute active"}")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"TALK",action("TALK")).build()).addAction(Notification.Action.Builder(null,"MUTE",action("MUTE")).build()).addAction(Notification.Action.Builder(null,"OPEN",open).build()).build() }
    fun disconnect(){push(false);panic(true);prefs.edit().clear().apply();work.execute{try{if(profile.has("token"))call("leave",JSONObject())}catch(_:Exception){};main.post{stopSelf()}}}
    fun forget(){prefs.edit().clear().apply()}
    override fun onDestroy(){rtc?.push(false);unadvertise();recovery?.let{try{getSystemService(NsdManager::class.java).stopServiceDiscovery(it)}catch(_:Exception){}};http?.close();work.shutdownNow();rtc?.close();wake?.let{if(it.isHeld)it.release()};wifi?.let{if(it.isHeld)it.release()};multicast?.let{if(it.isHeld)it.release()};getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null);audio.mode=AudioManager.MODE_NORMAL;instance=null;state=JSONObject().put("phase","idle");super.onDestroy()}
    private fun pack(q:JSONObject):String {val out=java.io.ByteArrayOutputStream();DeflaterOutputStream(out).use{it.write(q.toString().toByteArray())};return Base64.encodeToString(out.toByteArray(),Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)}
    private fun unpack(s:String):JSONObject {require(s.length<18000);val stream=InflaterInputStream(java.io.ByteArrayInputStream(Base64.decode(s,Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)));val out=java.io.ByteArrayOutputStream();val b=ByteArray(1024);stream.use{while(true){val n=it.read(b);if(n<0)break;require(out.size()+n<=32768);out.write(b,0,n)}};return JSONObject(out.toString("UTF-8"))}
}
