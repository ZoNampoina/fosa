package com.arizona.fosa.mobile

import android.Manifest
import android.net.http.SslError
import android.os.SystemClock
import android.webkit.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real native ↔ embedded Web application over local HTTPS and Opus.
 * This emulator test explicitly trusts its disposable test server certificate;
 * production still requires the user to install/verify the host's local CA. */
@RunWith(AndroidJUnit4::class)
class HybridAudioTest {
    @Test fun nativeAndProductionWebDecodeAudioOverLocalHttps(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val ctx=instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} ${Manifest.permission.RECORD_AUDIO}").close()
        val ip=LanAddress.ip(ctx) ?: error("Private emulator interface required")
        val room=LanSession("HYBRID AUDIO","NATIVE","HOST")
        val token=room.ticket(room.owner).getString("token")
        val tls=LanTls(ctx,listOf(ip));val server=LanHttp("0.0.0.0",room,ctx=ctx,ca=tls.ca,factory=tls.factory)
        val engine=RtcMobile(ctx,room.owner,true,{to,type,data->room.call("signal",JSONObject().put("to",to).put("type",type).put("data",data),token)},{},{id,q->room.call(q.getString("path"),q.optJSONObject("body") ?: JSONObject(),room.ticket(id).getString("token"))})
        val worker=Executors.newSingleThreadScheduledExecutor();var ack=0L
        worker.scheduleWithFixedDelay({try{val q=room.call("poll",JSONObject().put("after",ack).put("talk",engine.talking),token);val m=q.getJSONArray("members");engine.sync((0 until m.length()).map{m.getJSONObject(it)});val signals=q.getJSONArray("signals");for(i in 0 until signals.length()){val s=signals.getJSONObject(i);engine.receive(s.getString("from"),s.getString("type"),s.getJSONObject("data"));ack=maxOf(ack,s.getLong("seq"))};engine.heartbeat();engine.stats()}catch(_:Exception){}},0,150,TimeUnit.MILLISECONDS)
        val scenario=ActivityScenario.launch(MobileActivity::class.java)
        var web:WebView?=null
        fun js(script:String):String {val latch=java.util.concurrent.CountDownLatch(1);var result="null";instrumentation.runOnMainSync{web!!.evaluateJavascript(script){result=it;latch.countDown()}};assertTrue(latch.await(5,TimeUnit.SECONDS));return result}
        fun await(label:String,check:()->Boolean){val end=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<end){if(check())return;Thread.sleep(150)};fail("$label; native ${engine.diagnostics()} ${engine.error}; Web ${js("JSON.stringify(window.fosaMobile?.diagnostics())")}")}
        try{
            scenario.onActivity{activity->val view=WebView(activity);web=view;view.settings.javaScriptEnabled=true;view.settings.domStorageEnabled=true;view.settings.mediaPlaybackRequiresUserGesture=false
                view.webChromeClient=object:WebChromeClient(){override fun onPermissionRequest(request:PermissionRequest){request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))}}
                view.webViewClient=object:WebViewClient(){override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:SslError){handler.proceed()}}
                activity.setContentView(view);view.loadUrl("https://$ip:${server.port}/?c=${room.code}&name=WEB&join=1")}
            await("Production Web must join authenticated local HTTPS and connect native SRTP"){js("!!window.fosaMobile?.directReady()")=="true"&&engine.links.values.any{it.connected}}
            js("fosaMobile.setMode('TAP');fosaMobile.enableAudio();true")
            val nativeBefore=engine.playbackNonZeroFrames
            js("fosaMobile.testAudio();true")
            await("Web encoder must reach the actual native decoder PCM callback"){engine.playbackNonZeroFrames>nativeBefore+4800}
            engine.testAudio()
            await("Native encoder must reach production Web decoded PCM and RTP energy"){js("fosaMobile.measureMic();[...fosaMobile.links.values()].some(l=>l.receiveLevel>-65 && l.metrics.bytesReceived>0 && l.metrics.totalAudioEnergy>0)")=="true"}
            await("Short explicit test must finish without rearming TAP"){js("!fosaMobile.testTone && !fosaMobile.talkControl.armed && !fosaMobile.talkRequested")=="true"&&!engine.testing}
            assertEquals(2,room.call("poll",JSONObject(),token).getJSONArray("members").length())
            android.util.Log.i("FOSA_AUDIO_QA","FOSA_HYBRID_PCM ${engine.diagnostics()} WEB ${js("JSON.stringify(fosaMobile.diagnostics())")}")
        }finally{worker.shutdownNow();instrumentation.runOnMainSync{web?.let{it.loadUrl("about:blank");it.destroy()}};scenario.close();engine.close();server.close()}
    }
}
