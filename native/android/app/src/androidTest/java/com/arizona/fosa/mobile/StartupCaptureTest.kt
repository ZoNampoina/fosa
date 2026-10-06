package com.arizona.fosa.mobile

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Capture the real branded application startup UI while its animation clock is paused. */
@RunWith(AndroidJUnit4::class)
class StartupCaptureTest {
    @get:Rule val ui=createEmptyComposeRule()
    @Test fun actualBrandedSplash(){
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        ctx.stopService(Intent(ctx,MobileService::class.java))
        val deadline=System.currentTimeMillis()+5000
        while(MobileService.instance!=null&&System.currentTimeMillis()<deadline)Thread.sleep(25)
        assertNull(MobileService.instance)
        ui.mainClock.autoAdvance=false
        val scenario=ActivityScenario.launch<MobileActivity>(Intent(ctx,MobileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            ui.mainClock.advanceTimeByFrame()
            val shownDeadline=System.currentTimeMillis()+5000
            var shown=false
            while(!shown&&System.currentTimeMillis()<shownDeadline){
                try{ui.onNodeWithTag("splash-screen").assertIsDisplayed();shown=true}catch(_:AssertionError){Thread.sleep(50)}
            }
            assertTrue("Splash screen did not become visible",shown)
            ui.onNodeWithText("NETWORK AUDIO SYSTEM").assertExists()
            var bitmap:Bitmap?=null
            val captureDeadline=System.currentTimeMillis()+3000
            while(bitmap==null&&System.currentTimeMillis()<captureDeadline){
                try{bitmap=ui.onNodeWithTag("splash-screen").captureToImage().asAndroidBitmap()}catch(_:IllegalArgumentException){Thread.sleep(75)}
            }
            val captured=bitmap ?: error("Splash window surface unavailable")
            assertTrue(captured.width>0&&captured.height>0)
            val values=android.content.ContentValues().apply{put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"00-splash.png");put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"image/png");put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/FOSA-screenshots")}
            val uri=ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: error("Capture storage unavailable")
            ctx.contentResolver.openOutputStream(uri)!!.use{captured.compress(Bitmap.CompressFormat.PNG,100,it)}
            ui.mainClock.autoAdvance=true
            ui.mainClock.advanceTimeBy(FosaMotion.StartupMs+32)
            ui.onNodeWithTag("home-create").assertIsDisplayed()
        }finally{ui.mainClock.autoAdvance=true;scenario.close()}
    }
}
