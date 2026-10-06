package com.arizona.fosa

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import com.arizona.fosa.mobile.MobileService

/** The two native products must not race for the microphone, route or focus. */
object AudioModeSwitch {
    fun toBodypack(activity:ComponentActivity,ready:()->Unit,failed:(String)->Unit){
        MobileService.instance?.push(false)
        activity.stopService(Intent(activity,MobileService::class.java))
        awaitStopped(activity,{MobileService.instance==null},ready,failed)
    }
    fun toMobile(activity:ComponentActivity,ready:()->Unit,failed:(String)->Unit){
        BodypackService.instance?.muteLocal(true)
        activity.stopService(Intent(activity,BodypackService::class.java))
        awaitStopped(activity,{BodypackService.instance==null},ready,failed)
    }
    private fun awaitStopped(activity:ComponentActivity,stopped:()->Boolean,ready:()->Unit,failed:(String)->Unit){
        val handler=Handler(Looper.getMainLooper());val deadline=SystemClock.elapsedRealtime()+5000
        val check=object:Runnable{override fun run(){
            if(activity.isFinishing||activity.isDestroyed)return
            if(stopped())ready() else if(SystemClock.elapsedRealtime()>=deadline)failed("Le moteur audio précédent ne s’est pas arrêté. Réessaie.") else handler.postDelayed(this,50)
        }}
        handler.post(check)
    }
}
