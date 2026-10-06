package com.arizona.fosa.mobile

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MobileUiTest {
    private var peer:NativePeerFixture?=null
    @get:Rule val ui=createAndroidComposeRule<MobileActivity>()
    private fun shell(command:String){android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use{it.readBytes()}}
    private fun shot(name:String){ui.waitForIdle();Thread.sleep(300);shell("mkdir -p /sdcard/Download/FOSA-screenshots");shell("screencap -p /sdcard/Download/FOSA-screenshots/$name.png")}
    @Test fun premiumScreensRealHostAndBackground(){
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.RECORD_AUDIO").close()
        inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS").close()
        ui.waitUntil(5000){ui.onAllNodesWithTag("home-create").fetchSemanticsNodes().isNotEmpty()}
        shot("01-home");ui.onNodeWithTag("home-create").performClick();ui.onNodeWithText("Bring your band\ntogether.").assertIsDisplayed();shot("02-create");ui.onNodeWithText("‹ BACK").performClick();ui.onNodeWithTag("home-join").performScrollTo().performClick();ui.onNodeWithText("Enter the code.\nThat\'s all.").assertIsDisplayed();shot("03-join");ui.onNodeWithText("‹ BACK").performClick();ui.onNodeWithTag("home-create").performClick()
        ui.onNodeWithTag("submit-session").performScrollTo().performClick()
        ui.waitUntil(15000){MobileService.state.optBoolean("active")};ui.waitUntil(10000){MobileService.state.optBoolean("controlConnected")}
        peer=NativePeerFixture(ctx,MobileService.state)
        ui.waitUntil(15000){MobileService.state.optJSONArray("metrics")?.let{a->(0 until a.length()).any{a.getJSONObject(it).optBoolean("connected")}}==true}
        shot("04-talk");ui.onNodeWithTag("nav-MEMBERS").performClick();shot("05-members");ui.onNodeWithText("MUTE").performClick();ui.onNodeWithText("UNMUTE").assertExists();ui.onNodeWithTag("nav-STATUS").performClick();ui.onNodeWithTag("nav-MEMBERS").performClick();ui.onNodeWithText("UNMUTE").assertExists();ui.onNodeWithText("UNMUTE").performClick();ui.onNodeWithTag("nav-STATUS").performClick();shot("06-status");assertTrue(MobileService.state.isNull("latency"))
        ui.onNodeWithTag("nav-SETTINGS").performClick();shot("07-settings");ui.activityRule.scenario.onActivity{MobileService.instance!!.volume(.25)};ui.waitUntil(5000){MobileService.state.optDouble("master")==.25};ui.onNodeWithTag("nav-TALK").performClick();ui.onNodeWithTag("nav-SETTINGS").performClick();ui.onNodeWithText("-12.0 dB").assertExists();ui.onNodeWithText("OFFLINE PACKAGE").performScrollTo().performClick();shot("08-offline");ui.onNodeWithText("CHECK OFFLINE READY").performClick();ui.onNodeWithText("APP SHELL · AUDIO · UI · ICONS READY").assertExists()
        ui.onNodeWithText("CLOSE").performScrollTo().performClick();ui.onNodeWithTag("nav-TALK").performClick()
        val packets=peer!!.receivedPackets()
        assertTrue("PTT must not be disabled by a transient control status once microphone is ready",MobileService.state.optBoolean("mic"))
        ui.onNodeWithTag("talk-button").performScrollTo().performTouchInput{down(center)}
        ui.waitUntil(3000){MobileService.state.optBoolean("talk")}
        // A finger moving over the vertical scroller must keep PTT, including
        // through tick/stats-driven recompositions. This was not covered by tap.
        ui.onNodeWithTag("talk-button").performTouchInput{moveBy(androidx.compose.ui.geometry.Offset(0f,-65f))}
        Thread.sleep(1300);assertTrue("Hold must still transmit after finger motion and state updates",MobileService.state.optBoolean("talk"))
        ui.waitUntil(8000){peer!!.receivedPackets()>packets}
        ui.onNodeWithTag("talk-button").performTouchInput{up()};ui.waitUntil(1500){!MobileService.state.optBoolean("talk")};assertFalse(MobileService.state.optBoolean("talk"))
        val peerId=peer!!.id;peer!!.reconnect()
        ui.waitUntil(15000){MobileService.state.optJSONArray("members")?.let{a->a.length()==2&&(0 until a.length()).any{a.getJSONObject(it).optString("id")==peerId&&a.getJSONObject(it).optInt("generation",1)==2}}==true}
        ui.waitUntil(15000){MobileService.state.optJSONArray("metrics")?.optJSONObject(0)?.let{it.optBoolean("connected")&&it.optString("id")==peerId}==true}
        assertEquals(peerId,peer!!.id)
        peer!!.talk(true)
        ui.waitUntil(15000){MobileService.state.optJSONArray("metrics")?.optJSONObject(0)?.optLong("packetsReceived",0)?.let{it>10L}==true}
        val before=MobileService.state.getJSONArray("metrics").getJSONObject(0).optLong("packetsReceived")
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        Thread.sleep(2600);assertNotNull(MobileService.instance)
        val after=MobileService.state.getJSONArray("metrics").getJSONObject(0).optLong("packetsReceived")
        assertTrue("Native audio must keep arriving while the activity is stopped",after>before)
        assertTrue("Actual native AudioTrack must be active",MobileService.state.optBoolean("audioPlayback"))
        peer!!.talk(false)
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        ui.activityRule.scenario.onActivity{it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE};ui.waitForIdle();Thread.sleep(700)
        val navTop=ui.onNodeWithTag("nav-TALK").fetchSemanticsNode().boundsInRoot.top
        for(tag in listOf("talk-button","panic-button")){
            val bounds=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must be fully accessible above navigation in landscape",bounds.top>=0f&&bounds.bottom<=navTop)
        }
        shot("09-landscape")
        ctx.stopService(Intent(ctx,MobileService::class.java))
    }
    @After fun stop(){peer?.close();ui.activity.stopService(Intent(ui.activity,MobileService::class.java))}
}
