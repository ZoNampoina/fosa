package com.arizona.fosa.mobile

import org.json.JSONObject

enum class TransportKind(val label:String) {
    LAN("Local Wi-Fi"), WIFI_DIRECT("Wi-Fi Direct"), HOTSPOT("Hotspot"), CLOUD_RENDEZVOUS("Internet-assisted discovery")
}
enum class ConnectionPhase { DISCOVERING, FOUND, AUTHENTICATING, CONNECTING_NETWORK, SIGNALING, NEGOTIATING_AUDIO, CONNECTED, RECONNECTING, FAILED }

/** Network adapters never own Session, Auth, SDP or audio. Future iOS/Windows
 * adapters implement this contract using their own platform APIs. */
interface LocalTransport : AutoCloseable {
    val kind:TransportKind
    fun discover(found:(LocalSession)->Unit, failed:(String)->Unit)
    fun announce(metadata:JSONObject)
    fun connect(session:LocalSession, connected:(String)->Unit, failed:(String)->Unit)
}

data class LocalSession(val id:String,val name:String,val code:String,val host:String,val role:String,
    val address:String="",val port:Int=48765,val kind:TransportKind=TransportKind.LAN,
    val peer:String="",val members:Int=1,val version:String="",val device:Boolean=false) {
    fun json()=JSONObject().put("session",id).put("sessionName",name).put("code",code).put("hostName",host)
        .put("hostRole",role).put("address",address).put("port",port).put("transport",kind.name)
        .put("transportLabel",kind.label).put("members",members).put("version",version).put("peer",peer).put("device",device)
    companion object {
        fun from(q:JSONObject,address:String="",kind:TransportKind=TransportKind.LAN,peer:String="")=LocalSession(
            q.optString("session"),q.optString("sessionName","FOSA"),q.optString("code"),q.optString("hostName"),
            q.optString("hostRole"),address,q.optInt("port",48765),kind,peer,q.optInt("members",1),q.optString("version"),q.optBoolean("device"))
    }
}
