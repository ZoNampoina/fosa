package com.arizona.fosa.mobile

import android.content.Context
import android.media.MediaRecorder
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
    private val rpc:(String,JSONObject)->JSONObject) {
    data class Link(val pc:PeerConnection,val track:AudioTrack?,var channel:DataChannel?=null,var remote:Boolean=false,val pending:MutableList<IceCandidate> = mutableListOf(),var connected:Boolean=false,var stats:JSONObject=JSONObject(),var received:AudioTrack?=null)
    val links=ConcurrentHashMap<String,Link>()
    @Volatile var error="";private set
    @Volatile var playing=false;private set
    var roster=emptyList<JSONObject>()
    @Volatile var talking=false;private set
    @Volatile var target="all"
    @Volatile var muted=false;private set
    @Volatile var level:Double?=null;private set
    private var master=0.75
    private val memberMutes=ConcurrentHashMap<String,Boolean>()
    private var sampleTime=0L
    private val adm:JavaAudioDeviceModule
    private val factory:PeerConnectionFactory
    private val source:AudioSource?
    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        adm=JavaAudioDeviceModule.builder(ctx).setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION).setSampleRate(48000)
            .setAudioRecordErrorCallback(object:JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(message:String){error="Microphone indisponible : $message";push(false);changed()}
                override fun onWebRtcAudioRecordStartError(code:JavaAudioDeviceModule.AudioRecordStartErrorCode,message:String){error="Microphone arrêté : $message";push(false);changed()}
                override fun onWebRtcAudioRecordError(message:String){error="Capture perdue : $message";push(false);changed()}
            })
            .setAudioTrackStateCallback(object:JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart(){playing=true}
                override fun onWebRtcAudioTrackStop(){playing=false}
            })
            .setAudioTrackErrorCallback(object:JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(message:String){error="Sortie audio indisponible : $message";changed()}
                override fun onWebRtcAudioTrackStartError(code:JavaAudioDeviceModule.AudioTrackStartErrorCode,message:String){error="Sortie audio arrêtée : $message";changed()}
                override fun onWebRtcAudioTrackError(message:String){error="Sortie audio perdue : $message";changed()}
            })
            .setUseStereoInput(false).setUseStereoOutput(false).setUseLowLatency(true).setEnableVolumeLogger(false)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .setSamplesReadyCallback { a ->
                val now=System.nanoTime();if(now-sampleTime>150000000L){sampleTime=now;val data=a.data;val b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);var sum=0.0;var count=0;while(b.remaining()>=2){val v=b.short/32768.0;sum+=v*v;count++};level=if(count>0)max(-120.0,20*log10(max(1e-6,sqrt(sum/count)))) else null}
            }.createAudioDeviceModule()
        factory=PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
        source=if(mic)factory.createAudioSource(MediaConstraints().apply{mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation","true"));mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression","true"));mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl","true"))}) else null
    }
    @Synchronized fun push(active:Boolean,destination:String=target){target=destination;talking=active&&!muted&&source!=null&&error.isEmpty()&&links.any{it.value.connected&&eligible(it.key)};links.forEach{(id,l)->l.track?.setEnabled(talking&&eligible(id))};changed()}
    private fun eligible(id:String):Boolean = target=="all"||target=="user:$id"||target=="leader"&&roster.any{it.optString("id")==id&&it.optBoolean("leader")}||target.startsWith("group:")&&roster.any{it.optString("id")==id&&it.optString("group")==target.removePrefix("group:")}
    @Synchronized fun panic(active:Boolean){muted=active;adm.setSpeakerMute(active);if(active)push(false);changed()}
    @Synchronized fun volume(value:Double){master=value.coerceIn(0.0,1.0);links.values.forEach{it.received?.setVolume(master)}}
    @Synchronized fun memberMute(id:String,active:Boolean){memberMutes[id]=active;links[id]?.received?.setEnabled(!active)}
    @Synchronized fun sync(members:List<JSONObject>) {
        roster=members
        val live=members.filter{it.optString("id")!=self&&it.optBoolean("online")}.map{it.getString("id")}.toSet()
        links.keys.filter{it !in live}.forEach{remove(it)}
        live.forEach{if(!links.containsKey(it)){make(it);if(self<it)offer(it)}}
        push(talking,target)
    }
    @Synchronized private fun make(id:String):Link {
        links[id]?.let{return it}
        val config=PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy=PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy=PeerConnection.TcpCandidatePolicy.DISABLED
        }
        val pc=factory.createPeerConnection(config,object:PeerConnection.Observer {
            override fun onSignalingChange(s:PeerConnection.SignalingState){}
            override fun onIceConnectionChange(s:PeerConnection.IceConnectionState){ links[id]?.connected=s==PeerConnection.IceConnectionState.CONNECTED||s==PeerConnection.IceConnectionState.COMPLETED;changed() }
            override fun onIceConnectionReceivingChange(v:Boolean){}
            override fun onIceGatheringChange(s:PeerConnection.IceGatheringState){}
            override fun onIceCandidate(c:IceCandidate){if(LanAddress.candidate(c.sdp))signal(id,"ice",JSONObject().put("candidate",c.sdp).put("sdpMid",c.sdpMid).put("sdpMLineIndex",c.sdpMLineIndex))}
            override fun onIceCandidatesRemoved(c:Array<out IceCandidate>){}
            override fun onAddStream(s:MediaStream){}
            override fun onRemoveStream(s:MediaStream){}
            override fun onDataChannel(c:DataChannel){wire(id,c)}
            override fun onRenegotiationNeeded(){}
            override fun onAddTrack(r:RtpReceiver,streams:Array<out MediaStream>){(r.track() as? AudioTrack)?.let{links[id]?.received=it;it.setVolume(master);it.setEnabled(memberMutes[id]!=true)}}
        }) ?: error("Audio WebRTC indisponible")
        val track=source?.let{factory.createAudioTrack("mic-$id",it).also{t->t.setEnabled(false);pc.addTrack(t,listOf("fosa"))}}
        if(track==null)pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        val link=Link(pc,track);links[id]=link
        return link
    }
    private fun wire(id:String,c:DataChannel){links[id]?.channel=c;c.registerObserver(object:DataChannel.Observer {
        override fun onBufferedAmountChange(v:Long){}
        override fun onStateChange(){changed()}
        override fun onMessage(b:DataChannel.Buffer){if(b.binary||b.data.remaining()>32768)return;val bytes=ByteArray(b.data.remaining());b.data.get(bytes);try{val q=JSONObject(String(bytes,Charsets.UTF_8));if(q.has("requestId")){val result=try{JSONObject().put("data",rpc(id,q))}catch(e:Exception){JSONObject().put("error",e.message)};result.put("requestId",q.getLong("requestId"));send(c,result)}}catch(_:Exception){} }
    })}
    private fun send(c:DataChannel,j:JSONObject){if(c.state()==DataChannel.State.OPEN)c.send(DataChannel.Buffer(ByteBuffer.wrap(j.toString().toByteArray()),false))}
    private fun observer(created:((SessionDescription)->Unit)?=null,done:(()->Unit)?=null)=object:SdpObserver {
        override fun onCreateSuccess(s:SessionDescription){created?.invoke(s)}
        override fun onSetSuccess(){done?.invoke()}
        override fun onCreateFailure(s:String){changed()}
        override fun onSetFailure(s:String){changed()}
    }
    private fun publish(id:String,type:String,s:SessionDescription){val l=links[id]?:return;l.pc.setLocalDescription(observer(done={signal(id,type,JSONObject().put("sdp",LanAddress.sdp(s.description)).put("type",type))}),s)}
    @Synchronized fun offer(id:String){val l=make(id);if(l.channel==null)wire(id,l.pc.createDataChannel("fosa-mobile",DataChannel.Init()));l.pc.createOffer(observer(created={publish(id,"offer",it)}),MediaConstraints())}
    @Synchronized fun receive(id:String,type:String,data:JSONObject) {
        if(type=="reset"){remove(id);make(id);if(self<id)offer(id);return}
        val l=make(id)
        if(type=="ice") {val value=data.optString("candidate");if(!LanAddress.candidate(value))return;val c=IceCandidate(data.optString("sdpMid"),data.optInt("sdpMLineIndex"),value);if(l.remote)l.pc.addIceCandidate(c) else l.pending.add(c);return}
        if(type !in listOf("offer","answer"))return
        if(type=="answer"&&l.pc.signalingState()==PeerConnection.SignalingState.STABLE)return
        val s=SessionDescription(if(type=="offer")SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER,LanAddress.sdp(data.getString("sdp")))
        l.pc.setRemoteDescription(observer(done={synchronized(this){l.remote=true;l.pending.forEach{l.pc.addIceCandidate(it)};l.pending.clear()};if(type=="offer")l.pc.createAnswer(observer(created={publish(id,"answer",it)}),MediaConstraints())}),s)
    }
    @Synchronized fun stats(){links.values.forEach{l->l.pc.getStats { report->val out=JSONObject();report.statsMap.values.forEach{s->val m=s.members
        if(s.type=="candidate-pair"&&m["state"]=="succeeded"&&m["currentRoundTripTime"] is Number)out.put("rttMs",(m["currentRoundTripTime"] as Number).toDouble()*1000)
        if(s.type=="inbound-rtp"&&m["kind"]=="audio"){(m["jitter"] as? Number)?.let{out.put("jitterMs",it.toDouble()*1000)};val lost=(m["packetsLost"] as? Number)?.toDouble();val rx=(m["packetsReceived"] as? Number)?.toDouble();if(rx!=null)out.put("packetsReceived",rx.toLong());if(lost!=null&&rx!=null&&rx+lost>0)out.put("loss",100*max(0.0,lost)/(rx+lost))}
    };l.stats=out;changed() }}}
    @Synchronized fun reset(){push(false);links.keys.toList().forEach{remove(it)}}
    @Synchronized private fun remove(id:String){links.remove(id)?.let{it.channel?.close();it.channel?.dispose();it.pc.close();it.pc.dispose();it.track?.dispose()}}
    @Synchronized fun close(){reset();source?.dispose();factory.dispose();adm.release()}
}
