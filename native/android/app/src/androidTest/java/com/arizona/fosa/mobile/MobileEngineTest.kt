package com.arizona.fosa.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import android.os.SystemClock
import java.net.*
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class MobileEngineTest {
    private fun await(label:String,check:()->Boolean){val deadline=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<deadline){if(check())return;Thread.sleep(30)};fail(label)}
    @Test fun coordinatorAuthenticationGroupsUnicodeAndBoundaries(){
        var now=1000L;val room=LanSession("Répétition","Zo","SAX"){now};val owner=room.ticket(room.owner).getString("token")
        try{room.call("join",JSONObject().put("code","000000"));fail("Private session must reject wrong code")}catch(_:IllegalArgumentException){}
        val member=room.call("join",JSONObject().put("code",room.code).put("name","Élodie").put("role","Voix"))
        val token=member.getString("token");val id=member.getString("id")
        try{room.call("group",JSONObject().put("id",id).put("group","VOCALS"),token);fail("Only leader manages groups")}catch(_:IllegalArgumentException){}
        room.call("group",JSONObject().put("id",id).put("group","VOCALS"),owner)
        val roster=room.call("poll",JSONObject().put("talk",true).put("level",-24),token).getJSONArray("members")
        assertEquals("VOCALS",roster.getJSONObject(1).getString("group"));assertFalse(roster.getJSONObject(1).has("token"));assertTrue(roster.getJSONObject(1).getBoolean("talk"))
        now+=2000;assertFalse(room.call("poll",JSONObject(),owner).getJSONArray("members").getJSONObject(1).getBoolean("talk"))
        val http=LanHttp("127.0.0.1",room)
        try{val c=URL("http://127.0.0.1:${http.port}/lan/join").openConnection() as HttpURLConnection;c.requestMethod="POST";c.doOutput=true;c.readTimeout=3000
            c.outputStream.use{it.write(JSONObject().put("code",room.code).put("name","Éléonore").toString().toByteArray())};val response=JSONObject(c.inputStream.bufferedReader().use{it.readText()});assertEquals("Éléonore",response.getString("name"));c.disconnect()
        }finally{http.close()}
        assertFalse(LanAddress.candidate("candidate:1 1 UDP 1 8.8.8.8 1234 typ srflx"));assertFalse(LanAddress.privateV4("8.8.8.8"));assertTrue(LanAddress.privateV4("192.168.43.1"))
    }
    @Test fun nativeDirectOpusPttPrivacyPanicAndRecovery(){
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.RECORD_AUDIO").close()
        val messages=ConcurrentLinkedQueue<Triple<String,String,JSONObject>>()
        lateinit var a:RtcMobile;lateinit var b:RtcMobile
        a=RtcMobile(ctx,"a",true,{_,type,data->messages.add(Triple("b",type,data))},{},{_,_->JSONObject()})
        b=RtcMobile(ctx,"b",true,{_,type,data->messages.add(Triple("a",type,data))},{},{_,_->JSONObject()})
        val members=listOf(JSONObject().put("id","a").put("online",true).put("leader",true),JSONObject().put("id","b").put("online",true).put("group","BAND"))
        fun drain(){while(true){val q=messages.poll()?:break;if(q.first=="a")a.receive("b",q.second,q.third)else b.receive("a",q.second,q.third)}}
        try{a.sync(members);b.sync(members);await("Direct LAN native ICE must connect"){drain();a.links["b"]?.connected==true&&b.links["a"]?.connected==true}
            a.push(true,"user:missing");assertFalse(a.links.getValue("b").track!!.enabled())
            a.push(true,"user:b");assertTrue(a.links.getValue("b").track!!.enabled())
            a.push(false);assertFalse(a.links.getValue("b").track!!.enabled())
            a.push(true,"group:BAND");assertTrue(a.links.getValue("b").track!!.enabled())
            a.panic(true);assertTrue(a.muted);assertFalse(a.talking);assertFalse(a.links.getValue("b").track!!.enabled());assertTrue(a.links.getValue("b").connected)
            a.panic(false);a.stats();b.stats();await("Measured RTC metrics must arrive"){a.stats();a.links.getValue("b").stats.has("rttMs")}
            a.reset();b.reset();a.sync(members);b.sync(members);await("Stream must reconnect after reset"){drain();a.links["b"]?.connected==true&&b.links["a"]?.connected==true}
        }finally{a.close();b.close()}
    }
}
