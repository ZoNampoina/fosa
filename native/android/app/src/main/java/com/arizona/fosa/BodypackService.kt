package com.arizona.fosa

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.net.wifi.WifiManager
import android.os.*
import org.json.JSONObject
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.*

/** PCM UDP playback belongs to this service, never to WebView or its lifecycle. */
class BodypackService : Service() {
    companion object { @Volatile var instance: BodypackService? = null }
    private val running = AtomicBoolean(false)
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile var panic = false
    @Volatile var talk = false
    @Volatile var micArmed = false
    @Volatile var target = "all"
    @Volatile var connected = false
    @Volatile var error = ""
    @Volatile private var server = ""
    @Volatile private var token = ""
    @Volatile private var lastPacket = 0L
    @Volatile private var received = 0L
    @Volatile private var missing = 0L
    @Volatile private var invalid = 0L
    @Volatile private var skipped = 0L
    @Volatile private var jitter = 0.0
    @Volatile private var rtt: Double? = null
    @Volatile private var captureQueue = 0.0
    @Volatile private var ready = false
    @Volatile private var name = "Bodypack"
    @Volatile var targetPackets = 2
    private val ring = arrayOfNulls<ShortArray>(32)
    private val sequences = LongArray(32) { -1 }
    private val ringLock = Object()
    private var next = -1L
    private var latest = -1L
    private var outputKey = ByteArray(0)
    private var inputKey = ByteArray(0)
    private var streamId = ByteArray(0)
    private var sendSequence = 0L
    private var controlSequence = 0L
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) { muteLocal(true); error = "Sortie débranchée : mute local. Vérifie les écouteurs avant de reprendre." }
    }

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("monitoring", "FOSA monitoring", NotificationManager.IMPORTANCE_LOW))
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "MUTE" -> muteLocal(true)
            "STOP" -> { stopSelf(); return START_NOT_STICKY }
            "MIC" -> armMic()
            "START" -> {
                if (running.get()) return START_NOT_STICKY
                server = intent.getStringExtra("server") ?: return START_NOT_STICKY
                token = intent.getStringExtra("token") ?: return START_NOT_STICKY
                targetPackets = (intent.getIntExtra("targetMs",10)/5).coerceIn(1,8)
                foreground(false)
                panic = false; error = ""; running.set(true)
                val am = getSystemService(AudioManager::class.java)
                focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes()).setOnAudioFocusChangeListener({ change ->
                        if (change < 0) { muteLocal(true); error = "Focus audio perdu : mute local" }
                    }, handler).build()
                am.requestAudioFocus(focus!!)
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FOSA:monitoring").apply { acquire() }
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(mode, "FOSA:LAN").apply { acquire() }
                worker = Thread({ supervise() }, "FOSA-session").apply { start() }
            }
        }
        return START_NOT_STICKY // OS restart must never silently reopen a microphone.
    }
    private fun attributes() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private fun foreground(mic: Boolean) {
        val open = PendingIntent.getActivity(this, 1, Intent(this, BodypackActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val mute = PendingIntent.getService(this, 2, Intent(this, BodypackService::class.java).setAction("MUTE"), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 3, Intent(this, BodypackService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "monitoring").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("FOSA MONITORING ACTIVE").setContentText("$name · ${if (panic) "MUTED" else "PCM / LAN"} · latence non mesurée")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "MUTE", mute).build())
            .addAction(Notification.Action.Builder(null, "OPEN", open).build()).addAction(Notification.Action.Builder(null, "STOP", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(31, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        else startForeground(31, notification)
    }
    fun muteLocal(value: Boolean) { panic = value; if (value) talk = false; handler.post { if (running.get()) foreground(micArmed) } }
    fun request(path: String, body: JSONObject? = null): JSONObject {
        val conn = URL("$server/api/$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 2500; conn.readTimeout = 2500
        conn.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (body != null) {
                conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val result = JSONObject(text)
            if (!ok) throw IllegalStateException(result.optString("error", "HTTP ${conn.responseCode}"))
            return result
        } finally { conn.disconnect() }
    }
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun supervise() {
        while (running.get()) {
            var reader: Thread? = null; var player: Thread? = null
            try {
                val session = request("native", JSONObject())
                streamId = hex(session.getString("streamId")); outputKey = hex(session.getString("receiveKey")); inputKey = hex(session.getString("sendKey"))
                name = session.getJSONObject("profile").optString("name", "Bodypack")
                sendSequence = 0; controlSequence = 0; received = 0; missing = 0; invalid = 0; skipped = 0; lastPacket = 0
                synchronized(ringLock) { next = -1; latest = -1; sequences.fill(-1); ring.fill(null); ready = false }
                val udp = DatagramSocket().apply { connect(InetAddress.getByName(URI(server).host), session.getInt("port")); soTimeout = 1000; receiveBufferSize = 64*1024 }
                socket = udp; send(byteArrayOf('H'.code.toByte()))
                reader = Thread({ receive(udp) }, "FOSA-UDP").apply { start() }
                player = Thread({ play(udp) }, "FOSA-output").apply { start() }
                handler.post { foreground(micArmed) }
                var lostSince = SystemClock.elapsedRealtime()
                while (running.get() && socket === udp && !udp.isClosed) {
                    send(byteArrayOf('H'.code.toByte()))
                    val start = SystemClock.elapsedRealtimeNanos()
                    val control = request("control", JSONObject().put("sequence", ++controlSequence).put("talk", talk && micArmed)
                        .put("target", target).put("metrics", status()))
                    rtt = (SystemClock.elapsedRealtimeNanos()-start)/1e6
                    if (!control.optBoolean("talkAllowed")) { talk = false; if (micArmed) disableMic() }
                    val now = SystemClock.elapsedRealtime()
                    connected = lastPacket > 0 && now-lastPacket < 1500
                    if (connected) { lostSince = now; error = "" }
                    else if (now-lostSince > 4000) throw IllegalStateException("Flux UDP perdu : reconnexion")
                    Thread.sleep(500)
                }
            } catch (e: Exception) { if (running.get()) error = e.message ?: "Liaison interrompue" }
            finally {
                connected = false; talk = false; socket?.close(); socket = null
                reader?.join(1200); player?.join(1200)
            }
            if (running.get()) try { Thread.sleep(1000) } catch (_: InterruptedException) { }
        }
    }
    @Synchronized private fun send(body: ByteArray) {
        val udp = socket ?: return
        if (sendSequence >= 0xffffffffL) { udp.close(); return }
        try {
            val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).put("FNA1".toByteArray()).put(streamId).putInt(sendSequence++.toInt()).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(inputKey, "AES"), GCMParameterSpec(128, header.copyOfRange(4, 16)))
            cipher.updateAAD(header)
            val data = header+cipher.doFinal(body)
            udp.send(DatagramPacket(data, data.size))
        } catch (_: Exception) { }
    }
    private fun receive(udp: DatagramSocket) {
        val bytes = ByteArray(1200); val packet = DatagramPacket(bytes, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        var previousTime = 0L; var previousSequence = -1L
        var highest = -1L; var replayMask = 0L
        while (running.get() && !udp.isClosed) try {
            packet.length = bytes.size; udp.receive(packet)
            if (packet.length != 1017 || !bytes.copyOfRange(0,4).contentEquals("FNA1".toByteArray()) || !bytes.copyOfRange(4,12).contentEquals(streamId)) { invalid++; continue }
            val seq = ByteBuffer.wrap(bytes,12,4).int.toLong() and 0xffffffffL
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(outputKey, "AES"), GCMParameterSpec(128, bytes.copyOfRange(4,16)))
            cipher.updateAAD(bytes,0,16)
            val plain = cipher.doFinal(bytes,16,packet.length-16)
            if(seq>highest) {
                val distance=seq-highest
                replayMask=if(distance>=64)1L else (replayMask shl distance.toInt()) or 1L
                highest=seq
            } else {
                val age=highest-seq
                if(age>=64 || (replayMask and (1L shl age.toInt()))!=0L){invalid++;continue}
                replayMask=replayMask or (1L shl age.toInt())
            }
            if (plain[0] != 'A'.code.toByte()) { invalid++; continue }
            val view = ByteBuffer.wrap(plain,1,984).slice().order(ByteOrder.LITTLE_ENDIAN)
            if (view.int != 0x314c4c46 || (view.int.toLong() and 0xffffffffL) != seq) { invalid++; continue }
            view.int
            if (view.short.toInt()!=240 || view.get().toInt()!=2 || view.get().toInt()!=0 || view.int!=48000) { invalid++; continue }
            captureQueue = (view.int.toLong() and 0xffffffffL)/1000.0
            val pcm = ShortArray(480); view.asShortBuffer().get(pcm)
            // Publish liveness before waking playback; otherwise the first valid
            // block can be treated as stale while lastPacket is still zero.
            lastPacket=SystemClock.elapsedRealtime()
            synchronized(ringLock) {
                if (next>=0 && seq<next) { skipped++; return@synchronized }
                val slot = (seq%32).toInt()
                if (sequences[slot]==seq) return@synchronized
                ring[slot] = pcm; sequences[slot]=seq
                if (next<0) next=seq
                latest=max(latest,seq); received++
                if (!ready && latest-next+1>=targetPackets) { ready=true; ringLock.notifyAll() }
            }
            val now = SystemClock.elapsedRealtimeNanos()
            if (previousTime>0 && seq>previousSequence) jitter += (abs((now-previousTime)/1e6-(seq-previousSequence)*5)-jitter)/16
            previousTime=now; previousSequence=seq
        } catch (_: SocketTimeoutException) { } catch (_: Exception) { invalid++ }
    }
    private fun play(udp: DatagramSocket) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var out: AudioTrack? = null
        try {
            val minimum = AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT)
            out = AudioTrack.Builder().setAudioAttributes(attributes()).setAudioFormat(AudioFormat.Builder()
                .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(max(minimum,1920)).build()
            val burst = getSystemService(AudioManager::class.java).getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 240
            out.setBufferSizeInFrames(max(480,burst*2)); track=out
            // AudioTrack accepts an entire hardware buffer immediately on startup.
            // Prime it with silence so this burst cannot consume future network packets.
            val silence=ShortArray(out.bufferSizeInFrames*2)
            out.write(silence,0,silence.size,AudioTrack.WRITE_BLOCKING);out.play()
            val buffer = ShortArray(480)
            var fade = 0f; var left = 0f; var right = 0f
            var wasMuted = false
            var gaps = 0
            while (running.get() && !udp.isClosed) {
                var data: ShortArray? = null
                synchronized(ringLock) {
                    if (!ready) ringLock.wait(10)
                    if (ready) {
                        if (latest-next>targetPackets+4) { skipped+=latest-next-targetPackets; next=latest-targetPackets; fade=0f }
                        val slot=(next%32).toInt()
                        if (sequences[slot]==next) { data=ring[slot]; ring[slot]=null; sequences[slot]=-1; gaps=0 } else { missing++;gaps++ }
                        next++
                        if (gaps>=2 || SystemClock.elapsedRealtime()-lastPacket>100) { ready=false; next=-1; latest=-1; gaps=0; sequences.fill(-1); ring.fill(null) }
                    }
                }
                val mute = panic || !ready || data==null
                // Drain old output on panic; software ramp to silence is <= 64 samples.
                if (panic && !wasMuted) { out.pause(); out.flush(); out.write(silence,0,silence.size,AudioTrack.WRITE_BLOCKING);out.play() }
                wasMuted=panic
                for (i in 0 until 240) {
                    fade=(fade+if(mute)-1f/64 else 1f/64).coerceIn(0f,1f)
                    if(data!=null) { left=data!![i*2].toFloat(); right=data!![i*2+1].toFloat() }
                    buffer[i*2]=(left*fade).toInt().coerceIn(-29204,29204).toShort()
                    buffer[i*2+1]=(right*fade).toInt().coerceIn(-29204,29204).toShort()
                }
                var offset=0
                while(offset<480 && running.get() && !udp.isClosed) {
                    val written=out.write(buffer,offset,480-offset,AudioTrack.WRITE_BLOCKING)
                    if(written<0) throw IllegalStateException("AudioTrack erreur $written")
                    if(written==0) break
                    offset+=written
                }
            }
        } catch(e: Exception) { error="Sortie audio : ${e.message}"; udp.close() }
        finally { try { out?.pause(); out?.flush(); out?.release() } catch(_:Exception){}; if(track===out)track=null }
    }
    fun armMic() {
        if (micArmed || checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) return
        foreground(true); micArmed=true
        Thread({
            var input: AudioRecord?=null
            try {
                val minimum=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                input=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,max(minimum,1920))
                recorder=input; input.startRecording(); val pcm=ByteArray(480); val body=ByteArray(481); body[0]='T'.code.toByte()
                while(running.get() && micArmed) {
                    var offset=0
                    while(offset<480 && micArmed) { val n=input.read(pcm,offset,480-offset); if(n<0)throw IllegalStateException("AudioRecord $n"); offset+=n }
                    if(talk && micArmed && offset==480) { System.arraycopy(pcm,0,body,1,480); send(body) }
                }
            } catch(e:Exception) { error="Micro : ${e.message}" }
            finally { try { input?.stop(); input?.release() } catch(_:Exception){}; recorder=null; micArmed=false; talk=false; handler.post { if(running.get())foreground(false) } }
        },"FOSA-talkback").start()
    }
    fun disableMic() { micArmed=false; talk=false; try { recorder?.stop() } catch(_:Exception){} }
    fun status(): JSONObject {
        val t=track; val route=when(t?.routedDevice?.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED HEADPHONES"
            AudioDeviceInfo.TYPE_USB_DEVICE,AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB AUDIO / DAC"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLUETOOTH — latence supplémentaire"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER — branche les écouteurs"
            else -> "UNKNOWN"
        }
        val depth=synchronized(ringLock) { if(ready)max(0,latest-next+1)*5 else 0 }
        val battery=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject().put("connected",connected).put("playback",t?.playState==AudioTrack.PLAYSTATE_PLAYING)
            .put("error",error).put("panic",panic).put("mic",micArmed).put("talk",talk)
            .put("packets",received).put("missing",missing).put("invalid",invalid).put("skipped",skipped)
            .put("jitter",jitter).put("rtt",rtt ?: JSONObject.NULL).put("buffer",depth)
            .put("loss",if(received+missing>0)100.0*missing/(received+missing) else JSONObject.NULL)
            .put("network","LAN / UDP").put("output",route).put("audioLatency",JSONObject.NULL)
            .put("latencyMethod","UNKNOWN").put("captureQueueMs",captureQueue)
            .put("outputBufferMs",(t?.bufferSizeInFrames ?: 0)/48.0).put("underruns",t?.underrunCount ?: 0)
            .put("lowLatencyMode",t?.performanceMode==AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .put("battery",battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1)
    }
    override fun onDestroy() {
        running.set(false); talk=false; disableMic(); socket?.close(); socket=null
        try { track?.pause(); track?.flush() } catch(_:Exception){}
        synchronized(ringLock) { ringLock.notifyAll() }
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        if(wake?.isHeld==true)wake?.release(); if(wifi?.isHeld==true)wifi?.release()
        unregisterReceiver(noisy); instance=null; super.onDestroy()
    }
}
