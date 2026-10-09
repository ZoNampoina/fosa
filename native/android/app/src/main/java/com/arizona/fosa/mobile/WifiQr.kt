package com.arizona.fosa.mobile

/** ZXing Wi-Fi payload. Only real Android LocalOnlyHotspot credentials belong here. */
object WifiQr {
    private fun escape(value:String)=buildString{value.forEach{c->if(c in "\\;:,\"")append('\\');append(c)}}
    fun encode(ssid:String,password:String):String {require(ssid.isNotBlank()&&password.isNotBlank());return "WIFI:T:WPA;S:${escape(ssid)};P:${escape(password)};;"}
}
