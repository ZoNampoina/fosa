package com.arizona.fosa

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arizona.fosa.mobile.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual launcher button, Activity, LAN HTTP, WebView and origin bridge. */
@RunWith(AndroidJUnit4::class)
class BodypackUiTest {
    @get:Rule val ui=createAndroidComposeRule<MobileActivity>()
    private var opened:BodypackActivity?=null
    private fun web(view:View):WebView?{if(view is WebView)return view;if(view is ViewGroup)for(i in 0 until view.childCount)web(view.getChildAt(i))?.let{return it};return null}
    private fun javascript(script:String):String?{
        val result=AtomicReference<String>();val done=CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync{web(opened!!.findViewById(android.R.id.content))!!.evaluateJavascript(script){result.set(it);done.countDown()}}
        done.await(1500,TimeUnit.MILLISECONDS);return result.get()
    }
    @Test fun openBodypackConnectsAndFailedLoadsStayActionable(){
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        ctx.getSharedPreferences("bodypack",0).edit().clear().commit()
        inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.RECORD_AUDIO").close()
        inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS").close()
        val nearby=if(android.os.Build.VERSION.SDK_INT>=33)"android.permission.NEARBY_WIFI_DEVICES" else "android.permission.ACCESS_FINE_LOCATION"
        inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} $nearby").close()
        val server=ServerSocket(0);val active=AtomicBoolean(true);val status=AtomicInteger(200)
        val worker=Thread{while(active.get())try{server.accept().use{socket->socket.soTimeout=2000
            val input=socket.getInputStream().bufferedReader();input.readLine();while(true){val line=input.readLine()?:break;if(line.isEmpty())break}
            val bytes="<!doctype html><html><body><h1>FOSA BODYPACK LAN TEST</h1><script>document.title=typeof FosaAndroid==='object'?'NATIVE BRIDGE READY':'BRIDGE MISSING';window.nativeFacts=JSON.parse(FosaAndroid.status());</script></body></html>".toByteArray()
            socket.getOutputStream().write(("HTTP/1.1 ${status.get()} Test\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()+bytes)
        }}catch(_:Exception){}}.apply{start()}
        val ip=LanAddress.ip(ctx) ?: throw AssertionError("Emulator LAN address required")
        val address="http://$ip:${server.localPort}"
        var monitor=inst.addMonitor(BodypackActivity::class.java.name,null,false)
        try{
            ui.waitUntil(5000){ui.onAllNodesWithTag("home-bodypack").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("home-bodypack").performScrollTo().performClick()
            opened=inst.waitForMonitorWithTimeout(monitor,5000) as? BodypackActivity
            assertNotNull("OPEN BODYPACK must launch the native activity",opened)
            ui.onNodeWithTag("bodypack-setup").assertExists()
            ui.onNodeWithTag("bodypack-address").performScrollTo().performTextReplacement(address)
            ui.onNodeWithTag("bodypack-connect").performScrollTo().performClick()
            ui.waitUntil(10000){javascript("document.title") == "\"NATIVE BRIDGE READY\""}
            assertEquals("false",javascript("nativeFacts.connected")) // no simulated READY/audio
            ui.onNodeWithTag("bodypack-connection").performClick();status.set(503)
            ui.onNodeWithTag("bodypack-connect").performScrollTo().performClick()
            ui.waitUntil(10000){ui.onAllNodes(hasTestTag("bodypack-message") and hasText("HTTP 503",substring=true)).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("bodypack-message").performScrollTo().assertIsDisplayed()
            Thread.sleep(6500) // a late discovery timeout must not replace HTTP errors
            ui.onNodeWithText("HTTP 503",substring=true).performScrollTo().assertIsDisplayed()
            ui.onNodeWithTag("bodypack-address").performScrollTo().performTextReplacement("https://example.com")
            ui.onNodeWithTag("bodypack-connect").performScrollTo().performClick()
            ui.onNodeWithText("Adresse LAN privée requise").performScrollTo().assertIsDisplayed()
            val unused=ServerSocket(0).use{it.localPort}
            ui.onNodeWithTag("bodypack-address").performScrollTo().performTextReplacement("http://$ip:$unused")
            ui.onNodeWithTag("bodypack-connect").performScrollTo().performClick()
            ui.waitUntil(10000){ui.onAllNodes(hasText("Serveur inaccessible",substring=true)).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("Serveur inaccessible",substring=true).performScrollTo().assertIsDisplayed()
            ui.onNodeWithTag("bodypack-back").performClick();opened=null
            ui.onNodeWithTag("home-create").performScrollTo().performClick();ui.onNodeWithTag("submit-session").performScrollTo().performClick()
            ui.waitUntil(15000){MobileService.state.optBoolean("controlConnected")}
            assertNotNull(MobileService.instance)
            // waitForMonitorWithTimeout consumes its monitor; register again.
            inst.removeMonitor(monitor);monitor=inst.addMonitor(BodypackActivity::class.java.name,null,false)
            status.set(200);ui.onNodeWithTag("nav-SETTINGS").performClick();ui.onNodeWithTag("settings-bodypack").performScrollTo().performClick()
            opened=inst.waitForMonitorWithTimeout(monitor,5000) as? BodypackActivity
            assertNotNull(opened);ui.waitUntil(10000){MobileService.instance==null}
            ui.waitUntil(10000){javascript("document.title") == "\"NATIVE BRIDGE READY\""}
        }finally{
            opened?.let{activity->inst.runOnMainSync{activity.finish()}}
            ctx.stopService(Intent(ctx,MobileService::class.java));ctx.stopService(Intent(ctx,BodypackService::class.java))
            inst.removeMonitor(monitor);active.set(false);server.close();worker.join(1000)
        }
    }
}
