package com.arizona.fosa.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.*
import java.security.KeyStore
import javax.net.ssl.*

@RunWith(AndroidJUnit4::class)
class LocalPairingTest {
    private val ctx get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun localInterfaceSelectionNeverUsesDefaultWifiForUnrelatedPeer(){
        assertNull("A Wi-Fi default route cannot be used for a P2P or public peer",LanAddress.forHost(ctx,"8.8.8.8"))
        assertNull(LanAddress.sourceForPeer("127.0.0.1"))
        val local=LanAddress.ip(ctx)
        assertNotNull("Emulator must provide its real private interface",local)
        assertEquals(local,LanAddress.sourceForPeer(local!!))
    }
    private fun post(origin:String,path:String,q:JSONObject=JSONObject(),token:String=""):JSONObject{val c=URL("$origin/lan/$path").openConnection() as HttpURLConnection;c.requestMethod="POST";c.doOutput=true;c.readTimeout=3000;if(token.isNotBlank())c.setRequestProperty("Authorization","Bearer $token");try{c.outputStream.use{it.write(q.toString().toByteArray())};return JSONObject(c.inputStream.bufferedReader().use{it.readText()})}finally{c.disconnect()}}
    @Test fun realHttpChallengeResumePermissionsAndNoTokenInRoster(){
        val room=LanSession("BAND LIVE","ZO","HOST");val server=LanHttp("127.0.0.1",room)
        try{val origin="http://127.0.0.1:${server.port}";val info=post(origin,"info");assertEquals(2,info.getInt("protocolVersion"));assertFalse(info.getBoolean("internetRequired"));assertFalse(info.has("token"))
            val nonce=post(origin,"challenge").getString("nonce");val body=JSONObject().put("code",room.code).put("session",room.id).put("nonce",nonce).put("protocolVersion",2).put("name","JOHN").put("clientKey","a".repeat(32))
            val first=post(origin,"join",body);val token=first.getString("token");assertTrue(token.length>=64)
            try{room.call("join",body,remote="127.0.0.1");fail("Nonce replay must fail")}catch(_:IllegalArgumentException){}
            val resumed=post(origin,"join",body.put("nonce",post(origin,"challenge").getString("nonce")).put("resumeToken",token));assertEquals(first.getString("id"),resumed.getString("id"));assertEquals(2,resumed.getInt("generation"))
            val owner=room.ticket(room.owner).getString("token");room.call("permissions",JSONObject().put("id",first.getString("id")).put("canTalk",false),owner)
            val members=post(origin,"poll",JSONObject().put("talk",true),token).getJSONArray("members");assertEquals(2,members.length());assertFalse(members.getJSONObject(1).getBoolean("talk"));for(i in 0 until members.length()){assertFalse(members.getJSONObject(i).has("token"));assertFalse(members.getJSONObject(i).has("clientKey"))}
        }finally{server.close()}
    }
    @Test fun shortQrRoundTripAndOneScanWebDevice(){
        val room=LanSession("LIVE","ZO","HOST");val qr=PairingQr.session(room.id,room.code,"http://192.168.49.1:48765")
        assertTrue(qr.length<150);assertFalse(qr.contains("sdp",true));assertFalse(qr.contains("ice",true));assertEquals(room.id,PairingQr.parse(qr).getQueryParameter("s"))
        val matrix=QRCodeWriter().encode(qr,BarcodeFormat.QR_CODE,256,256);val pixels=IntArray(256*256){i->if(matrix[i%256,i/256])android.graphics.Color.BLACK else android.graphics.Color.WHITE}
        assertEquals(qr,QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(256,256,pixels)))).text)
        val device=room.call("device-register",JSONObject());assertTrue(device.getString("qr").length<150)
        val admit=JSONObject().put("id",device.getString("id")).put("nonce",device.getString("nonce"))
        assertFalse(room.call("device-poll",admit).getBoolean("approved"));room.call("device-admit",admit,room.ticket(room.owner).getString("token"));assertTrue(room.call("device-poll",admit).getBoolean("approved"))
        try{room.call("device-admit",admit,room.ticket(room.owner).getString("token"));fail("Second scan must not admit again")}catch(_:IllegalArgumentException){}
    }
    @Test fun realWifiQrEscapingAndSessionSeparation(){
        val ssid="FOSA; Local: \"Band\",\\";val password="a;:b,\\c\"12345"
        val payload=WifiQr.encode(ssid,password)
        val matrix=QRCodeWriter().encode(payload,BarcodeFormat.QR_CODE,384,384);val pixels=IntArray(384*384){i->if(matrix[i%384,i/384])android.graphics.Color.BLACK else android.graphics.Color.WHITE}
        val decoded=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(384,384,pixels))))
        val wifi=com.google.zxing.client.result.ResultParser.parseResult(decoded) as com.google.zxing.client.result.WifiParsedResult
        assertEquals(ssid,wifi.ssid);assertEquals(password,wifi.password);assertEquals("WPA",wifi.networkEncryption)
        assertFalse(payload.startsWith("fosa:"));assertTrue(payload.startsWith("WIFI:"))
    }
    @Test fun bundledWebAndRealLocalTlsWithoutInternet(){
        val room=LanSession("LIVE","ZO","HOST");val tls=LanTls(ctx,listOf("192.168.49.1"));val server=LanHttp("127.0.0.1",room,ctx=ctx,ca=tls.ca,factory=tls.factory)
        val trust=KeyStore.getInstance(KeyStore.getDefaultType()).apply{load(null);setCertificateEntry("host",java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(tls.ca.inputStream()))}
        val tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply{init(trust)}
        val ssl=SSLContext.getInstance("TLS").apply{init(null,tm.trustManagers,null)}
        try{for(asset in listOf("","app.js","core.js","connection.js","talk.js","meter-worklet.js","qr.js","style.css","icon.svg","sw.js","manifest.json")){
            val c=URL("https://localhost:${server.port}/$asset").openConnection() as HttpsURLConnection;c.sslSocketFactory=ssl.socketFactory;c.readTimeout=4000
            val bytes=c.inputStream.use{it.readBytes()};assertTrue("Bundled $asset must load over actual TLS",bytes.isNotEmpty());assertEquals(200,c.responseCode);c.disconnect()
        }
        val second=LanTls(ctx,listOf("192.168.43.1"));assertArrayEquals("CA identity persists across local IP changes",tls.ca,second.ca)
        }finally{server.close()}
    }
}
