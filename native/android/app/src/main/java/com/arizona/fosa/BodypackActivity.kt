package com.arizona.fosa

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.*
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.arizona.fosa.mobile.*
import androidx.core.view.WindowCompat
import org.json.JSONObject
import java.net.URI

/** Shared LAN control UI with a deliberately small, origin-restricted native audio API. */
class BodypackActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var serverAddress by mutableStateOf("")
    private var message by mutableStateOf("Même LAN que le PC · écouteurs filaires / USB-C")
    private val devices=mutableStateListOf<Pair<String,String>>()
    private var controls by mutableStateOf(true)
    private var origin = ""
    private var discovery: NsdManager.DiscoveryListener? = null
    private var nsd: NsdManager? = null
    private val prefs by lazy { getSharedPreferences("bodypack",MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        serverAddress=prefs.getString("server","") ?: ""
        web=WebView(this).apply {
            setBackgroundColor(Color.rgb(10,13,16)); settings.javaScriptEnabled=true; settings.domStorageEnabled=true
            settings.allowFileAccess=false; settings.allowContentAccess=false; settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            addJavascriptInterface(NativeAudio(),"FosaAndroid")
            webViewClient=object:WebViewClient() {
                override fun shouldOverrideUrlLoading(view:WebView, request:WebResourceRequest):Boolean {
                    if(request.url.toString().startsWith("$origin/"))return false
                    message="Lien externe : ${request.url.host ?: "adresse bloquée"}"
                    return true
                }
                override fun onReceivedError(view:WebView, request:WebResourceRequest, error:WebResourceError) {
                    if(request.isForMainFrame)message="Serveur inaccessible. Vérifie IP, Wi-Fi et pare-feu du PC."
                }
            }
        }
        setContent { FosaTheme { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=16.dp)) {
            FosaAppBar("BODYPACK","MR18 / PC")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FosaButton("‹ MOBILE",Modifier.weight(1f),secondary=true){ finish() }
                FosaButton("CONNECTION",Modifier.weight(1f),secondary=true){controls=!controls}
            }
            if(controls)FosaPanel(Modifier.padding(vertical=12.dp)) {
                FosaLabel("FOSA STAGE SERVER")
                OutlinedTextField(serverAddress,{serverAddress=it},label={Text("PC address / QR link")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=FosaRadius.Control)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FosaButton("CONNECT",Modifier.weight(1f)){open(serverAddress)}
                    FosaButton("DETECT LAN",Modifier.weight(1f),secondary=true){discover()}
                }
                Text(message,style=MaterialTheme.typography.bodySmall)
                devices.forEach{(name,url)->FosaButton(name,Modifier.fillMaxWidth(),secondary=true){open(url)}}
            }
            AndroidView(factory={web},modifier=Modifier.weight(1f).fillMaxWidth())
        } } }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),10)
        if(intent?.data!=null)open(intent.data.toString()) else if(serverAddress.isNotBlank())open(serverAddress) else discover()
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent); setIntent(intent); intent.data?.let{open(it.toString())} }
    private fun open(value:String) {
        try {
            var text=value.trim()
            if(text.startsWith("fosa://")) {
                val uri=Uri.parse(text); val server=uri.getQueryParameter("server") ?: throw IllegalArgumentException("Serveur absent")
                text="$server/network.html?musician=1#code=${Uri.encode(uri.getQueryParameter("code") ?: "")}&session=${Uri.encode(uri.getQueryParameter("session") ?: "")}" 
            }
            if(!text.contains("://"))text="http://$text"
            val uri=URI(text); val host=uri.host ?: throw IllegalArgumentException("Adresse invalide")
            // No untrusted internet pages get a JavaScript bridge into the audio service.
            val octets=host.split('.').map { it.toIntOrNull() }
            val privateIp=octets.size==4 && octets.all{it!=null && it in 0..255} &&
                (octets[0]==10 || (octets[0]==192 && octets[1]==168) || (octets[0]==172 && octets[1] in 16..31))
            val local=host=="localhost" || privateIp || host.endsWith(".local")
            require(local && uri.scheme in listOf("http","https") && uri.userInfo==null) { "Adresse LAN privée requise" }
            val port=if(uri.port<0)8765 else uri.port
            val next="${uri.scheme}://$host:$port"
            if(origin.isNotEmpty() && origin!=next)stopService(Intent(this,BodypackService::class.java))
            origin=next; serverAddress=origin; prefs.edit().putString("server",origin).apply()
            val fragment=uri.rawFragment?.let { "#$it" } ?: ""
            web.loadUrl("$origin/network.html?musician=1&native=1$fragment")
            controls=false; devices.clear(); message="LAN • $host • PCM natif en arrière-plan"
        } catch(e:Exception) { message=e.message ?: "Adresse invalide" }
    }
    private fun discover() {
        if(discovery!=null)return
        nsd=getSystemService(NsdManager::class.java)
        devices.clear(); message="Recherche FOSA STAGE… IP manuelle disponible si multicast bloqué."
        discovery=object:NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type:String){}
            override fun onDiscoveryStopped(type:String){ discovery=null }
            override fun onStartDiscoveryFailed(type:String,code:Int){ runOnUiThread{message="Détection indisponible ($code). Utilise l’IP du PC."}; discovery=null }
            override fun onStopDiscoveryFailed(type:String,code:Int){ discovery=null }
            override fun onServiceLost(info:NsdServiceInfo){}
            override fun onServiceFound(info:NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd?.resolveService(info,object:NsdManager.ResolveListener {
                    override fun onResolveFailed(service:NsdServiceInfo,code:Int){}
                    override fun onServiceResolved(service:NsdServiceInfo) { runOnUiThread {
                        val ip=service.host?.hostAddress ?: return@runOnUiThread
                        if(ip.contains(':'))return@runOnUiThread
                        val item=service.serviceName to "http://$ip:${service.port}";if(item !in devices)devices.add(item)
                        message="Serveur détecté par mDNS. Touche son nom pour connecter."
                    } }
                })
            }
        }
        nsd?.discoverServices("_fosa._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery)
    }
    inner class NativeAudio {
        @JavascriptInterface fun start(server:String,token:String,targetMs:Int):Boolean {
            if(server!=origin || token.isBlank())return false
            runOnUiThread { startForegroundService(Intent(this@BodypackActivity,BodypackService::class.java).setAction("START").putExtra("server",server).putExtra("token",token).putExtra("targetMs",targetMs.coerceIn(5,40))) }
            return true
        }
        @JavascriptInterface fun stop() { runOnUiThread { stopService(Intent(this@BodypackActivity,BodypackService::class.java)) } }
        @JavascriptInterface fun panic(active:Boolean) { BodypackService.instance?.muteLocal(active) }
        @JavascriptInterface fun status():String = BodypackService.instance?.status()?.toString() ?: JSONObject().put("connected",false).put("playback",false).toString()
        @JavascriptInterface fun control(talk:Boolean,target:String) { BodypackService.instance?.let { it.target=target.take(80); it.talk=talk && it.micArmed } }
        @JavascriptInterface fun target(ms:Int) { BodypackService.instance?.targetPackets=(ms/5).coerceIn(1,8) }
        @JavascriptInterface fun microphone(active:Boolean) { runOnUiThread {
            if(!active)BodypackService.instance?.disableMic()
            else if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),11)
            else startService(Intent(this@BodypackActivity,BodypackService::class.java).setAction("MIC"))
        } }
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<String>,results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==11 && results.firstOrNull()==PackageManager.PERMISSION_GRANTED)
            startService(Intent(this,BodypackService::class.java).setAction("MIC"))
    }
    override fun onPause() { super.onPause(); BodypackService.instance?.talk=false }
    override fun onDestroy() { discovery?.let{ try{nsd?.stopServiceDiscovery(it)}catch(_:Exception){} }; web.removeJavascriptInterface("FosaAndroid"); web.destroy(); super.onDestroy() }
}
