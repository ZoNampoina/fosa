package com.arizona.fosa.mobile

import android.content.Context
import org.json.JSONObject
import java.net.*
import java.util.concurrent.*

/** Second actual native WebRTC client on the emulator, using production HTTP coordinator. */
class NativePeerFixture(ctx:Context,status:JSONObject) {
    private val address=status.getString("address")
    private val owner=status.getString("id")
    private val loop=Executors.newSingleThreadScheduledExecutor()
    private var ack=0L
    private val profile=request("join",JSONObject().put("code",status.getString("code")).put("name","TEST PEER").put("role","NATIVE EMULATOR").put("client","Instrumented native"))
    private val engine=RtcMobile(ctx,profile.getString("id"),true,{to,type,data->if(!loop.isShutdown)loop.execute{request("signal",JSONObject().put("to",to).put("type",type).put("data",data),profile.getString("token"))}},{},{_,_->JSONObject()})
    init{loop.scheduleWithFixedDelay({try{val q=request("poll",JSONObject().put("after",ack).put("talk",engine.talking).put("target",engine.target),profile.getString("token"));val members=q.getJSONArray("members");engine.sync((0 until members.length()).map{members.getJSONObject(it)});val signals=q.getJSONArray("signals");for(i in 0 until signals.length()){val s=signals.getJSONObject(i);engine.receive(s.getString("from"),s.getString("type"),s.getJSONObject("data"));ack=maxOf(ack,s.getLong("seq"))}}catch(_:Exception){}},0,200,TimeUnit.MILLISECONDS)}
    private fun request(path:String,b:JSONObject,token:String=""):JSONObject {val c=URL("$address/lan/$path").openConnection() as HttpURLConnection;c.requestMethod="POST";c.doOutput=true;c.connectTimeout=1500;c.readTimeout=1500;c.setRequestProperty("Content-Type","application/json");if(token.isNotEmpty())c.setRequestProperty("Authorization","Bearer $token");try{c.outputStream.use{it.write(b.toString().toByteArray())};return JSONObject(c.inputStream.bufferedReader().use{it.readText()})}finally{c.disconnect()}}
    fun talk(active:Boolean){engine.push(active,"user:$owner")}
    fun close(){engine.push(false);loop.shutdownNow();engine.close()}
}
