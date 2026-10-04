package com.arizona.fosa

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Real service/UDP/AudioTrack on emulator. No physical latency or MR18 assertion. */
@RunWith(AndroidJUnit4::class)
class BodypackLifecycleTest {
    private fun await(message:String, check:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+15000
        while(SystemClock.elapsedRealtime()<end){if(check())return;Thread.sleep(50)}
        fail(message+" · "+BodypackService.instance?.status())
    }
    @Test fun nativePlaybackBackgroundTalkbackAndPanic() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").close()
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        val server=ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))
        val udp=DatagramSocket(0,InetAddress.getByName("127.0.0.1"));udp.soTimeout=500
        val active=AtomicBoolean(true);val talkPackets=AtomicInteger(0)
        val id=ByteArray(8){it.toByte()};val receiveKey=ByteArray(32){(it+1).toByte()};val sendKey=ByteArray(32){(it+2).toByte()}
        fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 255)}
        val endpoint=java.util.concurrent.atomic.AtomicReference<SocketAddress>()
        val httpThread=Thread {
            while(active.get())try {
                server.accept().use { client ->
                    val input=client.getInputStream().bufferedReader();val first=input.readLine() ?: return@use
                    var size=0
                    while(true){val line=input.readLine() ?: break;if(line.isEmpty())break;if(line.startsWith("Content-Length:",true))size=line.substringAfter(':').trim().toInt()}
                    if(size>0){val body=CharArray(size);var offset=0;while(offset<size){val n=input.read(body,offset,size-offset);if(n<0)break;offset+=n}}
                    val result=if(first.contains("/api/native"))JSONObject().put("port",udp.localPort).put("streamId",hex(id)).put("receiveKey",hex(receiveKey)).put("sendKey",hex(sendKey)).put("profile",JSONObject().put("name","EMULATOR TEST")) else JSONObject().put("ok",true).put("talkAllowed",true).put("talkActive",true)
                    val bytes=result.toString().toByteArray()
                    client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()+bytes)
                }
            }catch(_:Exception){}
        }.apply{start()}
        val inputThread=Thread {
            val buffer=ByteArray(1200);val packet=DatagramPacket(buffer,buffer.size)
            while(active.get())try {
                packet.length=buffer.size;udp.receive(packet)
                val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(sendKey,"AES"),GCMParameterSpec(128,buffer.copyOfRange(4,16)));cipher.updateAAD(buffer,0,16)
                val plain=cipher.doFinal(buffer,16,packet.length-16)
                endpoint.set(packet.socketAddress);if(plain[0]=='T'.code.toByte())talkPackets.incrementAndGet()
            }catch(_:Exception){}
        }.apply{start()}
        val outputThread=Thread {
            var seq=0;val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            while(active.get()) {
                val address=endpoint.get()
                if(address!=null)try {
                    val header=ByteBuffer.allocate(16).put("FNA1".toByteArray()).put(id).putInt(seq).array()
                    val payload=ByteBuffer.allocate(985).order(ByteOrder.LITTLE_ENDIAN).put('A'.code.toByte()).put("FLL1".toByteArray()).putInt(seq).putInt(seq*240).putShort(240).put(2).put(0).putInt(48000).putInt(1000)
                    repeat(240){payload.putShort(1000);payload.putShort(-1000)}
                    cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(receiveKey,"AES"),GCMParameterSpec(128,header.copyOfRange(4,16)));cipher.updateAAD(header)
                    val data=header+cipher.doFinal(payload.array());udp.send(DatagramPacket(data,data.size,address));seq++
                }catch(_:Exception){}
                Thread.sleep(5)
            }
        }.apply{start()}
        val scenario=ActivityScenario.launch(BodypackActivity::class.java)
        try {
            scenario.onActivity { it.startForegroundService(Intent(it,BodypackService::class.java).setAction("START").putExtra("server","http://127.0.0.1:${server.localPort}").putExtra("token","test-token")) }
            await("Native packets must reach an actual AudioTrack") { val s=BodypackService.instance?.status();s?.optBoolean("playback")==true && s.optLong("packets")>30 }
            val service=BodypackService.instance!!
            scenario.onActivity { service.armMic() }
            await("Native microphone should arm") { service.micArmed }
            service.talk=true
            await("Talkback UDP must remain concurrent with playback") { talkPackets.get()>5 && service.status().optBoolean("playback") }
            service.talk=false
            scenario.onActivity { it.moveTaskToBack(true) }
            val before=service.status().getLong("packets");Thread.sleep(1600)
            assertTrue("Background audio must keep receiving",service.status().getLong("packets")>before+100)
            service.muteLocal(true)
            assertTrue(service.status().getBoolean("panic"));assertFalse(service.talk)
            assertTrue("Mute must preserve connection",service.status().getBoolean("playback"))
            service.muteLocal(false);assertFalse(service.status().getBoolean("panic"))
            service.disableMic()
            assertFalse(service.micArmed)
            assertEquals("UNKNOWN",service.status().getString("latencyMethod"))
        } finally {
            context.stopService(Intent(context,BodypackService::class.java));scenario.close()
            active.set(false);server.close();udp.close();httpThread.join(1000);inputThread.join(1000);outputThread.join(1000)
        }
    }
}
