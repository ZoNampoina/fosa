package com.arizona.fosa.mobile

import android.content.Context
import android.net.*
import java.net.InetAddress
import java.net.NetworkInterface

object LanAddress {
    fun privateV4(value:String):Boolean { val n=value.split('.').map{it.toIntOrNull()};return n.size==4&&n.all{it!=null&&it in 0..255}&&(n[0]==10||n[0]==192&&n[1]==168||n[0]==172&&n[1] in 16..31) }
    fun privateV6(value:String):Boolean {val v=value.substringBefore('%').lowercase();return v.startsWith("fc")||v.startsWith("fd")||Regex("^fe[89ab]").containsMatchIn(v)}
    fun wifi(ctx:Context):Network? { val cm=ctx.getSystemService(ConnectivityManager::class.java);return cm.allNetworks.firstOrNull{val c=cm.getNetworkCapabilities(it);c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true||c?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true} }
    fun forHost(ctx:Context,host:String):Network? {
        val cm=ctx.getSystemService(ConnectivityManager::class.java)
        val target=try{InetAddress.getByName(host)}catch(_:Exception){return wifi(ctx)}
        if(target.isLoopbackAddress)return null
        return cm.allNetworks.firstOrNull{n->
            val caps=cm.getNetworkCapabilities(n)
            val local=caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true||caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true
            local&&cm.getLinkProperties(n)?.routes?.any{r->r.destination.contains(target)}==true
        }
    }
    fun gateway(ctx:Context):String? { val cm=ctx.getSystemService(ConnectivityManager::class.java);val n=wifi(ctx) ?: return null;return cm.getLinkProperties(n)?.routes?.firstOrNull{r->r.isDefaultRoute&&r.gateway?.hostAddress?.let{privateV4(it)}==true}?.gateway?.hostAddress }
    fun ip(ctx:Context):String? { val cm=ctx.getSystemService(ConnectivityManager::class.java);wifi(ctx)?.let{cm.getLinkProperties(it)?.linkAddresses?.firstOrNull{a->privateV4(a.address.hostAddress ?: "")}?.let{a->return a.address.hostAddress}}
        return NetworkInterface.getNetworkInterfaces().toList().filter{it.isUp&&!it.isLoopback&&it.name.matches(Regex("(?i).*(wlan|wifi|p2p|ap\\d|swlan|eth).*"))}.flatMap{it.inetAddresses.toList()}.mapNotNull{it.hostAddress}.firstOrNull{privateV4(it)}
    }
    fun ips():List<String> = try{NetworkInterface.getNetworkInterfaces().toList().filter{it.isUp&&!it.isLoopback}.flatMap{it.inetAddresses.toList()}.mapNotNull{it.hostAddress}.filter{privateV4(it)}.distinct()}catch(_:Exception){emptyList()}
    fun localCandidate(ctx:Context,value:String):Boolean {
        val ip=value.trim().split(Regex("\\s+")).getOrNull(4) ?: return false
        if(ip.endsWith(".local"))return true
        return try{val cm=ctx.getSystemService(ConnectivityManager::class.java)
            val networkAddresses=cm.allNetworks.filter{n->cm.getNetworkCapabilities(n)?.let{it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)}==true}.flatMap{cm.getLinkProperties(it)?.linkAddresses.orEmpty()}.mapNotNull{it.address.hostAddress}
            ip in networkAddresses||NetworkInterface.getNetworkInterfaces().toList().filter{it.isUp&&!it.isLoopback&&it.name.matches(Regex("(?i).*(wlan|wifi|p2p|ap\\d|swlan|eth).*"))}.any{n->n.inetAddresses.toList().any{it.hostAddress==ip}}
        }catch(_:Exception){false}
    }
    fun candidate(value:String):Boolean { val p=value.trim().split(Regex("\\s+"));return p.size>7&&p[6]=="typ"&&p[7]=="host"&&(privateV4(p[4])||privateV6(p[4])||p[4].endsWith(".local")) }
    fun sdp(value:String):String=value.split("\r\n").filter{!it.startsWith("a=candidate:")||candidate(it.removePrefix("a="))}.joinToString("\r\n")
    fun localSdp(ctx:Context,value:String):String=value.split("\r\n").filter{!it.startsWith("a=candidate:")||candidate(it.removePrefix("a="))&&localCandidate(ctx,it.removePrefix("a="))}.joinToString("\r\n")
}
