package com.arizona.fosa.mobile

import android.content.Context
import org.json.JSONObject
import java.net.*
import java.io.BufferedInputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ServerSocketFactory

/** Bounded coordinator and bundled Web shell. No audio/proxy/cloud in HTTP. */
class LanHttp(address:String,private val room:LanSession?,bindPort:Int=0,
    private val ctx:Context?=null,private val ca:ByteArray?=null,
    factory:ServerSocketFactory=ServerSocketFactory.getDefault(),
    private val pairing:((String,JSONObject)->JSONObject)?=null) {
    private val socket=factory.createServerSocket().apply{reuseAddress=true;bind(InetSocketAddress(InetAddress.getByName(address),bindPort),16)}
    val port=socket.localPort
    private val running=AtomicBoolean(true)
    private val pool=java.util.concurrent.ThreadPoolExecutor(2,4,30,java.util.concurrent.TimeUnit.SECONDS,java.util.concurrent.ArrayBlockingQueue(32))
    private val assets=setOf("index.html","app.js","core.js","connection.js","qr.js","style.css","icon.svg","manifest.json","sw.js")
    init{Thread({while(running.get())try{val c=socket.accept();try{pool.execute{serve(c)}}catch(_:Exception){c.close()}}catch(_:Exception){}},"FOSA-local-server").apply{isDaemon=true;start()}}
    private fun serve(c:Socket){c.use{try{
        c.soTimeout=4000
        val ip=c.inetAddress.hostAddress.orEmpty()
        require(c.inetAddress.isLoopbackAddress||LanAddress.privateV4(ip)||LanAddress.privateV6(ip)){"Réseau local requis"}
        val input=BufferedInputStream(c.getInputStream())
        fun line():String{val out=java.io.ByteArrayOutputStream();while(true){val x=input.read();require(x>=0){"Connexion fermée"};if(x==10)break;require(out.size()<2048);if(x!=13)out.write(x)};return out.toString("UTF-8")}
        val parts=line().split(' ');require(parts.size==3);val method=parts[0];val path=parts[1].substringBefore('?');var length=0;var token="";var count=0
        while(true){val l=line();if(l.isEmpty())break;require(++count<30)
            if(l.startsWith("Content-Length:",true))length=l.substringAfter(':').trim().toInt()
            if(l.startsWith("Authorization:",true))token=l.substringAfter(':').trim().removePrefix("Bearer ")
            require(!l.startsWith("Transfer-Encoding:",true)){"Encodage non disponible"}
        }
        require(length in 0..32768);val bytes=ByteArray(length);var offset=0
        while(offset<length){val n=input.read(bytes,offset,length-offset);require(n>0);offset+=n}
        if(path.startsWith("/lan/")&&(method=="POST"||method=="GET"&&path=="/lan/info")){
            val result=room?.call(path.removePrefix("/lan/"),JSONObject(String(bytes,Charsets.UTF_8).ifEmpty{"{}"}),token,ip) ?: error("Session indisponible")
            respond(c,200,"application/json",result.toString().toByteArray());return
        }
        if(path.startsWith("/pair/")&&method=="POST"&&pairing!=null){respond(c,200,"application/json",pairing.invoke(path,JSONObject(String(bytes,Charsets.UTF_8).ifEmpty{"{}"})).toString().toByteArray());return}
        require(method=="GET"){"Requête invalide"}
        if(path=="/fosa-host-ca.crt"&&ca!=null){respond(c,200,"application/x-x509-ca-cert",ca);return}
        if(path=="/trust"&&ca!=null){
            val host=c.localAddress.hostAddress
            val fingerprint=java.security.MessageDigest.getInstance("SHA-256").digest(ca).joinToString(":"){"%02X".format(it)}
            val html="""<!doctype html><html lang="fr"><meta name="viewport" content="width=device-width"><title>FOSA Web local</title><body style="background:#0d1218;color:#eee;font:18px system-ui;max-width:700px;margin:40px auto;padding:20px"><h1>FOSA Web local</h1><p>Le micro et la PWA exigent HTTPS. Aucun Internet n'est nécessaire.</p><ol><li><a href="/fosa-host-ca.crt">Télécharger le certificat de cet hôte</a>.</li><li>Windows : importer ce certificat dans Autorités de certification racines de confiance de l'utilisateur. Android : Paramètres → Sécurité → Installer un certificat CA. iOS : installer le profil, puis activer la confiance dans les réglages des certificats.</li><li><a href="https://$host:48766/">Ouvrir FOSA Web sécurisé</a> et autoriser le micro.</li></ol><p>Vérifie cette empreinte sur l'hôte avant de faire confiance à son certificat :</p><code style="word-break:break-all">$fingerprint</code><p>Fais confiance uniquement à ton propre hôte. Le certificat et sa clé sont propres à son installation. Retire le certificat quand tu n'en as plus besoin.</p></body></html>"""
            respond(c,200,"text/html; charset=utf-8",html.toByteArray());return
        }
        val asset=when(path){"/","/mobile/","/join"->"index.html";else->path.removePrefix("/mobile/").removePrefix("/")}
        if(ctx==null||asset !in assets){respond(c,404,"text/plain","Page introuvable".toByteArray());return}
        val content=ctx.assets.open("fosa-web/$asset").use{it.readBytes()}
        val type=when(asset.substringAfterLast('.')){"js"->"text/javascript";"css"->"text/css";"html"->"text/html";"svg"->"image/svg+xml";"json"->"application/json";else->"text/plain"}
        respond(c,200,"$type; charset=utf-8",content)
    }catch(e:Exception){try{respond(c,400,"application/json",JSONObject().put("error",e.message ?: "Connexion refusée").toString().toByteArray())}catch(_:Exception){}}}}
    private fun respond(c:Socket,status:Int,type:String,bytes:ByteArray){c.getOutputStream().write("HTTP/1.1 $status ${if(status==200)"OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nService-Worker-Allowed: /\r\n\r\n".toByteArray()+bytes)}
    fun close(){running.set(false);socket.close();pool.shutdownNow()}
}
