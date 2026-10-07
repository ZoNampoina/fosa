package com.arizona.fosa.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** One scan authorizes one short-lived guest invitation. Nothing reusable is
 * embedded in the QR; coordinator challenge/join creates the private ticket. */
class DeviceInvitation(ctx:Context,name:String,role:String,accepted:(String)->Unit):AutoCloseable {
    val id=PairingQr.nonce().take(12)
    private val nonce=PairingQr.nonce()
    val qr=PairingQr.device(id,nonce)
    private val expires=System.currentTimeMillis()+90000
    private val used=AtomicBoolean(false)
    private val manager=FosaConnectionManager(ctx){_,_,_->}
    private val server=LanHttp("0.0.0.0",null,pairing={path,q->
        require(path=="/pair/accept"&&System.currentTimeMillis()<expires){"Invitation expirée"}
        require(q.optString("id")==id&&MessageDigest.isEqual(q.optString("nonce").toByteArray(),nonce.toByteArray())){"Invitation incorrecte"}
        val join=q.getString("join");require(PairingQr.parse(join).host=="join")
        require(used.compareAndSet(false,true)){"Invitation déjà utilisée"}
        Handler(Looper.getMainLooper()).postDelayed({accepted(join)},300)
        JSONObject().put("ok",true)
    })
    init{manager.announce(JSONObject().put("session",id).put("sessionName","Appareil $name").put("hostName",name).put("hostRole",role).put("device",true).put("port",server.port).put("version",com.arizona.fosa.BuildConfig.VERSION_NAME))}
    override fun close(){used.set(true);server.close();manager.close()}
}
