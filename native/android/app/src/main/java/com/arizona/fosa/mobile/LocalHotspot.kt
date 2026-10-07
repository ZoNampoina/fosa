package com.arizona.fosa.mobile

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.Build

class LocalHotspot(private val ctx:Context):AutoCloseable {
    private var reservation:WifiManager.LocalOnlyHotspotReservation?=null
    private var previousAddresses=emptySet<String>()
    fun address():String?=LanAddress.ips().firstOrNull{it !in previousAddresses}
    @Suppress("DEPRECATION") fun create(ready:(String,String)->Unit,failed:(String)->Unit){
        reservation?.let{r->val ssid=if(Build.VERSION.SDK_INT>=30)r.softApConfiguration.ssid else r.wifiConfiguration?.SSID;val pass=if(Build.VERSION.SDK_INT>=30)r.softApConfiguration.passphrase else r.wifiConfiguration?.preSharedKey;ready(ssid.orEmpty(),pass.orEmpty());return}
        previousAddresses=LanAddress.ips().toSet()
        try{ctx.applicationContext.getSystemService(WifiManager::class.java).startLocalOnlyHotspot(object:WifiManager.LocalOnlyHotspotCallback(){
            override fun onStarted(r:WifiManager.LocalOnlyHotspotReservation){reservation=r
                val ssid=if(Build.VERSION.SDK_INT>=30)r.softApConfiguration.ssid else r.wifiConfiguration?.SSID
                val password=if(Build.VERSION.SDK_INT>=30)r.softApConfiguration.passphrase else r.wifiConfiguration?.preSharedKey
                ready(ssid.orEmpty(),password.orEmpty())}
            override fun onFailed(reason:Int){failed(when(reason){ERROR_NO_CHANNEL->"Aucun canal Wi-Fi disponible";ERROR_INCOMPATIBLE_MODE->"Hotspot déjà utilisé : rejoins ce réseau local";ERROR_TETHERING_DISALLOWED->"Création du hotspot refusée par Android";else->"Hotspot local indisponible ($reason)"})}
            override fun onStopped(){reservation=null;failed("Le réseau FOSA local a été arrêté")}
        },Handler(Looper.getMainLooper()))}catch(_:SecurityException){failed("Permission Appareils à proximité refusée")}catch(_:Exception){failed("Hotspot local indisponible")}
    }
    override fun close(){reservation?.close();reservation=null}
}
