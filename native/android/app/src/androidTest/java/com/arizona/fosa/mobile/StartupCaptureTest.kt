package com.arizona.fosa.mobile

import android.app.*
import android.os.*
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Hold the actual platform splash long enough to capture its hardware-rendered icon. */
@RunWith(AndroidJUnit4::class)
class StartupCaptureTest {
    @Test fun actualPlatformSplash(){
        if(Build.VERSION.SDK_INT<31)return
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext;val app=ctx.applicationContext as Application
        val done=CountDownLatch(1);var failure:Throwable?=null
        val cb=object:Application.ActivityLifecycleCallbacks{
            override fun onActivityCreated(a:Activity,b:Bundle?){if(a is MobileActivity)a.splashScreen.setOnExitAnimationListener{screen->
                Thread {
                    try {
                        // Canvas.draw omits the icon's SurfaceView. Capture the real compositor instead.
                        Thread.sleep(300)
                        val bitmap=inst.uiAutomation.takeScreenshot() ?: error("Display capture unavailable")
                        val logoVisible=(bitmap.width/4 until bitmap.width*3/4 step 8).any{x->
                            (bitmap.height/4 until bitmap.height*3/4 step 8).any{y->
                                val p=bitmap.getPixel(x,y);android.graphics.Color.green(p)>100&&android.graphics.Color.green(p)>android.graphics.Color.red(p)*1.3
                            }
                        }
                        check(logoVisible){"Actual splash logo must be visible, not a blank background"}
                        val values=android.content.ContentValues().apply{put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"00-splash.png");put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"image/png");put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/FOSA-screenshots")}
                        val uri=ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: error("Capture storage unavailable")
                        ctx.contentResolver.openOutputStream(uri)!!.use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                    }catch(e:Throwable){failure=e}finally{a.runOnUiThread{screen.remove();done.countDown()}}
                }.start()

            }}
            override fun onActivityStarted(a:Activity){}
            override fun onActivityResumed(a:Activity){}
            override fun onActivityPaused(a:Activity){}
            override fun onActivityStopped(a:Activity){}
            override fun onActivitySaveInstanceState(a:Activity,b:Bundle){}
            override fun onActivityDestroyed(a:Activity){}
        }
        app.registerActivityLifecycleCallbacks(cb);inst.uiAutomation.executeShellCommand("input keyevent 3").close()
        val scenario=ActivityScenario.launch<MobileActivity>(Intent(ctx,MobileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try{assertTrue("Android platform splash must be captured",done.await(5,TimeUnit.SECONDS));failure?.let{throw AssertionError(it)}}finally{app.unregisterActivityLifecycleCallbacks(cb);scenario.close()}
    }
}
