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
    @get:Rule val ui=createAndroidComposeRule<MobileActivity>()
    private fun shot(name:String){ui.waitForIdle();val dir=File(ui.activity.getExternalFilesDir(null),"screenshots");dir.mkdirs();File(dir,"$name.png").outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun premiumScreensRealHostAndBackground(){
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.RECORD_AUDIO").close()
        inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS").close()
        shot("01-home");ui.onNodeWithTag("home-create").performClick();shot("02-create");ui.onNodeWithText("‹ BACK").performClick();ui.onNodeWithTag("home-join").performScrollTo().performClick();shot("03-join");ui.onNodeWithText("‹ BACK").performClick();ui.onNodeWithTag("home-create").performClick()
        ui.onNodeWithTag("submit-session").performScrollTo().performClick()
        ui.waitUntil(15000){MobileService.state.optBoolean("active")};ui.waitUntil(10000){MobileService.state.optBoolean("controlConnected")}
        shot("04-talk");ui.onNodeWithTag("nav-MEMBERS").performClick();shot("05-members");ui.onNodeWithTag("nav-STATUS").performClick();shot("06-status");assertTrue(MobileService.state.isNull("latency"))
        ui.onNodeWithTag("nav-SETTINGS").performClick();shot("07-settings");ui.onNodeWithText("OFFLINE PACKAGE").performScrollTo().performClick();shot("08-offline");ui.onNodeWithText("CHECK OFFLINE READY").performClick();ui.onNodeWithText("APP SHELL · AUDIO · UI · ICONS READY").assertExists()
        ui.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()};ui.onNodeWithTag("nav-TALK").performClick();ui.onNodeWithTag("talk-button").performScrollTo().performTouchInput{down(center);up()};assertFalse(MobileService.state.optBoolean("talk"))
        val before=MobileService.state.optString("phase");ui.activityRule.scenario.onActivity{it.moveTaskToBack(true)};Thread.sleep(1800);assertNotNull(MobileService.instance);assertEquals(before,MobileService.state.optString("phase"))
        ui.activityRule.scenario.onActivity{it.startActivity(Intent(it,MobileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))}
        ui.activityRule.scenario.onActivity{it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE};ui.waitForIdle();Thread.sleep(700);shot("09-landscape")
        ctx.stopService(Intent(ctx,MobileService::class.java))
    }
    @After fun stop(){ui.activity.stopService(Intent(ui.activity,MobileService::class.java))}
}
