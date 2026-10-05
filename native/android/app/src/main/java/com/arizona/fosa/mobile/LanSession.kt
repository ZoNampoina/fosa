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
    private val messages=mutableMapOf<String,MutableList<JSONObject>>()
    private val failures=mutableMapOf<String,Pair<Long,Int>>()
    init { users[owner]=user(owner,ownerName,ownerRole,"Native Android"); messages[owner]=mutableListOf() }
    private fun user(id:String,name:String,role:String,client:String)=JSONObject().put("id",id).put("token",UUID.randomUUID().toString()+UUID.randomUUID())
        .put("name",name.take(40).ifBlank{"Musicien"}).put("role",role.take(40)).put("client",client.take(30)).put("group","BAND")
        .put("talk",false).put("target","all").put("level",JSONObject.NULL).put("seen",clock())
    private fun same(a:String,b:String)=MessageDigest.isEqual(a.toByteArray(),b.toByteArray())
    @Synchronized fun ticket(uid:String)=JSONObject(users.getValue(uid).toString()).put("session",id).put("sessionName",name).put("leader",owner).put("protocol","FOSA-LAN/1")
    @Synchronized fun call(path:String,b:JSONObject,token:String="",remote:String="local"):JSONObject {
        if(path=="info")return JSONObject().put("protocol","FOSA-LAN/1").put("sessionName",name).put("session",id).put("members",users.size).put("internetRequired",false)
        if(path=="join") {
            val now=clock();val f=failures[remote]
            if(f!=null && now-f.first<60000 && f.second>=8)throw IllegalArgumentException("Trop de codes incorrects. Attends une minute.")
            if(!same(b.optString("code"),code) || (b.has("session") && b.optString("session").isNotBlank() && b.optString("session")!=id)) {
                failures[remote]=Pair(now,if(f!=null&&now-f.first<60000)f.second+1 else 1);throw IllegalArgumentException("Code session incorrect")
            }
            val expired=users.filter{it.key!=owner&&now-it.value.getLong("seen")>60000}.keys.toList();expired.forEach{users.remove(it);messages.remove(it)}
            if(users.size>=8)throw IllegalArgumentException("Session complète : 8 membres maximum")
            val uid=UUID.randomUUID().toString();users[uid]=user(uid,b.optString("name"),b.optString("role"),b.optString("client","Web"));messages[uid]=mutableListOf()
            return ticket(uid)
        }
        val p=users.values.find{same(it.getString("token"),token)} ?: throw IllegalArgumentException("Session privée : reconnexion requise")
        val uid=p.getString("id");p.put("seen",clock())
        when(path) {
            "poll" -> {
                p.put("talk",b.optBoolean("talk",false)).put("target",b.optString("target","all"))
                val level=b.optDouble("level",Double.NaN);p.put("level",if(level.isFinite())level.coerceIn(-120.0,0.0) else JSONObject.NULL)
                val ack=b.optLong("after",0);val queue=messages.getValue(uid);queue.removeAll{it.getLong("seq")<=ack}
                val public=users.values.map{u->JSONObject().also{v->for(k in listOf("id","name","role","client","group","level","target"))v.put(k,u.opt(k));v.put("leader",u.getString("id")==owner).put("online",clock()-u.getLong("seen")<7000).put("talk",u.optBoolean("talk")&&clock()-u.getLong("seen")<1500)}}
                return JSONObject().put("session",id).put("sessionName",name).put("leader",owner).put("members",JSONArray(public)).put("signals",JSONArray(queue)).put("internetRequired",false)
            }
            "signal" -> {
                val to=b.getString("to");if(to==uid||!users.containsKey(to))throw IllegalArgumentException("Membre absent")
                val type=b.getString("type");if(type !in listOf("offer","answer","ice","reset"))throw IllegalArgumentException("Signal invalide")
                val value=b.getJSONObject("data");if(value.toString().length>24000)throw IllegalArgumentException("Signal trop grand")
                val queue=messages.getValue(to);if(queue.size>=96)queue.removeAt(0)
                queue.add(JSONObject().put("seq",++serial).put("from",uid).put("type",type).put("data",value))
            }
            "group" -> { if(uid!=owner)throw IllegalArgumentException("Réservé au chef de session");users.getValue(b.getString("id")).put("group",b.optString("group","BAND").take(30)) }
            "leave" -> {if(uid!=owner){users.remove(uid);messages.remove(uid)}else p.put("talk",false)}
            else -> throw IllegalArgumentException("Commande non disponible")
        }
        return JSONObject().put("ok",true)
    }
    @Synchronized fun touchOwner(talk:Boolean,target:String,level:Double?) { users.getValue(owner).put("seen",clock()).put("talk",talk).put("target",target).put("level",level ?: JSONObject.NULL) }
}
