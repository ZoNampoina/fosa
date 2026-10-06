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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    private var pageRequested by mutableStateOf(false)
    private var loading by mutableStateOf(false)
    private var loadFailed=false
    private var loadAttempt=0
    private val main=Handler(Looper.getMainLooper())
    private var discovery: NsdManager.DiscoveryListener? = null
    private var discoveryGeneration=0
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
                    failedLoad("Lien externe bloqué : ${request.url.host ?: "adresse invalide"}. Utilise l’adresse LAN du PC.")
                    return true
                }
                override fun onReceivedError(view:WebView, request:WebResourceRequest, error:WebResourceError) {
                    if(request.isForMainFrame)failedLoad("Serveur inaccessible (${error.description}). Vérifie l’IP, le Wi-Fi et le pare-feu du PC.")
                }
                override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){
                    if(request.isForMainFrame)failedLoad("Le serveur répond HTTP ${response.statusCode}. Lance FOSA SERVER sur le PC et utilise son adresse de connexion Bodypack.")
                }
                override fun onPageFinished(view:WebView,url:String){if(!loadFailed&&url.startsWith("$origin/")){
                    loading=false;message="Contrôle chargé • LAN • ${URI(origin).host}"
                    prefs.edit().putString("server",origin).apply()
                }}
            }
        }
        setContent { FosaTheme { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=16.dp)) {
            FosaAppBar("BODYPACK","MR18 / PC")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FosaButton("‹ MOBILE",Modifier.weight(1f).testTag("bodypack-back"),secondary=true){ finish() }
                FosaButton("CONNECTION",Modifier.weight(1f).testTag("bodypack-connection"),secondary=true){controls=if(pageRequested)!controls else true}
            }
            if(controls)LazyColumn(Modifier.weight(1f).fillMaxWidth()){item{FosaPanel(Modifier.padding(vertical=12.dp).testTag("bodypack-setup")) {
                FosaLabel("FOSA STAGE SERVER")
                Text("Démarre FOSA SERVER sur le PC, puis START LOW LATENCY. Le Bodypack nécessite le PC relié à la MR18.",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(serverAddress,{serverAddress=it},label={Text("PC address / QR link")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("bodypack-address"),shape=FosaRadius.Control)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FosaButton("CONNECT",Modifier.weight(1f).testTag("bodypack-connect"),enabled=serverAddress.isNotBlank()){open(serverAddress)}
                    FosaButton("DETECT LAN",Modifier.weight(1f),secondary=true){discover()}
                }
                Text(message,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("bodypack-message"))
                devices.forEach{(name,url)->FosaButton(name,Modifier.fillMaxWidth(),secondary=true){open(url)}}
            }}}
            if(loading&&!controls)Text(message,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=8.dp))
            AndroidView(factory={web},modifier=(if(pageRequested)Modifier.weight(1f) else Modifier.height(0.dp)).fillMaxWidth().testTag("bodypack-web"))
        } } }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),10)
        message="Préparation du moteur Bodypack…"
        AudioModeSwitch.toBodypack(this,{
            if(intent?.data!=null)open(intent.data.toString()) else if(serverAddress.isNotBlank())open(serverAddress) else discover()
        },{failedLoad(it)})
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent); setIntent(intent); intent.data?.let{url->AudioModeSwitch.toBodypack(this,{open(url.toString())},{failedLoad(it)})} }
    private fun open(value:String) {
        stopDiscovery()
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
            origin=next; serverAddress=origin
            val fragment=uri.rawFragment?.let { "#$it" } ?: ""
            loadFailed=false;loading=true;pageRequested=true;val attempt=++loadAttempt
            controls=false;devices.clear();message="Chargement du serveur PC • $host…"
            web.loadUrl("$origin/network.html?musician=1&native=1$fragment")
            main.postDelayed({if(attempt==loadAttempt&&loading){web.stopLoading();failedLoad("Le serveur PC ne répond pas. Vérifie son adresse et que FOSA SERVER est démarré.")}},15000)
        } catch(e:Exception) { controls=true;message=e.message ?: "Adresse invalide" }
    }
    private fun failedLoad(cause:String){loadFailed=true;loading=false;pageRequested=false;controls=true;message=cause}
    private fun stopDiscovery(){discoveryGeneration++;val listener=discovery;discovery=null
        if(listener!=null)try{nsd?.stopServiceDiscovery(listener)}catch(_:Exception){}
    }
    private fun discover() {
        stopDiscovery();val generation=discoveryGeneration
        controls=true
        nsd=getSystemService(NsdManager::class.java)
        devices.clear(); message="Recherche FOSA STAGE… IP manuelle disponible si multicast bloqué."
        discovery=object:NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type:String){}
            override fun onDiscoveryStopped(type:String){if(generation==discoveryGeneration)discovery=null}
            override fun onStartDiscoveryFailed(type:String,code:Int){runOnUiThread{if(generation==discoveryGeneration){message="Détection indisponible ($code). Utilise l’IP du PC.";discovery=null}}}
            override fun onStopDiscoveryFailed(type:String,code:Int){if(generation==discoveryGeneration)discovery=null}
            override fun onServiceLost(info:NsdServiceInfo){}
            override fun onServiceFound(info:NsdServiceInfo) {
                if(generation!=discoveryGeneration)return
                @Suppress("DEPRECATION")
                nsd?.resolveService(info,object:NsdManager.ResolveListener {
                    override fun onResolveFailed(service:NsdServiceInfo,code:Int){}
                    override fun onServiceResolved(service:NsdServiceInfo) { runOnUiThread {
                        if(generation!=discoveryGeneration)return@runOnUiThread
                        val ip=service.host?.hostAddress ?: return@runOnUiThread
                        if(ip.contains(':'))return@runOnUiThread
                        val item=service.serviceName to "http://$ip:${service.port}";if(item !in devices)devices.add(item)
                        message="Serveur détecté par mDNS. Touche son nom pour connecter."
                    } }
                })
            }
        }
        nsd?.discoverServices("_fosa._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery)
        main.postDelayed({if(generation==discoveryGeneration&&devices.isEmpty()&&controls&&!loading)message="Aucun serveur PC détecté. Lance FOSA SERVER et START LOW LATENCY, puis saisis l’IP affichée sur le PC."},6000)
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
        @JavascriptInterface fun control(talk:Boolean,target:String) { BodypackService.instance?.controlTalk(talk,target) }
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
    override fun onDestroy() { main.removeCallbacksAndMessages(null);stopDiscovery();web.removeJavascriptInterface("FosaAndroid"); web.destroy(); super.onDestroy() }
}
