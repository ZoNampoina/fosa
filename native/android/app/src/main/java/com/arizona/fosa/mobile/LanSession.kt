package com.arizona.fosa.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** FOSA LAN/1. Coordinator only: never forwards the voice stream. */
class LanSession(val name:String, val ownerName:String, val ownerRole:String, private val clock:()->Long={System.currentTimeMillis()}) {
    val id=UUID.randomUUID().toString()
    val code=(100000+SecureRandom().nextInt(900000)).toString()
    val owner=UUID.randomUUID().toString()
    private var serial=0L
    private val users=linkedMapOf<String,JSONObject>()
    // Keep disconnected identities separately from the eight active slots. Only
    // their private credentials can restore them; names are never identifiers.
    private val retired=linkedMapOf<String,JSONObject>()
    private val messages=mutableMapOf<String,MutableList<JSONObject>>()
    private val failures=mutableMapOf<String,Pair<Long,Int>>()
    private val challenges=linkedMapOf<String,Pair<String,Long>>()
    private val devices=linkedMapOf<String,JSONObject>()
    var transportLabel="Local Wi-Fi"
    @Synchronized fun metadata()=JSONObject().put("protocol","FOSA-LAN/2").put("protocolVersion",2).put("version",com.arizona.fosa.BuildConfig.VERSION_NAME)
        .put("session",id).put("sessionName",name).put("hostName",ownerName).put("hostRole",ownerRole).put("code",code).put("members",users.size).put("transportLabel",transportLabel).put("internetRequired",false)
    init { users[owner]=user(owner,ownerName,ownerRole,"Native Android"); messages[owner]=mutableListOf() }
    private fun user(id:String,name:String,role:String,client:String)=JSONObject().put("id",id).put("token",UUID.randomUUID().toString()+UUID.randomUUID())
        .put("name",name.take(40).ifBlank{"Musicien"}).put("role",role.take(40)).put("client",client.take(30)).put("group","BAND")
        .put("talk",false).put("canTalk",true).put("canListen",true).put("target","all").put("level",JSONObject.NULL).put("seen",clock()).put("generation",1)
    private fun same(a:String,b:String)=MessageDigest.isEqual(a.toByteArray(),b.toByteArray())
    @Synchronized fun ticket(uid:String)=JSONObject(users.getValue(uid).toString()).put("session",id).put("sessionName",name).put("leader",owner).put("protocol","FOSA-LAN/2")
    @Synchronized fun call(path:String,b:JSONObject,token:String="",remote:String="local"):JSONObject {
        if(path=="info")return metadata().put("tlsPort",48766)
        if(path=="challenge"){val now=clock();challenges.entries.removeAll{now-it.value.second>30000};while(challenges.size>=64)challenges.remove(challenges.keys.first());val nonce=PairingQr.nonce();challenges[nonce]=remote to now;return JSONObject().put("nonce",nonce).put("session",id)}
        if(path=="device-register"){devices.entries.removeAll{clock()>it.value.getLong("expires")};require(devices.size<32){"Trop d’invitations en attente"};val device=PairingQr.nonce().take(12);val nonce=PairingQr.nonce();devices[device]=JSONObject().put("nonce",nonce).put("expires",clock()+90000).put("approved",false);return JSONObject().put("id",device).put("nonce",nonce).put("qr",PairingQr.device(device,nonce))}
        if(path=="device-poll"){val d=devices[b.optString("id")] ?: throw IllegalArgumentException("Invitation expirée");require(clock()<d.getLong("expires")&&same(b.optString("nonce"),d.getString("nonce"))){"Invitation incorrecte ou expirée"};return JSONObject().put("approved",d.optBoolean("approved")).also{if(d.optBoolean("approved"))it.put("code",code).put("session",id).put("joinToken",d.getString("joinToken"))}}
        if(path in listOf("join","auth","reconnect")) {
            val now=clock();val f=failures[remote]
            if(f!=null && now-f.first<60000 && f.second>=8)throw IllegalArgumentException("Trop de codes incorrects. Attends une minute.")
            if(!same(b.optString("code"),code) || (b.has("session") && b.optString("session").isNotBlank() && b.optString("session")!=id)) {
                failures[remote]=Pair(now,if(f!=null&&now-f.first<60000)f.second+1 else 1);throw IllegalArgumentException("Code session incorrect")
            }
            val resume=b.optString("resumeToken");val key=b.optString("clientKey")
            if(b.optInt("protocolVersion",1)>=2){val challenge=challenges.remove(b.optString("nonce"));require(challenge!=null&&challenge.first==remote&&now-challenge.second<30000){"Invitation expirée : réessaie"}}
            if(b.has("joinToken")){val device=devices.entries.find{it.value.optBoolean("approved")&&now<it.value.getLong("expires")&&same(it.value.optString("joinToken"),b.getString("joinToken"))} ?: throw IllegalArgumentException("Invitation déjà utilisée ou expirée");devices.remove(device.key)}
            require(key.isEmpty()||key.matches(Regex("[A-Za-z0-9_-]{32,128}"))){"Identité appareil invalide"}
            val previous=(users.values+retired.values).find{it.getString("id")!=owner&&
                (resume.isNotBlank()&&same(it.getString("token"),resume)||key.isNotBlank()&&same(it.optString("clientKey"),key))}
            val expired=users.filter{it.key!=owner&&it.value!==previous&&now-it.value.getLong("seen")>60000&&now>=it.value.optLong("pairUntil",0)}.keys.toList()
            expired.forEach{uid->retired[uid]=users.remove(uid)!!;messages.remove(uid)}
            while(retired.size>64)retired.remove(retired.keys.first())
            if(previous!=null){
                val uid=previous.getString("id")
                if(uid !in users&&users.size>=8)throw IllegalArgumentException("Session complète : 8 membres maximum")
                retired.remove(uid);users[uid]=previous
                previous.put("name",b.optString("name",previous.getString("name")).take(40)).put("role",b.optString("role",previous.getString("role")).take(40))
                    .put("client",b.optString("client",previous.getString("client")).take(30)).put("seen",now).put("talk",false).put("level",JSONObject.NULL)
                    .put("generation",previous.optInt("generation",1)+1).put("pairUntil",0)
                if(key.isNotBlank())previous.put("clientKey",key)
                messages[uid]=mutableListOf();messages.values.forEach{queue->queue.removeAll{it.optString("from")==uid}}
                return ticket(uid)
            }
            if(users.size>=8)throw IllegalArgumentException("Session complète : 8 membres maximum")
            val uid=UUID.randomUUID().toString();users[uid]=user(uid,b.optString("name"),b.optString("role"),b.optString("client","Web"));messages[uid]=mutableListOf()
            if(key.isNotBlank())users.getValue(uid).put("clientKey",key)
            return ticket(uid)
        }
        val p=users.values.find{same(it.getString("token"),token)} ?: throw IllegalArgumentException("Session privée : reconnexion requise")
        val uid=p.getString("id");p.put("seen",clock()).put("pairUntil",0)
        when(path) {
            "device-admit" -> {require(uid==owner){"Réservé à l’hôte"};val device=devices[b.optString("id")] ?: throw IllegalArgumentException("Appareil natif à découvrir");require(clock()<device.getLong("expires")&&same(b.optString("nonce"),device.getString("nonce"))&&!device.optBoolean("approved")){"Invitation expirée ou déjà utilisée"};device.put("approved",true).put("joinToken",PairingQr.nonce()+PairingQr.nonce())}
            "poll", "members", "talk", "ping" -> {
                p.put("talk",p.optBoolean("canTalk",true)&&b.optBoolean("talk",false)).put("target",b.optString("target","all"))
                val level=b.optDouble("level",Double.NaN);p.put("level",if(level.isFinite())level.coerceIn(-120.0,0.0) else JSONObject.NULL)
                val ack=b.optLong("after",0);val queue=messages.getValue(uid);queue.removeAll{it.getLong("seq")<=ack}
                val public=users.values.map{u->JSONObject().also{v->for(k in listOf("id","name","role","client","group","level","target","generation","canTalk","canListen"))v.put(k,u.opt(k));v.put("leader",u.getString("id")==owner).put("online",clock()-u.getLong("seen")<7000||clock()<u.optLong("pairUntil",0)).put("talk",u.optBoolean("talk")&&clock()-u.getLong("seen")<1500)}}
                return JSONObject().put("session",id).put("sessionName",name).put("leader",owner).put("members",JSONArray(public)).put("signals",JSONArray(queue)).put("internetRequired",false)
            }
            "signal" -> {
                val to=b.getString("to");if(to==uid||!users.containsKey(to))throw IllegalArgumentException("Membre absent")
                val type=b.getString("type");if(type !in listOf("offer","answer","ice","reset"))throw IllegalArgumentException("Signal invalide")
                val value=b.getJSONObject("data");if(value.toString().length>24000)throw IllegalArgumentException("Signal trop grand")
                val queue=messages.getValue(to);if(queue.size>=96)queue.removeAt(0)
                queue.add(JSONObject().put("seq",++serial).put("from",uid).put("generation",p.optInt("generation",1)).put("type",type).put("data",value))
            }
            "group" -> { if(uid!=owner)throw IllegalArgumentException("Réservé au chef de session");users.getValue(b.getString("id")).put("group",b.optString("group","BAND").take(30)) }
            "permissions" -> {require(uid==owner){"Réservé au chef de session"};val member=users.getValue(b.getString("id"));member.put("canTalk",b.optBoolean("canTalk",true)).put("canListen",b.optBoolean("canListen",true));if(!member.optBoolean("canTalk"))member.put("talk",false)}
            "leave" -> {if(uid!=owner){users.remove(uid);retired.remove(uid);messages.remove(uid)}else p.put("talk",false)}
            else -> throw IllegalArgumentException("Commande non disponible")
        }
        return JSONObject().put("ok",true)
    }
    @Synchronized fun reservePair(uid:String){users.getValue(uid).put("pairUntil",clock()+90000)}
    @Synchronized fun touchOwner(talk:Boolean,target:String,level:Double?) { users.getValue(owner).put("seen",clock()).put("talk",talk).put("target",target).put("level",level ?: JSONObject.NULL) }
}
