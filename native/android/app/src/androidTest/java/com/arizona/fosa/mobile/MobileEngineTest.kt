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
        assertFalse(LanAddress.candidate("candidate:1 1 UDP 1 8.8.8.8 1234 typ srflx"));assertTrue(LanAddress.candidate("candidate:2 1 TCP 1 192.168.43.10 9 typ host tcptype active"));assertTrue(LanAddress.candidate("candidate:3 1 UDP 1 fd12::2 5555 typ host"));assertFalse(LanAddress.privateV4("8.8.8.8"));assertTrue(LanAddress.privateV4("192.168.43.1"));assertTrue(LanAddress.privateV6("fd12::2"))
    }
    @Test fun privateIdentityResumesWithoutMergingNames(){
        var now=1000L;val room=LanSession("LIVE","Zo","SAX"){now};val owner=room.ticket(room.owner).getString("token")
        val key="a".repeat(32);val join=JSONObject().put("code",room.code).put("name","JOHN").put("role","DRUMS").put("clientKey",key)
        val first=room.call("join",join);val id=first.getString("id");val token=first.getString("token")
        room.call("group",JSONObject().put("id",id).put("group","DRUMS"),owner)
        room.call("poll",JSONObject().put("talk",true).put("level",-12),token)
        room.call("signal",JSONObject().put("to",room.owner).put("type","ice").put("data",JSONObject()),token)
        now+=8000
        val resumed=room.call("join",join.put("resumeToken",token))
        assertEquals(id,resumed.getString("id"));assertEquals(token,resumed.getString("token"));assertEquals(2,resumed.getInt("generation"));assertEquals("DRUMS",resumed.getString("group"));assertFalse(resumed.getBoolean("talk"))
        val public=room.call("poll",JSONObject(),owner);assertEquals(2,public.getJSONArray("members").length());assertEquals(0,public.getJSONArray("signals").length())
        for(i in 0 until public.getJSONArray("members").length()){val u=public.getJSONArray("members").getJSONObject(i);assertFalse(u.has("token"));assertFalse(u.has("clientKey"))}
        val other=room.call("join",JSONObject().put("code",room.code).put("name","JOHN").put("role","DRUMS").put("clientKey","b".repeat(32)))
        assertNotEquals("Two real devices may have the same name",id,other.getString("id"))
        now+=61000
        room.call("join",JSONObject().put("code",room.code).put("name","NEW DEVICE")) // retire stale active slots
        val later=room.call("join",join);assertEquals(id,later.getString("id"));assertEquals("DRUMS",later.getString("group"))
        assertEquals(3,room.call("poll",JSONObject(),owner).getJSONArray("members").length())
        room.call("leave",JSONObject(),token)
        assertNotEquals("Explicit leave revokes the old identity",id,room.call("join",join).getString("id"))
        // Lost initial HTTP response: retrying the device key does not add a slot.
        val retried=room.call("join",JSONObject().put("code",room.code).put("clientKey","c".repeat(32)))
        assertEquals(retried.getString("id"),room.call("join",JSONObject().put("code",room.code).put("clientKey","c".repeat(32))).getString("id"))
    }
    @Test fun nativeDirectOpusPttPrivacyPanicAndRecovery(){
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.RECORD_AUDIO").close()
        val messages=ConcurrentLinkedQueue<Triple<String,String,JSONObject>>()
        lateinit var a:RtcMobile;lateinit var b:RtcMobile
        a=RtcMobile(ctx,"a",true,{_,type,data->messages.add(Triple("b",type,data))},{},{_,_->JSONObject()})
        fun newB(mic:Boolean)=RtcMobile(ctx,"b",mic,{_,type,data->messages.add(Triple("a",type,data))},{},{_,_->JSONObject()})
        b=newB(false)
        val members=listOf(JSONObject().put("id","a").put("online",true).put("leader",true),JSONObject().put("id","b").put("online",true).put("group","BAND"))
        var upgradeOfferReceived=false
        fun drain(){while(true){val q=messages.poll()?:break;if(q.first=="a"&&q.second=="offer")upgradeOfferReceived=true;if(q.first=="a")a.receive("b",q.second,q.third)else b.receive("a",q.second,q.third)}}
        try{
            a.push(true,"user:b");assertTrue("PTT intent must be kept before the link exists",a.talkRequested);assertFalse(a.talking)
            a.sync(members);b.sync(members);await("Direct LAN native ICE must connect and activate held PTT"){drain();a.links["b"]?.connected==true&&b.links["a"]?.connected==true&&a.talking}
            assertTrue("Held PTT must enable the sender as soon as ICE connects",a.links.getValue("b").track!!.enabled());a.push(false)
            val listeningPc=b.links.getValue("a").pc;assertNull(b.links.getValue("a").track)
            b.enableMicrophone();await("Permission upgrade must renegotiate without replacing the listening transport"){drain();upgradeOfferReceived&&b.links["a"]?.pc?.signalingState()==org.webrtc.PeerConnection.SignalingState.STABLE&&a.links["b"]?.pc?.signalingState()==org.webrtc.PeerConnection.SignalingState.STABLE}
            assertSame(listeningPc,b.links.getValue("a").pc);b.push(true,"user:a");assertTrue(b.links.getValue("a").track!!.enabled());b.push(false)
            b.push(true,"leader");assertTrue("A member must be able to target the session leader",b.links.getValue("a").track!!.enabled());b.push(false)
            assertNotNull("Receiving track must exist before listen filtering",b.links.getValue("a").received)
            b.memberMute("a",true);assertFalse("Per-member listen mute must disable the received track",b.links.getValue("a").received!!.enabled())
            b.memberMute("a",false);assertTrue("Unmute must restore the received track",b.links.getValue("a").received!!.enabled())
            a.push(true,"user:missing");assertFalse(a.links.getValue("b").track!!.enabled())
            a.push(true,"user:b");assertTrue(a.links.getValue("b").track!!.enabled())
            a.push(false);assertFalse(a.links.getValue("b").track!!.enabled())
            a.push(true,"group:BAND");assertTrue(a.links.getValue("b").track!!.enabled())
            a.panic(true);assertTrue(a.muted);assertFalse(a.talking);assertFalse(a.talkRequested);assertFalse(a.links.getValue("b").track!!.enabled());assertTrue(a.links.getValue("b").connected)
            a.panic(false);a.stats();b.stats();await("Measured RTC metrics must arrive"){a.stats();a.links.getValue("b").stats.has("rttMs")}
            a.reset();b.reset();a.sync(members);b.sync(members);await("Stream must reconnect after reset"){drain();a.links["b"]?.connected==true&&b.links["a"]?.connected==true}
            val old=a.links.getValue("b").pc;b.close();b=newB(true);members[1].put("generation",2)
            a.sync(members);b.sync(members);await("Same identity must get a fresh connected audio transport"){drain();a.links["b"]?.connected==true&&b.links["a"]?.connected==true}
            assertNotSame(old,a.links.getValue("b").pc);assertEquals(2,a.links.getValue("b").generation)
        }finally{a.close();b.close()}
    }
}
