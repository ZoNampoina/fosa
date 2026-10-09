package com.arizona.fosa.mobile

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/** Direct LAN Opus/SRTP mesh. A separate sender track enforces each PTT destination. */
class RtcMobile(ctx:Context, private val self:String, mic:Boolean,
    private val signal:(String,String,JSONObject)->Unit,
    private val changed:()->Unit,
    private val rpc:(String,JSONObject)->JSONObject, private val noiseReduction:Boolean=true) {
    data class Link(val pc:PeerConnection,var track:AudioTrack?,var generation:Int=1,var channel:DataChannel?=null,var remote:Boolean=false,val pending:MutableList<IceCandidate> = mutableListOf(),val localIce:java.util.concurrent.CopyOnWriteArrayList<IceCandidate> = java.util.concurrent.CopyOnWriteArrayList(),var connected:Boolean=false,var iceState:String="NEW",var localCandidates:Int=0,var remoteCandidates:Int=0,var stats:JSONObject=JSONObject(),var received:AudioTrack?=null,var makingOffer:Boolean=false,var ignoreOffer:Boolean=false,var renegotiate:Boolean=false,var offerEpoch:Int=0,var sendReady:Boolean=false,val operations:java.util.ArrayDeque<()->Unit> = java.util.ArrayDeque(),var operationRunning:Boolean=false)
    val links=ConcurrentHashMap<String,Link>()
    @Volatile var error="";private set
    @Volatile var playing=false;private set
    var roster=emptyList<JSONObject>()
    @Volatile var talking=false;private set
    @Volatile var talkRequested=false;private set
    @Volatile var target="all"
    @Volatile var listenTarget="all";private set
    @Volatile var muted=false;private set
    @Volatile var level:Double?=null;private set
    @Volatile var master=0.75;private set
    private val memberMutes=ConcurrentHashMap<String,Boolean>()
    private val callbacks=Handler(Looper.getMainLooper())
    @Volatile private var closed=false
    // JNI may invoke observers on its signalling thread while a caller holds
    // our lock and waits for that same thread. Never acquire it in an observer.
    private fun defer(action:()->Unit){callbacks.post{if(!closed)action()}}
    private var sampleTime=0L
    val talkControl=TalkControl()
    @Volatile var capturing=false;private set
    @Volatile var testSignalFrames=0L;private set
    @Volatile var captureFrames=0L;private set
    @Volatile var captureNonZeroFrames=0L;private set
    @Volatile var playbackFrames=0L;private set
    @Volatile var playbackNonZeroFrames=0L;private set
    @Volatile var receiveLevel:Double?=null;private set
    @Volatile private var captureAt=0L
    @Volatile private var playbackAt=0L
    @Volatile private var testUntil=0L
    private var testPhase=0.0
    @Volatile private var bufferHasTestSignal=false
    val testing:Boolean get()=testUntil>android.os.SystemClock.elapsedRealtime()
    private fun rms(data:ByteArray):Double {val b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);var sum=0.0;var count=0;while(b.remaining()>=2){val v=b.short/32768.0;sum+=v*v;count++};return if(count>0)max(-120.0,20*log10(max(1e-6,sqrt(sum/count)))) else -120.0}
    fun diagnostics()=JSONObject().put("capture",capturing&&!testing&&android.os.SystemClock.elapsedRealtime()-captureAt<1500).put("captureSource",if(testing)"EXPLICIT TEST SIGNAL" else "MICROPHONE").put("testSignalFrames",testSignalFrames).put("captureFrames",captureFrames).put("captureNonZeroFrames",captureNonZeroFrames).put("micDbfs",level ?: JSONObject.NULL).put("decodedPlaybackFrames",playbackFrames).put("decodedNonZeroFrames",playbackNonZeroFrames).put("rxDbfs",receiveLevel ?: JSONObject.NULL).put("playbackRunning",playing).put("receiving",playing&&android.os.SystemClock.elapsedRealtime()-playbackAt<1500&&(receiveLevel ?: -120.0)>-90).put("testActive",testing).put("armed",talkControl.armed).put("mode",talkControl.mode).put("threshold",talkControl.threshold).put("closeDelayMs",talkControl.closeDelayMs).put("timeoutMs",talkControl.timeoutMs).put("noiseReduction",talkControl.noiseReduction)
    @Synchronized fun heartbeat(){val now=android.os.SystemClock.elapsedRealtime();if(testUntil!=0L&&now>=testUntil){testUntil=0;talkControl.stop()};talkControl.tick(now);talkRequested=testing||talkControl.requested;refreshTalk()}
    @Synchronized fun mode(value:String){stopTest();talkControl.mode(value);talkRequested=false;refreshTalk()}
    @Synchronized fun testAudio(){require(source!=null&&!muted&&roster.find{it.optString("id")==self}?.optBoolean("canTalk",true)!=false){"Microphone autorisé et permission de parole requis"};talkControl.stop();testUntil=android.os.SystemClock.elapsedRealtime()+2000;testPhase=0.0;talkRequested=true;refreshTalk()}
    private fun stopTest(){testUntil=0}
    private val context=ctx
    private val adm:JavaAudioDeviceModule
    private val factory:PeerConnectionFactory
    private var source:AudioSource?
    init {
        val nearby=if(android.os.Build.VERSION.SDK_INT>=33)android.Manifest.permission.NEARBY_WIFI_DEVICES else android.Manifest.permission.ACCESS_FINE_LOCATION
        NetworkMonitorAutoDetect.setIncludeWifiDirect(ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)&&ctx.checkSelfPermission(nearby)==android.content.pm.PackageManager.PERMISSION_GRANTED)
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        adm=JavaAudioDeviceModule.builder(ctx).setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION).setSampleRate(48000)
            .setAudioBufferCallback { buffer, format, channels, rate, bytes, time ->
                // AudioRecord supplies the real-time clock. Disabling it makes this
                // SDK call onBuffer with bytes=0 in a tight loop instead of 10 ms PCM.
                bufferHasTestSignal=testing&&format==android.media.AudioFormat.ENCODING_PCM_16BIT&&bytes>0
                if(bufferHasTestSignal){val b=buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);for(i in 0 until min(bytes,buffer.capacity())/2){val sample=(sin(testPhase)*0.12*32767).toInt().toShort();b.putShort(i*2,sample);if(i%channels==channels-1)testPhase=(testPhase+2*PI*660/rate)%(2*PI)}}
                time
            }
            .setAudioRecordStateCallback(object:JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart(){capturing=true;defer{changed()}}
                override fun onWebRtcAudioRecordStop(){capturing=false;level=null;defer{changed()}}
            })
            .setPlaybackSamplesReadyCallback { a -> playbackFrames+=a.data.size/2;playbackAt=android.os.SystemClock.elapsedRealtime();receiveLevel=rms(a.data);if((receiveLevel ?: -120.0)>-90)playbackNonZeroFrames+=a.data.size/2 }
            .setAudioRecordErrorCallback(object:JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(message:String){error="Microphone indisponible : $message";defer{push(false);changed()}}
                override fun onWebRtcAudioRecordStartError(code:JavaAudioDeviceModule.AudioRecordStartErrorCode,message:String){error="Microphone arrêté : $message";defer{push(false);changed()}}
                override fun onWebRtcAudioRecordError(message:String){error="Capture perdue : $message";defer{push(false);changed()}}
            })
            .setAudioTrackStateCallback(object:JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart(){playing=true;if(error.startsWith("Sortie audio"))error="";changed()}
                override fun onWebRtcAudioTrackStop(){playing=false;changed()}
            })
            .setAudioTrackErrorCallback(object:JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(message:String){error="Sortie audio indisponible : $message";changed()}
                override fun onWebRtcAudioTrackStartError(code:JavaAudioDeviceModule.AudioTrackStartErrorCode,message:String){error="Sortie audio arrêtée : $message";changed()}
                override fun onWebRtcAudioTrackError(message:String){error="Sortie audio perdue : $message";changed()}
            })
            .setUseStereoInput(false).setUseStereoOutput(false).setUseLowLatency(true).setEnableVolumeLogger(false)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(noiseReduction&&JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .setSamplesReadyCallback { a ->
                val db=rms(a.data);val test=bufferHasTestSignal;if(!test&&(error.startsWith("Microphone")||error.startsWith("Capture")))error=""
                if(test)testSignalFrames+=a.data.size/2 else {captureAt=android.os.SystemClock.elapsedRealtime();captureFrames+=a.data.size/2;if(db>-90)captureNonZeroFrames+=a.data.size/2}
                val now=System.nanoTime();if(now-sampleTime>100000000L){sampleTime=now;if(!test)level=db;defer{if(!test)talkControl.level(db,android.os.SystemClock.elapsedRealtime());heartbeat()}}
            }.createAudioDeviceModule()
        val localOnly=PeerConnectionFactory.Options().apply{networkIgnoreMask=PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or PeerConnectionFactory.Options.ADAPTER_TYPE_VPN}
        factory=PeerConnectionFactory.builder().setOptions(localOnly).setAudioDeviceModule(adm).createPeerConnectionFactory()
        source=if(mic)createMicrophone() else null;talkControl.noiseReduction=noiseReduction
    }
    private fun createMicrophone()=factory.createAudioSource(MediaConstraints().apply{mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation","true"));mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression",noiseReduction.toString()));mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl","true"))})
    /** Granting permission must keep the live data channel and listening tracks. */
    @Synchronized fun enableMicrophone(){if(source!=null)return;source=createMicrophone();error=""
        links.forEach{(id,l)->val track=factory.createAudioTrack("mic-$id",source!!);track.setEnabled(false);l.track=track;senderIds[id]=l.pc.addTrack(track,listOf("fosa")).id();offer(id)}
        refreshTalk()
    }
    fun microphoneReady():Boolean=source!=null
    @Synchronized private fun refreshTalk(){
        if(roster.find{it.optString("id")==self}?.optBoolean("canTalk",true)==false){talkControl.stop();stopTest();talkRequested=false}
        talking=talkRequested&&!muted&&source!=null&&roster.find{it.optString("id")==self}?.optBoolean("canTalk",true)!=false&&links.any{it.value.connected&&it.value.sendReady&&eligible(it.key)}
        links.forEach{(id,l)->l.track?.setEnabled(talking&&eligible(id))}
        changed()
    }
    @Synchronized fun push(active:Boolean,destination:String=target){target=destination;if(!active)stopTest();talkControl.active(active&&!muted&&source!=null&&roster.find{it.optString("id")==self}?.optBoolean("canTalk",true)!=false,android.os.SystemClock.elapsedRealtime());talkRequested=talkControl.requested;refreshTalk()}
    @Synchronized fun destination(value:String){target=value;refreshTalk()}
    private fun eligible(id:String):Boolean = target=="all"||target=="user:$id"||target=="leader"&&roster.any{it.optString("id")==id&&it.optBoolean("leader")}||target.startsWith("group:")&&roster.any{it.optString("id")==id&&it.optString("group")==target.removePrefix("group:")}
    @Synchronized fun panic(active:Boolean){muted=active;adm.setSpeakerMute(active);if(active){talkControl.stop();stopTest();talkRequested=false};refreshTalk()}
    @Synchronized fun volume(value:Double){master=if(value.isFinite())value.coerceIn(0.0,1.0) else 0.0;links.values.forEach{it.received?.setVolume(master)}}
    fun mutedMembers():List<String> = memberMutes.filter{it.value}.keys.toList()
    private fun listenEligible(id:String):Boolean {val m=roster.find{it.optString("id")==id};return listenTarget=="all"||listenTarget=="user:$id"||listenTarget=="leader"&&m?.optBoolean("leader")==true||listenTarget.startsWith("group:")&&m?.optString("group")==listenTarget.removePrefix("group:")}
    @Synchronized private fun applyListen(){links.forEach{(id,l)->l.received?.setEnabled(memberMutes[id]!=true&&listenEligible(id)&&roster.find{it.optString("id")==self}?.optBoolean("canListen",true)!=false&&roster.find{it.optString("id")==id}?.optBoolean("canTalk",true)!=false)};changed()}
    @Synchronized fun listen(value:String){listenTarget=value;applyListen()}
    @Synchronized fun memberMute(id:String,active:Boolean){memberMutes[id]=active;applyListen()}
    @Synchronized fun sync(members:List<JSONObject>) {
        roster=members
        val live=members.filter{it.optString("id")!=self&&it.optBoolean("online")}.map{it.getString("id")}.toSet()
        links.keys.filter{it !in live}.forEach{remove(it)}
        live.forEach{id->val generation=members.first{it.optString("id")==id}.optInt("generation",1)
            if(links[id]?.generation?.let{it!=generation}==true)remove(id)
            if(!links.containsKey(id)){make(id).generation=generation;if(self<id)offer(id)}}
        refreshTalk();applyListen()
    }
    /** A fresh manual offer replaces the old transport before poll sees its epoch. */
    @Synchronized fun preparePair(id:String,generation:Int){push(false);remove(id);make(id).generation=generation}
    @Synchronized private fun make(id:String):Link {
        links[id]?.let{return it}
        val config=PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy=PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy=PeerConnection.TcpCandidatePolicy.ENABLED
        }
        var observed:Link?=null
        val pc=factory.createPeerConnection(config,object:PeerConnection.Observer {
            override fun onSignalingChange(s:PeerConnection.SignalingState){}
            override fun onIceConnectionChange(s:PeerConnection.IceConnectionState){observed?.takeIf{links[id]===it}?.let{link->link.iceState=s.name;link.connected=s==PeerConnection.IceConnectionState.CONNECTED||s==PeerConnection.IceConnectionState.COMPLETED;defer{if(links[id]===link){if(s in listOf(PeerConnection.IceConnectionState.DISCONNECTED,PeerConnection.IceConnectionState.FAILED,PeerConnection.IceConnectionState.CLOSED)&&talkControl.mode!="HOLD")push(false);refreshTalk()}}} }
            override fun onIceConnectionReceivingChange(v:Boolean){}
            override fun onIceGatheringChange(s:PeerConnection.IceGatheringState){}
            override fun onIceCandidate(c:IceCandidate){if(observed!=null&&links[id]===observed&&LanAddress.candidate(c.sdp)&&LanAddress.localCandidate(context,c.sdp)){observed?.localIce?.add(c);observed?.localCandidates=observed?.localIce?.size ?: 0;changed();signal(id,"ice",JSONObject().put("candidate",c.sdp).put("sdpMid",c.sdpMid).put("sdpMLineIndex",c.sdpMLineIndex))}}
            override fun onIceCandidatesRemoved(c:Array<out IceCandidate>){}
            override fun onAddStream(s:MediaStream){}
            override fun onRemoveStream(s:MediaStream){}
            override fun onDataChannel(c:DataChannel){if(observed!=null&&links[id]===observed)wire(id,c)}
            override fun onRenegotiationNeeded(){defer{observed?.takeIf{links[id]===it}?.let{if(it.renegotiate)offer(id)}}}
            override fun onAddTrack(r:RtpReceiver,streams:Array<out MediaStream>){val track=r.track() as? AudioTrack ?: return;val link=observed ?: return;defer{synchronized(this@RtcMobile){if(links[id]===link){link.received=track;track.setVolume(master);applyListen()}}}}
        }) ?: error("Audio WebRTC indisponible")
        val track=source?.let{factory.createAudioTrack("mic-$id",it).also{t->t.setEnabled(false);senderIds[id]=pc.addTrack(t,listOf("fosa")).id()}}
        if(track==null)pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        val link=Link(pc,track);observed=link;links[id]=link
        return link
    }
    private fun wire(id:String,c:DataChannel){links[id]?.channel=c;c.registerObserver(object:DataChannel.Observer {
        override fun onBufferedAmountChange(v:Long){}
        override fun onStateChange(){changed()}
        override fun onMessage(b:DataChannel.Buffer){if(b.binary||b.data.remaining()>32768)return;val bytes=ByteArray(b.data.remaining());b.data.get(bytes);try{val q=JSONObject(String(bytes,Charsets.UTF_8));if(q.has("requestId")){val result=try{JSONObject().put("data",rpc(id,q))}catch(e:Exception){JSONObject().put("error",e.message)};result.put("requestId",q.getLong("requestId"));send(c,result)}}catch(_:Exception){} }
    })}
    private fun send(c:DataChannel,j:JSONObject){if(c.state()==DataChannel.State.OPEN)c.send(DataChannel.Buffer(ByteBuffer.wrap(j.toString().toByteArray()),false))}
    private fun observer(created:((SessionDescription)->Unit)?=null,done:(()->Unit)?=null,failed:((String)->Unit)?=null)=object:SdpObserver {
        override fun onCreateSuccess(s:SessionDescription){defer{created?.invoke(s)}}
        override fun onSetSuccess(){defer{updateNegotiatedSend();if(error.startsWith("Audio SDP"))error="";done?.invoke();refreshTalk()}}
        override fun onCreateFailure(s:String){defer{error="Audio SDP creation: $s";failed?.invoke(s);changed()}}
        override fun onSetFailure(s:String){defer{error="Audio SDP negotiation: $s";failed?.invoke(s);changed()}}
    }
    private val senderIds=mutableMapOf<String,String>()
    @Synchronized private fun updateNegotiatedSend(){links.forEach{(id,l)->if(l.pc.signalingState()==PeerConnection.SignalingState.STABLE)l.sendReady=l.track!=null&&senderIds[id]?.let{AudioNegotiation.sending(l.pc.localDescription?.description.orEmpty(),l.pc.remoteDescription?.description.orEmpty(),it)}==true}}

    // Hold the operation until its asynchronous SDP callback finishes. A new
    // offer arriving while an answer is being applied is not a second glare.
    @Synchronized private fun enqueue(id:String,l:Link,operation:()->Unit){if(links[id]!==l)return;l.operations.add(operation);advance(id,l)}
    @Synchronized private fun advance(id:String,l:Link){if(links[id]!==l||l.operationRunning)return;val next=l.operations.poll() ?: return;l.operationRunning=true;next()}
    @Synchronized private fun complete(id:String,l:Link){if(links[id]!==l)return;l.operationRunning=false;advance(id,l)}
    @Synchronized private fun publish(id:String,type:String,s:SessionDescription,epoch:Int?=null){val l=links[id]?:return
        if(epoch!=null&&epoch!=l.offerEpoch){complete(id,l);return}
        l.pc.setLocalDescription(observer(done={synchronized(this){if(links[id]===l){l.makingOffer=false;signal(id,type,JSONObject().put("sdp",LanAddress.localSdp(context,s.description)).put("type",type));if(type=="answer"&&l.renegotiate){l.renegotiate=false;offer(id)};complete(id,l)}}},failed={l.makingOffer=false;complete(id,l)}),s)
    }
    private val queuedOffers=mutableSetOf<String>()
    @Synchronized fun offer(id:String){val l=make(id);if(l.makingOffer){l.renegotiate=true;return};if(!queuedOffers.add(id))return;enqueue(id,l){queuedOffers.remove(id)
        if(l.pc.signalingState()!=PeerConnection.SignalingState.STABLE){l.renegotiate=true;complete(id,l)}else{
            l.renegotiate=false;l.makingOffer=true;val epoch=++l.offerEpoch
            if(l.channel==null)wire(id,l.pc.createDataChannel("fosa-mobile",DataChannel.Init()))
            l.pc.createOffer(observer(created={if(links[id]===l&&epoch==l.offerEpoch)publish(id,"offer",it,epoch)},failed={l.makingOffer=false;complete(id,l)}),MediaConstraints())
        }
    }}
    @Synchronized fun receive(id:String,type:String,data:JSONObject) {
        if(type=="reset"){if(talkControl.mode!="HOLD")push(false);remove(id);make(id);if(self<id)offer(id);return}
        val l=make(id)
        if(type=="ice") {if(l.ignoreOffer)return;val value=data.optString("candidate");if(!LanAddress.candidate(value))return;l.remoteCandidates++;val c=IceCandidate(data.optString("sdpMid"),data.optInt("sdpMLineIndex"),value);if(l.remote)l.pc.addIceCandidate(c) else l.pending.add(c);changed();return}
        if(type !in listOf("offer","answer"))return
        enqueue(id,l){
            if(type=="answer"&&l.pc.signalingState()!=PeerConnection.SignalingState.HAVE_LOCAL_OFFER){complete(id,l)}else{
                val collision=type=="offer"&&(l.makingOffer||l.pc.signalingState()!=PeerConnection.SignalingState.STABLE)
                l.ignoreOffer=collision&&self<id
                if(l.ignoreOffer){complete(id,l)}else{
                    val s=SessionDescription(if(type=="offer")SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER,LanAddress.sdp(data.getString("sdp")))
                    fun apply(){if(links[id]!==l)return;l.pc.setRemoteDescription(observer(done={synchronized(this){if(links[id]===l){l.remote=true;l.pending.forEach{l.pc.addIceCandidate(it)};l.pending.clear();if(type=="offer")l.pc.createAnswer(observer(created={if(links[id]===l)publish(id,"answer",it)},failed={complete(id,l)}),MediaConstraints())else{if(l.renegotiate){l.renegotiate=false;offer(id)};complete(id,l)}}}},failed={complete(id,l)}),s)}
                    if(collision){++l.offerEpoch;l.makingOffer=false;l.renegotiate=true
                        if(l.pc.signalingState()==PeerConnection.SignalingState.HAVE_LOCAL_OFFER)l.pc.setLocalDescription(observer(done={apply()},failed={complete(id,l)}),SessionDescription(SessionDescription.Type.ROLLBACK,"")) else apply()
                    }else apply()
                }
            }
        }
    }
    @Synchronized fun stats(){links.forEach{(id,l)->l.pc.getStats { report->defer{synchronized(this@RtcMobile){if(links[id]===l){val out=JSONObject();report.statsMap.values.forEach{s->val m=s.members
        if(s.type=="transport"){m["dtlsState"]?.let{out.put("dtls",it)};m["selectedCandidatePairId"]?.let{pairId->report.statsMap[pairId.toString()]?.let{pair->
            out.put("selectedCandidatePair",pair.id)
            val local=report.statsMap[pair.members["localCandidateId"]?.toString()]?.members
            val remote=report.statsMap[pair.members["remoteCandidateId"]?.toString()]?.members
            for((prefix,candidate) in listOf("local" to local,"peer" to remote))candidate?.let{c->for(k in listOf("address","port","protocol","candidateType"))c[k]?.let{out.put(prefix+k.replaceFirstChar{it.uppercase()},it)}}
            if(local?.get("candidateType") in listOf("relay","srflx")||remote?.get("candidateType") in listOf("relay","srflx"))defer{error="Chemin audio externe refusé";push(false);remove(id);changed()}
        }}}
        if(s.type=="candidate-pair"&&m["state"]=="succeeded"&&m["currentRoundTripTime"] is Number)out.put("rttMs",(m["currentRoundTripTime"] as Number).toDouble()*1000)
        if(s.type=="outbound-rtp"&&m["kind"]=="audio"){m["codecId"]?.let{report.statsMap[it.toString()]?.members?.get("mimeType")?.let{mime->out.put("codec",mime)}};for(k in listOf("packetsSent","bytesSent"))(m[k] as? Number)?.let{out.put(k,it.toLong())}}
        if(s.type=="inbound-rtp"&&m["kind"]=="audio"){m["codecId"]?.let{report.statsMap[it.toString()]?.members?.get("mimeType")?.let{mime->out.put("receiveCodec",mime)}};for(k in listOf("bytesReceived","totalAudioEnergy","totalSamplesReceived","totalSamplesDuration","audioLevel"))(m[k] as? Number)?.let{out.put(k,it)};(m["jitter"] as? Number)?.let{out.put("jitterMs",it.toDouble()*1000)};val lost=(m["packetsLost"] as? Number)?.toDouble();val rx=(m["packetsReceived"] as? Number)?.toDouble();if(rx!=null)out.put("packetsReceived",rx.toLong());if(lost!=null&&rx!=null&&rx+lost>0)out.put("loss",100*max(0.0,lost)/(rx+lost))}
    };out.put("txPacketsDelta",max(0L,out.optLong("packetsSent")-l.stats.optLong("packetsSent"))).put("rxPacketsDelta",max(0L,out.optLong("packetsReceived")-l.stats.optLong("packetsReceived"))).put("signalingState",l.pc.signalingState().name).put("negotiatedSender",l.sendReady).put("senderEnabled",l.track?.enabled() ?: false);l.stats=out;changed() }}}}}}
    @Synchronized fun repair(){talking=false;links.values.forEach{it.track?.setEnabled(false)};links.keys.toList().forEach{remove(it)};changed()}
    @Synchronized fun reset(){talkControl.stop();stopTest();talkRequested=false;talking=false;links.values.forEach{it.track?.setEnabled(false)};links.keys.toList().forEach{remove(it)};changed()}
    @Synchronized private fun remove(id:String){queuedOffers.remove(id);senderIds.remove(id);if(talkControl.mode!="HOLD"){talkControl.stop();stopTest();talkRequested=false};links.remove(id)?.let{it.channel?.close();it.channel?.dispose();it.pc.close();it.pc.dispose();it.track?.dispose()}}
    @Synchronized fun close(){if(closed)return;closed=true;callbacks.removeCallbacksAndMessages(null);reset();source?.dispose();factory.dispose();adm.release()}
}
