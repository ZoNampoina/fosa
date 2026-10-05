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

/** Retain the actual Android platform splash briefly for its screenshot, only in this test. */
@RunWith(AndroidJUnit4::class)
class StartupCaptureTest {
    @Test fun actualPlatformSplash(){
        if(Build.VERSION.SDK_INT<31)return
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext;val app=ctx.applicationContext as Application
        val done=CountDownLatch(1);var failure:Throwable?=null
        val cb=object:Application.ActivityLifecycleCallbacks{
            override fun onActivityCreated(a:Activity,b:Bundle?){if(a is MobileActivity)a.splashScreen.setOnExitAnimationListener{screen->
                Thread{try{Thread.sleep(100);android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/FOSA-screenshots")).use{it.readBytes()};android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/FOSA-screenshots/00-splash.png")).use{it.readBytes()}}catch(e:Throwable){failure=e}finally{Handler(Looper.getMainLooper()).post{screen.remove();done.countDown()}}}.start()
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
