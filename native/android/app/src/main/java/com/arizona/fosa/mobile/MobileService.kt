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
    // A P2P discovery/group operation can wait on the worker. Existing peers
    // must still see their host online, and held Talk keeps its safety limit.
    private val ownerBeat=object:Runnable{override fun run(){if(instance!==this@MobileService)return;val engine=rtc;val host=room;if(engine!=null&&host!=null){engine.heartbeat();host.touchOwner(engine.talking,engine.target,engine.level);if(LanAddress.ip(this@MobileService)!=null)lastPoll=SystemClock.elapsedRealtime()};main.postDelayed(this,1000)}}
    private val publication=java.util.concurrent.atomic.AtomicLong();private var appliedPublication=0L
    private val work=Executors.newSingleThreadScheduledExecutor()
    private var room:LanSession?=null;private var http:LanHttp?=null;private var https:LanHttp?=null;private var rtc:RtcMobile?=null
    private var tls:LanTls?=null;private var webError="";private var connectionPhase=ConnectionPhase.DISCOVERING;private var reconnectCount=0
    private var servedAddresses=emptySet<String>()
    private var recoveryAttempts=0
    private var joiningCode="";private var lastReconnectAttempt=0L
    private val connection by lazy{FosaConnectionManager(this){p,_,message->connectionPhase=p;if(message.isNotBlank())error=message;publish()}}
    private var profile=JSONObject();private var session=JSONObject();private var address="";private var ack=0L;private var ticks=0;private var repairDue=0L
    private val audioRepairs=ConcurrentHashMap<String,Int>()
    private var routeWarning="";private var hadWired=false
    private val routes=object:AudioDeviceCallback(){override fun onAudioDevicesAdded(d:Array<out AudioDeviceInfo>){routeAudio()};override fun onAudioDevicesRemoved(d:Array<out AudioDeviceInfo>){routeAudio()}}
    private var error="";private var phase="idle";private var lastPoll=0L;private var mic=false
    private var registration:NsdManager.RegistrationListener?=null
    private var recovery:NsdManager.DiscoveryListener?=null
    private var wake:PowerManager.WakeLock?=null;private var wifi:WifiManager.WifiLock?=null;private var multicast:WifiManager.MulticastLock?=null
    private var pairAnswer="";private val manual=mutableSetOf<String>();private var networkIp=""
    private val cloudWork=Executors.newSingleThreadExecutor();private val cloudBusy=java.util.concurrent.atomic.AtomicBoolean(false)
    private var rendezvousSecret="";private var webPairingUntil=0L;private val rendezvousSeen=ConcurrentHashMap.newKeySet<String>();private val rendezvousAnswers=ConcurrentHashMap<String,String>()
    private val preferredLanPort=48765
    private val rendezvousUrl="https://kgrrxhmzteefmdbgdbaf.supabase.co/functions/v1/pair-rendezvous"
    private val rendezvousKey="sb_publishable_xWl3rWRXfTrL9WGERJmkHQ_Gnjz8bv3"
    private val rendezvousIceCounts=ConcurrentHashMap<String,Int>()
    private val prefs by lazy{getSharedPreferences("mobile-session",MODE_PRIVATE)}
    private val audio by lazy{getSystemService(AudioManager::class.java)}
    override fun onBind(i:Intent?):IBinder?=null
    override fun onCreate(){super.onCreate();instance=this;main.post(ownerBeat);audio.registerAudioDeviceCallback(routes,main);getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("fosa-mobile","FOSA Mobile",NotificationManager.IMPORTANCE_LOW))}
    override fun onStartCommand(i:Intent?,flags:Int,id:Int):Int {
        when(i?.action){
            "START"->{ foreground();enqueue{try{start(i)}catch(e:Exception){fail(e.message ?: "Impossible de démarrer")}} }
            "HOTSPOT"->{if(Build.VERSION.SDK_INT>=29)startForeground(114,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)else startForeground(114,notification());createLocalNetwork()}
            "TALK"->{if(rtc?.talkControl?.mode=="HOLD")talkMode("TAP");push(!(rtc?.talkControl?.armed ?: false))}
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
        if(i.getBooleanExtra("host",false)&&i.getStringExtra("network")!="HOTSPOT"){hotspotName="";hotspotPassword=""}
        networkIp=if(i.getBooleanExtra("host",false))connection.hostNetwork(i.getStringExtra("network") ?: "CURRENT") else LanAddress.ip(this).orEmpty()
        val network=LanAddress.wifi(this);getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(if(room!=null||connection.transport==TransportKind.HOTSPOT||connection.transport==TransportKind.WIFI_DIRECT)null else network)
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
            startLocalServers();profile=room!!.ticket(room!!.owner);rendezvousSecret=java.util.UUID.randomUUID().toString()+java.util.UUID.randomUUID();advertise()
        }else {
            val requestedCode=i.getStringExtra("code")?.trim() ?: ""
            joiningCode=requestedCode
            require(requestedCode.matches(Regex("\\d{6}"))){"Code session à 6 chiffres requis"}
            val name=i.getStringExtra("name") ?: "Musicien";val role=i.getStringExtra("role") ?: ""
            val raw=i.getStringExtra("address")?.trim().orEmpty()
            val saved=prefs.getString("profile",null);val savedAddress=prefs.getString("address",null);val savedCode=prefs.getString("code",null)
            val keyName="client-key:$requestedCode"
            val clientKey=prefs.getString(keyName,null) ?: (java.util.UUID.randomUUID().toString()+java.util.UUID.randomUUID()).replace("-","").also{prefs.edit().putString(keyName,it).apply()}
            val join=JSONObject().put("code",requestedCode).put("name",name).put("role",role).put("client","Native Android").put("clientKey",clientKey)
            if(saved!=null&&savedCode==requestedCode)join.put("resumeToken",JSONObject(saved).optString("token"))
            val preferred=raw.ifBlank{if(savedCode==requestedCode)savedAddress.orEmpty() else ""}
            val found=connection.resolve(requestedCode,if(preferred.isNotBlank())normalizeAddress(preferred) else "",i.getStringExtra("sessionId").orEmpty())
            address=found.address;connection.bind(address);profile=connection.join(found,join)
            networkIp=LanAddress.ip(this).orEmpty()
            prefs.edit().putString("address",address).putString("profile",profile.toString()).putString("code",requestedCode).apply()
        }
        rtc=newRtc();connection.phase(ConnectionPhase.NEGOTIATING_AUDIO);main.post{routeAudio()};if(room==null)recoverDiscovery();phase="Session connectée";work.scheduleWithFixedDelay({tick()},0,500,TimeUnit.MILLISECONDS)
    }
    private fun enqueue(task:()->Unit){if(!work.isShutdown)try{work.execute{task()}}catch(_:RejectedExecutionException){}}
    private fun newRtc()=RtcMobile(this,profile.getString("id"),mic,{to,type,data->enqueue{try{call("signal",JSONObject().put("to",to).put("type",type).put("data",data))}catch(_:Exception){}}},{publish()},{id,q->
        val host=room ?: throw IllegalArgumentException("Coordinateur indisponible")
        host.call(q.getString("path"),q.optJSONObject("body") ?: JSONObject(),host.ticket(id).getString("token"),"rtc:$id")
    },prefs.getBoolean("noise-reduction",true)).also{engine->engine.talkControl.threshold=prefs.getFloat("vox-threshold",-36f).toDouble();engine.talkControl.closeDelayMs=prefs.getLong("vox-close",650);engine.talkControl.timeoutMs=prefs.getLong("talk-timeout",0)}
    private fun normalizeAddress(raw:String):String {val u=Uri.parse(if(raw.contains("://"))raw else "http://$raw");require(u.scheme=="http"&&LanAddress.privateV4(u.host ?: "")&&u.port in 1..65535&&u.userInfo==null){"Adresse locale invalide"};return "http://${u.host}:${u.port}"}
    private fun startLocalServers(){
        http?.close();https?.close();https=null
        // Listening servers must accept either local interface. Do not mark
        // their sockets with the unrelated infrastructure Wi-Fi network.
        val cm=getSystemService(ConnectivityManager::class.java);val previous=cm.boundNetworkForProcess;cm.bindProcessToNetwork(null)
        servedAddresses=LanAddress.ips().toSet()
        try{
        tls=try{LanTls(this,LanAddress.ips())}catch(e:Exception){webError="Web sécurisé indisponible : ${e.javaClass.simpleName}";null}
        http=try{LanHttp("0.0.0.0",room!!,preferredLanPort,this,tls?.ca)}catch(_:Exception){LanHttp("0.0.0.0",room!!,0,this,tls?.ca)}
        address="http://$networkIp:${http!!.port}"
        tls?.let{try{https=LanHttp("0.0.0.0",room!!,48766,this,it.ca,it.factory);webError=""}catch(_:Exception){webError="Port Web sécurisé occupé"}}
        }finally{cm.bindProcessToNetwork(previous)}
    }
    private fun request(path:String,b:JSONObject,token:String="")=connection.request(address,path,b,token)
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
    private fun applyWebOffer(id:String,q:JSONObject){
        val sdp=q.getString("sdp")
        rtc!!.receive(id,"offer",JSONObject().put("sdp",sdp))
        val ice=q.optJSONArray("ice") ?: return
        for(i in 0 until ice.length()){
            val candidate=ice.getJSONObject(i);val value=candidate.optString("candidate")
            if(value.isNotBlank()&&!sdp.contains("a=$value"))rtc!!.receive(id,"ice",candidate)
        }
    }
    private fun pairingIce(id:String):JSONArray {val out=JSONArray();rtc?.links?.get(id)?.localIce?.forEach{candidate->out.put(JSONObject().put("candidate",candidate.sdp).put("sdpMid",candidate.sdpMid).put("sdpMLineIndex",candidate.sdpMLineIndex))};return out}
    private fun pairingAnswer(id:String,p:JSONObject):String {
        val link=rtc?.links?.get(id) ?: throw IllegalStateException("Lien audio absent")
        val sdp=link.pc.localDescription?.description ?: throw IllegalStateException("Réponse audio en préparation")
        val ice=pairingIce(id);require(ice.length()>0){"Candidats ICE locaux en préparation"}
        return pack(JSONObject().put("type","answer").put("sdp",LanAddress.localSdp(this,sdp)).put("ice",ice).put("profile",p).put("host",profile.getString("id")))
    }
    private fun finishCloudPair(requestId:String,id:String,p:JSONObject,attempt:Int=0){
        main.postDelayed({enqueue{
            val link=rtc?.links?.get(id)
            if(link==null){rendezvousAnswers.remove(requestId);rendezvousIceCounts.remove(requestId);rendezvousSeen.remove(requestId);return@enqueue}
            try{
                val count=link.localIce.size
                if(count>0&&(attempt==0||rendezvousIceCounts[requestId]!=count)){
                    val answer=pairingAnswer(id,p);rendezvousIceCounts[requestId]=count;rendezvousAnswers[requestId]=answer;postRendezvousAnswer(requestId,answer)
                }
                if(link.connected||attempt>=24){rendezvousAnswers.remove(requestId);rendezvousIceCounts.remove(requestId);rendezvousSeen.remove(requestId)}
                else finishCloudPair(requestId,id,p,attempt+1)
            }catch(_:Exception){
                if(attempt<24)finishCloudPair(requestId,id,p,attempt+1)
                else {rendezvousAnswers.remove(requestId);rendezvousIceCounts.remove(requestId);rendezvousSeen.remove(requestId)}
            }
        }},if(attempt==0)450 else 350)
    }
    private fun finishOfflinePair(id:String,p:JSONObject,attempt:Int=0){
        main.postDelayed({enqueue{try{
            pairAnswer="https://zonampoina.github.io/fosa/mobile/#answer="+pairingAnswer(id,p);error="";publish()
        }catch(e:Exception){if(attempt<20&&rtc?.links?.containsKey(id)==true)finishOfflinePair(id,p,attempt+1) else {error=e.message ?: "Appairage impossible";publish()}}}},if(attempt==0)500 else 200)
    }
    private fun pairCloud(requestId:String,encoded:String){try{
        val host=room ?: return;val q=unpack(encoded);require(q.getString("type")=="offer"&&q.optString("code")==host.code){"Invitation Web invalide"}
        val p=host.call("join",q.put("client","Web code"),remote="web-code");val id=p.getString("id");host.reservePair(id);manual.add(id)
        rtc!!.preparePair(id,p.optInt("generation",1))
        applyWebOffer(id,q)
        finishCloudPair(requestId,id,p)
    }catch(_:Exception){rendezvousSeen.remove(requestId)}}
    private fun postRendezvousAnswer(requestId:String,answer:String){if(cloudWork.isShutdown)return;cloudWork.execute{try{
        val host=room ?: return@execute;rendezvousRequest(JSONObject().put("action","host-answer").put("code",host.code).put("secret",rendezvousSecret).put("id",requestId).put("answer",answer))
    }catch(_:Exception){}}}
    private fun tick(){try {
        val engine=rtc ?: return
        val now=SystemClock.elapsedRealtime();engine.heartbeat()
        if(engine.talkRequested&&!engine.talking&&repairDue>0&&now>=repairDue){forceRepair(engine);repairDue=now+5000}
        val ip=if(room!=null)connection.currentAddress() else LanAddress.ip(this)
        if(ip==null){engine.push(false);connectionPhase=ConnectionPhase.RECONNECTING;phase="Réseau perdu · reconnexion";publish();recoverConnection();return}
        if(ip!=networkIp){engine.push(false);engine.reset();networkIp=ip;if(room==null)connection.bind(address) else getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(LanAddress.wifi(this));if(room!=null){startLocalServers();advertise()}}
        if(room!=null&&LanAddress.ips().toSet()!=servedAddresses){if(connection.direct.groupInterface.isNotBlank())getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null);startLocalServers();advertise()}
        session=call("poll",JSONObject().put("after",ack).put("talk",engine.talking).put("target",engine.target).put("level",if(engine.talking)engine.level else JSONObject.NULL))
        lastPoll=SystemClock.elapsedRealtime();recoveryAttempts=0;error="";phase="Session connectée";connectionPhase=if(engine.links.values.any{it.connected})ConnectionPhase.CONNECTED else ConnectionPhase.NEGOTIATING_AUDIO
        val members=session.getJSONArray("members");val list=(0 until members.length()).map{members.getJSONObject(it)}
        engine.sync(list)
        val signals=session.getJSONArray("signals")
        for(n in 0 until signals.length()){val s=signals.getJSONObject(n);val from=s.getString("from")
            if(!s.has("generation")||list.any{it.optString("id")==from&&it.optInt("generation",1)==s.getInt("generation")})engine.receive(from,s.getString("type"),s.getJSONObject("data"))
            ack=maxOf(ack,s.getLong("seq"))}
        if(++ticks%4==0){engine.stats();getSystemService(NotificationManager::class.java).notify(114,notification())};if(room!=null&&SystemClock.elapsedRealtime()<webPairingUntil&&ticks%3==0)rendezvousCycle()
        // Recreate failed links after a bounded interval. The smaller UUID offers, avoiding glare.
        if(ticks%16==0)engine.links.filter{!it.value.connected&&!manual.contains(it.key)&&(audioRepairs[it.key] ?: 0)<3}.keys.forEach{id->audioRepairs[id]=(audioRepairs[id] ?: 0)+1;call("signal",JSONObject().put("to",id).put("type","reset").put("data",JSONObject()));engine.receive(id,"reset",JSONObject())}
        publish()
    }catch(e:Exception){rtc?.push(false);if(connectionPhase!=ConnectionPhase.RECONNECTING)reconnectCount++;connectionPhase=ConnectionPhase.RECONNECTING;phase="Reconnexion locale";error="Hôte inaccessible. Même Wi-Fi, sans isolation des clients ?";publish();recoverConnection()}}
    private fun recoverConnection(){if(SystemClock.elapsedRealtime()-lastReconnectAttempt<7000||room==null&&lastPoll>0&&SystemClock.elapsedRealtime()-lastPoll<6000)return
        if(recoveryAttempts>=6){error="Six reprises locales échouées : vérifie le réseau puis RECONNECT AUDIO";publish();return}
        recoveryAttempts++;lastReconnectAttempt=SystemClock.elapsedRealtime()
        if(room!=null){if(connection.transport==TransportKind.WIFI_DIRECT)try{networkIp=connection.hostNetwork("WIFI_DIRECT");getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null);rtc?.reset();startLocalServers();advertise()}catch(e:Exception){error=e.message.orEmpty()};return}
        if(joiningCode.isBlank())return
        try{val found=connection.resolve(joiningCode,address,profile.optString("session"));connection.bind(found.address)
            val body=JSONObject().put("code",joiningCode).put("name",profile.optString("name")).put("role",profile.optString("role")).put("client","Native Android").put("resumeToken",profile.optString("token")).put("clientKey",prefs.getString("client-key:$joiningCode",""))
            val resumed=connection.join(found,body);require(resumed.getString("id")==profile.getString("id")){"Identité de reprise différente"};profile=resumed;address=found.address;ack=0;rtc?.reset();prefs.edit().putString("profile",profile.toString()).putString("address",address).apply()
        }catch(_:Exception){}
    }
    fun push(active:Boolean){
        val engine=rtc;engine?.push(active)
        repairDue=if(active&&engine?.talkRequested==true&&!engine.talking)SystemClock.elapsedRealtime()+1200 else 0L
        publish();getSystemService(NotificationManager::class.java).notify(114,notification())
    }
    private fun forceRepair(engine:RtcMobile){
        if(engine.talkControl.mode!="HOLD")engine.push(false)
        val ids=engine.links.keys.filter{(audioRepairs[it] ?: 0)<3}
        if(ids.isEmpty()){engine.push(false);error="Trois réparations audio échouées : vérifie le réseau et lance TEST AUDIO avant un nouvel essai";publish();return}
        ids.forEach{id->audioRepairs[id]=(audioRepairs[id] ?: 0)+1}
        ids.forEach{id->try{call("signal",JSONObject().put("to",id).put("type","reset").put("data",JSONObject()))}catch(_:Exception){}}
        engine.repair()
        val members=session.optJSONArray("members")
        if(members!=null){val list=(0 until members.length()).map{members.getJSONObject(it)};engine.sync(list)}
        error="";phase="Audio · reconnexion directe";publish()
    }
    fun enableWebPairing(ttl:Long=120000){if(room==null)return;webPairingUntil=SystemClock.elapsedRealtime()+ttl;enqueue{rendezvousCycle()};publish()}
    fun repairAudio(){recoveryAttempts=0;audioRepairs.clear();enqueue{rtc?.let{forceRepair(it)}}}
    fun talkMode(value:String){rtc?.mode(value);publish()}
    fun audioOptions(threshold:Double?=null,closeDelay:Long?=null,timeout:Long?=null,noiseReduction:Boolean?=null){val engine=rtc ?: return
        threshold?.let{engine.talkControl.threshold=it.coerceIn(-60.0,-12.0);prefs.edit().putFloat("vox-threshold",engine.talkControl.threshold.toFloat()).apply()}
        closeDelay?.let{engine.talkControl.closeDelayMs=it.coerceIn(150,3000);prefs.edit().putLong("vox-close",engine.talkControl.closeDelayMs).apply()}
        timeout?.let{engine.talkControl.safetyTimeout(it,SystemClock.elapsedRealtime());prefs.edit().putLong("talk-timeout",engine.talkControl.timeoutMs).apply()}
        noiseReduction?.let{if(it!=engine.talkControl.noiseReduction){push(false);prefs.edit().putBoolean("noise-reduction",it).apply();enqueue{engine.links.keys.toList().forEach{id->try{call("signal",JSONObject().put("to",id).put("type","reset").put("data",JSONObject()))}catch(_:Exception){}};val savedMaster=engine.master;val savedTarget=engine.target;val savedListen=engine.listenTarget;val savedMutes=engine.mutedMembers();val savedMode=engine.talkControl.mode;val savedPanic=engine.muted
                engine.close();rtc=newRtc().also{fresh->fresh.volume(savedMaster);fresh.destination(savedTarget);fresh.listen(savedListen);savedMutes.forEach{fresh.memberMute(it,true)};fresh.mode(savedMode);if(savedPanic)fresh.panic(true)};publish()}}};publish()
    }
    fun testAudio(){try{rtc?.testAudio();publish()}catch(e:Exception){error=e.message.orEmpty();publish()}}
    fun pauseHold(){if(rtc?.talkControl?.mode=="HOLD")push(false)}
    fun target(value:String){rtc?.destination(value);publish()}
    fun listen(value:String){rtc?.listen(value);publish()}
    fun panic(active:Boolean){if(!active)routeWarning="";rtc?.panic(active);publish();getSystemService(NotificationManager::class.java).notify(114,notification())}
    fun volume(value:Double){rtc?.volume(value);publish()}
    fun memberMute(id:String,value:Boolean){rtc?.memberMute(id,value);publish()}
    fun group(id:String,value:String){enqueue{try{call("group",JSONObject().put("id",id).put("group",value))}catch(e:Exception){error=e.message ?: "Groupe indisponible";publish()}}}
    fun armMic(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){foreground();enqueue{rtc?.enableMicrophone();mic=rtc?.microphoneReady() ?: true;publish()}}}
    fun importWeb(value:String){enqueue{try{
        val host=room ?: throw IllegalArgumentException("Ouvre ce lien sur le téléphone qui a créé la session.")
        val encoded=if(value.startsWith("fosa:"))Uri.parse(value).getQueryParameter("data") ?: "" else value.trim()
        val q=unpack(encoded);require(q.getString("type")=="offer"){"Invitation Web invalide"}
        val p=host.call("join",q.put("client","Web fallback"),remote="web-pair");val id=p.getString("id");host.reservePair(id);manual.add(id)
        rtc!!.preparePair(id,p.optInt("generation",1))
        applyWebOffer(id,q)
        // Manual pairing carries both browser and native LAN ICE candidates explicitly; no trickle deadlock.
        finishOfflinePair(id,p)
    }catch(e:Exception){error=e.message ?: "Appairage impossible";publish()}}}
    private fun advertise(){val host=room ?: return;host.transportLabel=connection.transport.label;connection.announce(host.metadata().put("port",http!!.port).put("transport",connection.transport.name))}
    fun addDevice(qr:String){enqueue{try{
        val host=room ?: throw IllegalArgumentException("Crée une session avant d’ajouter un appareil")
        val u=PairingQr.parse(qr);require(u.host=="device"){"Scanne le QR de l’appareil invité"}
        try{host.call("device-admit",JSONObject().put("id",u.getQueryParameter("id")).put("nonce",u.getQueryParameter("n")),profile.getString("token"));error="";publish();return@enqueue}catch(_:IllegalArgumentException){}
        val device=connection.findDevice(u.getQueryParameter("id")!!)
        val ip=LanAddress.sourceForPeer(Uri.parse(device.address).host.orEmpty()) ?: throw IllegalArgumentException("Appareil hors du réseau local")
        if(LanAddress.ips().toSet()!=servedAddresses){startLocalServers();advertise()}
        val reachable="http://$ip:${http!!.port}"
        connection.request(device.address,"/pair/accept",JSONObject().put("id",u.getQueryParameter("id")).put("nonce",u.getQueryParameter("n")).put("join",PairingQr.session(host.id,host.code,reachable)))
        error="";publish()
    }catch(e:Exception){error=e.message ?: "Appareil inaccessible";publish()}}}
    fun createLocalNetwork(){
        if((session.optJSONArray("members")?.length() ?: 1)>1){error="Déconnecte les invités avant de changer de réseau";publish();return}
        connection.createHotspot({ssid,password->enqueue{error="";hotspotName=ssid;hotspotPassword=password;publish()}},{message->hotspotName="";hotspotPassword="";if(connection.transport==TransportKind.HOTSPOT)error=message;publish()})
    }
    private var hotspotName="";private var hotspotPassword=""
    fun permissions(id:String,talk:Boolean,listen:Boolean){enqueue{try{call("permissions",JSONObject().put("id",id).put("canTalk",talk).put("canListen",listen))}catch(e:Exception){error=e.message.orEmpty()};publish()}}
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
                override fun onServiceResolved(i:NsdServiceInfo){val sid=i.attributes["session"]?.toString(Charsets.UTF_8);val ip=i.host?.hostAddress ?: return;if(sid==profile.optString("session")&&LanAddress.privateV4(ip))enqueue{try{val next="http://$ip:${i.port}";if(connection.request(next,"info",JSONObject()).optString("session")==profile.optString("session")){address=next;connection.bind(address);prefs.edit().putString("address",address).apply()}}catch(_:Exception){}}}
            })}
        };nsd.discoverServices("_fosa-mobile._tcp.",NsdManager.PROTOCOL_DNS_SD,recovery)
    }
    private fun unadvertise(){registration?.let{try{getSystemService(NsdManager::class.java).unregisterService(it)}catch(_:Exception){}};registration=null}
    fun joinLink():String=room?.let{PairingQr.session(it.id,it.code,address)} ?: ""
    @Suppress("DEPRECATION") private fun routeAudio(){
        if(instance!==this||rtc==null)return
        val types=listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_USB_DEVICE)
        val wired=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any{it.type in types}
        if(hadWired&&!wired){rtc?.panic(true);routeWarning="Écouteurs débranchés : mute local. Vérifie la sortie avant UNMUTE."}
        hadWired=wired
        try{if(Build.VERSION.SDK_INT>=31){val devices=audio.availableCommunicationDevices;val chosen=devices.firstOrNull{it.type in types} ?: devices.firstOrNull{it.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER};if(chosen!=null&&!audio.setCommunicationDevice(chosen))routeWarning="Sélection de sortie refusée par Android : vérifie la route système"}else audio.isSpeakerphoneOn=!wired}catch(_:SecurityException){}
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
        val metrics=JSONArray();engine?.links?.forEach{(id,l)->metrics.put(JSONObject(l.stats.toString()).put("id",id).put("connected",l.connected).put("iceState",l.iceState).put("localCandidates",l.localCandidates).put("remoteCandidates",l.remoteCandidates))}
        val d=engine?.diagnostics() ?: JSONObject();val sending=engine?.talking==true&&engine.links.values.any{it.stats.optLong("txPacketsDelta")>0}
        val s=JSONObject().put("audioDiagnostics",d).put("talkMode",engine?.talkControl?.mode ?: "HOLD").put("talkArmed",engine?.talkControl?.armed ?: false).put("capturing",d.optBoolean("capture")).put("sending",sending).put("receiving",d.optBoolean("receiving")).put("receiveLevel",engine?.receiveLevel ?: JSONObject.NULL).put("microphoneSystemMuted",audio.isMicrophoneMute).put("outputSystemVolume",audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)).put("networkInterfaces",JSONArray(LanAddress.ips())).put("wifiQr",if(hotspotName.isNotBlank()&&hotspotPassword.isNotBlank())WifiQr.encode(hotspotName,hotspotPassword) else "").put("phase",phase).put("error",engine?.error?.takeIf{it.isNotBlank()} ?: error.ifBlank{routeWarning}).put("active",engine!=null).put("host",room!=null).put("sessionName",profile.optString("sessionName")).put("name",profile.optString("name")).put("role",profile.optString("role")).put("id",profile.optString("id"))
            .put("members",members).put("metrics",metrics).put("talk",engine?.talking ?: false).put("talkRequested",engine?.talkRequested ?: false).put("target",engine?.target ?: "all").put("listenTarget",engine?.listenTarget ?: "all").put("muted",engine?.muted ?: false).put("mic",engine?.microphoneReady() ?: mic).put("level",engine?.level ?: JSONObject.NULL).put("output",output()).put("address",address).put("code",room?.code ?: "").put("join",if(room!=null)joinLink() else "").put("answer",pairAnswer)
            .put("master",engine?.master ?: .75).put("mutedMembers",JSONArray(engine?.mutedMembers() ?: emptyList<String>())).put("audioPlayback",engine?.playing ?: false).put("latency",JSONObject.NULL).put("internetRequired",false).put("battery",getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("connectionPhase",connectionPhase.name).put("transport",connection.transport.label).put("signaling","LOCAL").put("recoveryAttempts",recoveryAttempts).put("reconnectCount",reconnectCount).put("web",if(https!=null)"https://$networkIp:48766/" else "").put("webSetup","http://$networkIp:${http?.port ?: preferredLanPort}/trust").put("webError",webError).put("fingerprint",tls?.ca?.let{java.security.MessageDigest.getInstance("SHA-256").digest(it).joinToString(":"){b->"%02X".format(b)}} ?: "").put("hotspotName",hotspotName).put("hotspotPassword",hotspotPassword).put("local",LanAddress.ip(this)!=null).put("webPairing",room!=null&&SystemClock.elapsedRealtime()<webPairingUntil).put("controlConnected",lastPoll>0&&SystemClock.elapsedRealtime()-lastPoll<5000)
        val apply={if(instance===this&&version>appliedPublication){appliedPublication=version;state=s}}
        if(Looper.myLooper()==Looper.getMainLooper())apply()else main.post{apply()}
    }
    private fun fail(value:String){error=value;connectionPhase=ConnectionPhase.FAILED;phase="Connexion impossible";publish()}
    private fun notification():Notification {val open=PendingIntent.getActivity(this,0,Intent(this,MobileActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(value:String)=PendingIntent.getService(this,value.hashCode(),Intent(this,MobileService::class.java).setAction(value),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,"fosa-mobile").setSmallIcon(R.drawable.fosa_logo).setContentTitle("FOSA MOBILE · ${profile.optString("sessionName","Local")}")
            .setContentText(if(rtc?.talkControl?.armed==true)"MIC OPEN · ${rtc?.talkControl?.mode} · toucher STOP TALK pour fermer" else "LAN · ${(session.optJSONArray("members")?.length() ?: 1)} membres · ${if(rtc?.muted==true)"MUTED" else "Écoute active"}")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,if(rtc?.talkControl?.armed==true)"STOP TALK" else "TAP TALK",action("TALK")).build()).addAction(Notification.Action.Builder(null,"MUTE",action("MUTE")).build()).addAction(Notification.Action.Builder(null,"OPEN",open).build()).build() }
    fun disconnect(){push(false);panic(true);enqueue{
        // A failed leave may still leave the old slot on the host. Keep its
        // private proof so the next join restores it rather than duplicating it.
        try{if(profile.has("token")){call("leave",JSONObject());if(room==null){val savedCode=prefs.getString("code","");prefs.edit().remove("profile").remove("address").remove("code").remove("client-key:$savedCode").apply()}}}catch(_:Exception){}
        main.post{stopSelf()}
    }}
    fun forget(){prefs.edit().clear().apply()}
    override fun onDestroy(){audio.unregisterAudioDeviceCallback(routes);rtc?.push(false);connection.close();https?.close();unadvertise();recovery?.let{try{getSystemService(NsdManager::class.java).stopServiceDiscovery(it)}catch(_:Exception){}};http?.close();cloudWork.shutdownNow();work.shutdownNow();rtc?.close();wake?.let{if(it.isHeld)it.release()};wifi?.let{if(it.isHeld)it.release()};multicast?.let{if(it.isHeld)it.release()};getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null);audio.mode=AudioManager.MODE_NORMAL;instance=null;state=JSONObject().put("phase","idle");super.onDestroy()}
    private fun pack(q:JSONObject):String {val out=java.io.ByteArrayOutputStream();DeflaterOutputStream(out).use{it.write(q.toString().toByteArray())};return Base64.encodeToString(out.toByteArray(),Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)}
    private fun unpack(s:String):JSONObject {require(s.length<18000);val stream=InflaterInputStream(java.io.ByteArrayInputStream(Base64.decode(s,Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)));val out=java.io.ByteArrayOutputStream();val b=ByteArray(1024);stream.use{while(true){val n=it.read(b);if(n<0)break;require(out.size()+n<=32768);out.write(b,0,n)}};return JSONObject(out.toString("UTF-8"))}
}
