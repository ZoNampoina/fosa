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
                        // UiAutomation.takeScreenshot synchronizes input transactions and can
                        // wait for splash removal. screencap reads the compositor without that cycle.
                        val bytes=android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("screencap -p")).use{it.readBytes()}
                        val bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("Display capture unavailable")
                        val logoVisible=(bitmap.width/4 until bitmap.width*3/4 step 8).any{x->
                            (bitmap.height/4 until bitmap.height*3/4 step 8).any{y->
                                val p=bitmap.getPixel(x,y);android.graphics.Color.red(p) in 20..80&&android.graphics.Color.green(p)>190&&android.graphics.Color.blue(p) in 100..180
                            }
                        }
                        val darkBackground=listOf(bitmap.width/5 to bitmap.height/5,bitmap.width*4/5 to bitmap.height/5).all{(x,y)->val p=bitmap.getPixel(x,y);android.graphics.Color.red(p)<40&&android.graphics.Color.green(p)<40&&android.graphics.Color.blue(p)<50}
                        check(logoVisible&&darkBackground){"Actual FOSA splash logo and background must be visible, not the launcher"}
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
        // Wait for HOME to finish before launching. Closing its descriptor early allows
        // the HOME event to arrive after launch and cover FOSA with the launcher.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("input keyevent 3")).use{it.readBytes()}
        Thread.sleep(600)
        app.registerActivityLifecycleCallbacks(cb)
        val launch=Intent.makeMainActivity(android.content.ComponentName(ctx,MobileActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val scenario=ActivityScenario.launch<MobileActivity>(launch)
        try{assertTrue("Android platform splash must be captured",done.await(5,TimeUnit.SECONDS));failure?.let{throw AssertionError(it)}}finally{app.unregisterActivityLifecycleCallbacks(cb);scenario.close()}
    }
}
