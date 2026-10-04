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
import android.widget.*
import org.json.JSONObject
import java.net.URI

/** Shared LAN control UI with a deliberately small, origin-restricted native audio API. */
class BodypackActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private var origin = ""
    private var discovery: NsdManager.DiscoveryListener? = null
    private var nsd: NsdManager? = null
    private val prefs by lazy { getSharedPreferences("bodypack",MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=Color.rgb(10,13,16); window.navigationBarColor=Color.rgb(10,13,16)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(10,13,16)); setPadding(12,8,12,0) }
        val title=TextView(this).apply { text="FOSA  /  BODYPACK"; textSize=23f; setTextColor(Color.rgb(224,245,235)); setPadding(8,8,8,8) }
        root.addView(title)
        address=EditText(this).apply { hint="IP du PC ou lien QR LAN"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setText(prefs.getString("server","")) }
        root.addView(address)
        val actions=LinearLayout(this)
        actions.addView(Button(this).apply { text="CONNECT"; setOnClickListener { open(address.text.toString()) } },LinearLayout.LayoutParams(0,48,1f))
        actions.addView(Button(this).apply { text="DETECT LAN"; setOnClickListener { discover() } },LinearLayout.LayoutParams(0,48,1f))
        root.addView(actions)
        status=TextView(this).apply { setTextColor(Color.LTGRAY); text="Même LAN que le PC • USB-C / écouteurs filaires"; setPadding(8,4,8,4) }; root.addView(status)
        devices=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; root.addView(devices)
        web=WebView(this).apply {
            setBackgroundColor(Color.rgb(10,13,16)); settings.javaScriptEnabled=true; settings.domStorageEnabled=true
            settings.allowFileAccess=false; settings.allowContentAccess=false; settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            addJavascriptInterface(NativeAudio(),"FosaAndroid")
            webViewClient=object:WebViewClient() {
                override fun shouldOverrideUrlLoading(view:WebView, request:WebResourceRequest):Boolean {
                    if(request.url.toString().startsWith("$origin/"))return false
                    status.text="Lien externe : ${request.url.host ?: "adresse bloquée"}"
                    return true
                }
                override fun onReceivedError(view:WebView, request:WebResourceRequest, error:WebResourceError) {
                    if(request.isForMainFrame)status.text="Serveur inaccessible. Vérifie IP, Wi-Fi et pare-feu du PC."
                }
            }
        }
        root.addView(web,LinearLayout.LayoutParams(-1,0,1f)); setContentView(root)
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),10)
        if(intent?.data!=null)open(intent.data.toString()) else if(address.text.isNotBlank())open(address.text.toString()) else discover()
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
            origin=next; address.setText(origin); prefs.edit().putString("server",origin).apply()
            val fragment=uri.rawFragment?.let { "#$it" } ?: ""
            web.loadUrl("$origin/network.html?musician=1&native=1$fragment")
            devices.removeAllViews(); status.text="LAN • $host • PCM natif en arrière-plan"
        } catch(e:Exception) { status.text=e.message ?: "Adresse invalide" }
    }
    private fun discover() {
        if(discovery!=null)return
        nsd=getSystemService(NsdManager::class.java)
        devices.removeAllViews(); status.text="Recherche FOSA STAGE… IP manuelle disponible si multicast bloqué."
        discovery=object:NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type:String){}
            override fun onDiscoveryStopped(type:String){ discovery=null }
            override fun onStartDiscoveryFailed(type:String,code:Int){ runOnUiThread{status.text="Détection indisponible ($code). Utilise l’IP du PC."}; discovery=null }
            override fun onStopDiscoveryFailed(type:String,code:Int){ discovery=null }
            override fun onServiceLost(info:NsdServiceInfo){}
            override fun onServiceFound(info:NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd?.resolveService(info,object:NsdManager.ResolveListener {
                    override fun onResolveFailed(service:NsdServiceInfo,code:Int){}
                    override fun onServiceResolved(service:NsdServiceInfo) { runOnUiThread {
                        val ip=service.host?.hostAddress ?: return@runOnUiThread
                        if(ip.contains(':'))return@runOnUiThread
                        devices.addView(Button(this@BodypackActivity).apply { text="${service.serviceName} · $ip"; setOnClickListener { open("http://$ip:${service.port}") } })
                        status.text="Serveur détecté par mDNS. Touche son nom pour connecter."
                    } }
                })
            }
        }
        nsd?.discoverServices("_fosa._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery)
    }
    inner class NativeAudio {
        @JavascriptInterface fun start(server:String,token:String):Boolean {
            if(server!=origin || token.isBlank())return false
            runOnUiThread { startForegroundService(Intent(this@BodypackActivity,BodypackService::class.java).setAction("START").putExtra("server",server).putExtra("token",token)) }
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
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==11 && results.firstOrNull()==PackageManager.PERMISSION_GRANTED)
            startService(Intent(this,BodypackService::class.java).setAction("MIC"))
    }
    override fun onPause() { super.onPause(); BodypackService.instance?.talk=false }
    override fun onDestroy() { discovery?.let{ try{nsd?.stopServiceDiscovery(it)}catch(_:Exception){} }; web.removeJavascriptInterface("FosaAndroid"); web.destroy(); super.onDestroy() }
}
