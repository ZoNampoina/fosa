package com.arizona.fosa.mobile

import android.net.Uri
import java.security.SecureRandom

/** Compact, versioned locators. Never serialize SDP/ICE/certificates/tokens. */
object PairingQr {
    fun nonce():String {val bytes=ByteArray(16);SecureRandom().nextBytes(bytes);return bytes.joinToString(""){"%02x".format(it)}}
    fun session(id:String,code:String,address:String):String {
        val u=Uri.parse(address)
        return "fosa://join?v=2&s=$id&c=$code&h=${u.host}&p=${u.port}"
    }
    fun device(id:String,nonce:String):String="fosa://device?v=2&id=$id&n=$nonce"
    fun parse(value:String):Uri {require(value.length<=150){"Utilise le QR court de FOSA 0.13"};val u=Uri.parse(value)
        require(u.scheme=="fosa"&&u.host in listOf("join","device")&&u.getQueryParameter("v")=="2"){"Ce QR n’est pas une invitation FOSA locale"}
        if(u.host=="join"){require(u.getQueryParameter("c")?.matches(Regex("[0-9]{6}"))==true);val h=u.getQueryParameter("h");require(h==null||LanAddress.privateV4(h));require(u.getQueryParameter("s")?.length in 6..40)}
        else{require(u.getQueryParameter("id")?.matches(Regex("[a-f0-9]{12}"))==true);require(u.getQueryParameter("n")?.matches(Regex("[a-f0-9]{32}"))==true)}
        return u
    }
}
