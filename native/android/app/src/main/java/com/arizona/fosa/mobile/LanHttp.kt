package com.arizona.fosa.mobile

import org.json.JSONObject
import java.net.*
import java.io.BufferedInputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Small bounded LAN signaling server. No assets, cloud, proxy or audio in HTTP. */
class LanHttp(address:String, private val room:LanSession, bindPort:Int=0) {
    private val socket=ServerSocket().apply{reuseAddress=true;bind(InetSocketAddress(InetAddress.getByName(address),bindPort),16)}
    val port=socket.localPort
    private val running=AtomicBoolean(true)
    private val pool=java.util.concurrent.ThreadPoolExecutor(2,4,30,java.util.concurrent.TimeUnit.SECONDS,java.util.concurrent.ArrayBlockingQueue(32))
    init { Thread({while(running.get())try{val c=socket.accept();try{pool.execute{serve(c)}}catch(_:Exception){c.close()}}catch(_:Exception){}},"FOSA-coordinator").start() }
    private fun serve(c:Socket) { c.use {
        c.soTimeout=3000
        var status=200
        val result=try {
            val input=BufferedInputStream(c.getInputStream())
            fun line():String { val out=java.io.ByteArrayOutputStream();while(true){val x=input.read();require(x>=0){"Connexion fermée"};if(x==10)break;require(out.size()<2048);if(x!=13)out.write(x)};return out.toString("UTF-8") }
            val first=line()
            require(first.length<1024 && first.startsWith("POST /lan/")){"Requête invalide"}
            val path=first.substringAfter("POST /lan/").substringBefore(' ')
            var length=0;var token="";var count=0
            while(true){val line=line();if(line.isEmpty())break;require(++count<30 && line.length<2048)
                if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()
                if(line.startsWith("Authorization:",true))token=line.substringAfter(':').trim().removePrefix("Bearer ")
            }
            require(length in 0..32768){"Requête trop grande"}
            val bytes=ByteArray(length);var offset=0
            while(offset<length){val n=input.read(bytes,offset,length-offset);require(n>0);offset+=n}
            room.call(path,JSONObject(String(bytes,Charsets.UTF_8).ifEmpty{"{}"}),token,c.inetAddress.hostAddress ?: "lan")
        }catch(e:Exception){status=400;JSONObject().put("error",e.message ?: "Connexion refusée")}
        val bytes=result.toString().toByteArray()
        c.getOutputStream().write("HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".toByteArray()+bytes)
    } }
    fun close(){running.set(false);socket.close();pool.shutdownNow()}
}
