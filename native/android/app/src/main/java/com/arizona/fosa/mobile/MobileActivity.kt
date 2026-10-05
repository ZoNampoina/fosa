package com.arizona.fosa.mobile

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.nsd.*
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import org.json.JSONObject
import com.arizona.fosa.BodypackActivity
import com.google.zxing.*
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap

@OptIn(ExperimentalMaterial3Api::class)
class MobileActivity:ComponentActivity(){
    private var route by mutableStateOf("home");private var tab by mutableStateOf("TALK")
    private var name by mutableStateOf("ZO");private var role by mutableStateOf("SAX");private var sessionName by mutableStateOf("BAND LIVE")
    private var address by mutableStateOf("");private var code by mutableStateOf("");private var theme by mutableStateOf("DARK")
    private var haptic by mutableStateOf(true)
    private var performance by mutableStateOf(false);private var awake by mutableStateOf(false);private var modal by mutableStateOf("")
    private var discoveries=mutableStateListOf<Pair<String,String>>()
    private var discovery:NsdManager.DiscoveryListener?=null
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){MobileService.instance?.armMic()}
    override fun onCreate(b:Bundle?){super.onCreate(b);WindowCompat.setDecorFitsSystemWindows(window,false)
        val prefs=getSharedPreferences("mobile-ui",MODE_PRIVATE);name=prefs.getString("name","ZO") ?: "ZO";role=prefs.getString("role","SAX") ?: "SAX";theme=prefs.getString("theme","DARK") ?: "DARK"
        setContent{FosaTheme(theme,haptic){MobileRoot()}};handle(intent);scan()
    }
    override fun onNewIntent(i:Intent){super.onNewIntent(i);setIntent(i);handle(i)}
    private fun handle(i:Intent){i.data?.let{u->if(u.host=="mobile-pair")MobileService.instance?.importWeb(u.toString()) else if(u.host=="mobile"){address=u.getQueryParameter("address") ?: "";code=u.getQueryParameter("code") ?: "";route="join"};Unit}}
    private fun askMic(){val list=mutableListOf(Manifest.permission.RECORD_AUDIO);if(android.os.Build.VERSION.SDK_INT>=33)list.add(Manifest.permission.POST_NOTIFICATIONS);permissions.launch(list.toTypedArray())}
    private fun launch(host:Boolean){getSharedPreferences("mobile-ui",MODE_PRIVATE).edit().putString("name",name).putString("role",role).apply();startForegroundService(Intent(this,MobileService::class.java).setAction("START").putExtra("host",host).putExtra("name",name).putExtra("role",role).putExtra("session",sessionName).putExtra("address",address).putExtra("code",code));tab="TALK"}
    private fun leave(){MobileService.instance?.disconnect() ?: stopService(Intent(this,MobileService::class.java));route="home"}
    @Composable private fun MobileRoot(){val s=MobileService.state;val active=s.optBoolean("active")
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=20.dp)){
            FosaAppBar(status=if(s.optBoolean("talk"))"TALK" else if(active)"LOCAL" else "LAN FIRST",good=!active||s.optBoolean("controlConnected"))
            if(active){Column(Modifier.weight(1f)){if(s.optString("error").isNotBlank())Text(s.optString("error"),color=FosaColors.Warning,modifier=Modifier.padding(vertical=8.dp));when(tab){"TALK"->Talk(s);"MEMBERS"->Members(s);"STATUS"->Status(s);else->SettingsScreen(s)}}
                Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("TALK","MEMBERS","STATUS","SETTINGS").forEach{item->FosaButton(item,Modifier.weight(1f).testTag("nav-$item"),secondary=tab!=item,compact=true){tab=item}}}
            }else{key(route){LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(20.dp)){item{if(route=="home")Home() else Form(route=="create")};if(s.optString("error").isNotBlank())item{FosaPanel{Text(s.optString("error"),color=FosaColors.Warning);FosaButton("RETRY"){leave()}}}}}}
        }
        if(modal.isNotEmpty())ModalBottomSheet(onDismissRequest={modal=""},containerColor=MaterialTheme.colorScheme.surface){Column(Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
            when(modal){"users"->{Text("Talk to a member",style=MaterialTheme.typography.titleLarge);members(s).filter{it.optString("id")!=s.optString("id")}.forEach{u->FosaButton("${u.optString("name")} · ${u.optString("role")}",Modifier.fillMaxWidth(),secondary=true){MobileService.instance?.target("user:${u.getString("id")}");modal=""}}}
                "groups"->{Text("Talk to a group",style=MaterialTheme.typography.titleLarge);members(s).map{it.optString("group","BAND")}.distinct().forEach{g->FosaButton(g,Modifier.fillMaxWidth(),secondary=true){MobileService.instance?.target("group:$g");modal=""}}}
                "offline"->{Text("Offline package",style=MaterialTheme.typography.headlineLarge);FosaStatus("NATIVE · READY");Text("Interface, moteur audio et icônes sont intégrés à l’APK. Aucun téléchargement n’est requis pendant la session.");FosaButton("CHECK OFFLINE READY"){modal="offline-ready"}}
                "offline-ready"->{FosaStatus("APP SHELL · AUDIO · UI · ICONS READY");Text("Le LAN reste nécessaire pour rejoindre les autres membres. Internet est facultatif.")}
                "invite"->{Text("Invite a member",style=MaterialTheme.typography.titleLarge);Qr(s.optString("join"));FosaLabel("SESSION CODE");Text(s.optString("code"),style=MaterialTheme.typography.displayLarge,fontFamily=FontFamily.Monospace);Text("Scanner avec la caméra Android, puis ouvrir FOSA. Garde ce code privé.")}
                "pair"->{Text("Pair a Web fallback",style=MaterialTheme.typography.titleLarge);Text("Sur la PWA, crée une invitation Web. Scanne son QR avec la caméra de ce téléphone, puis ouvre le lien FOSA.");if(s.optString("answer").isNotBlank()){Qr(s.getString("answer"));FosaLabel("ANSWER · à scanner depuis la PWA ouverte");val clipboard=LocalClipboardManager.current;FosaButton("COPY ANSWER"){clipboard.setText(androidx.compose.ui.text.AnnotatedString(s.getString("answer")))}}}
                else->{Text("Advanced diagnostics",style=MaterialTheme.typography.titleLarge);Text("${s.optString("address")}\nFOSA-LAN/1 · Opus 48 kHz · SRTP\nAucun STUN / TURN\n8 membres maximum\nLatence audio : UNKNOWN");Text("Le RTT mesure un aller-retour réseau. Il ne mesure pas la latence microphone → casque.")}
            };FosaButton("CLOSE",Modifier.fillMaxWidth(),secondary=true){modal=""};Spacer(Modifier.height(24.dp))}}
    }
    @Composable private fun Home(){Column(verticalArrangement=Arrangement.spacedBy(20.dp)){
        Spacer(Modifier.height(8.dp));FosaLabel("PRIVATE NETWORK INTERCOM");Text("Your stage.\nYour connection.",style=MaterialTheme.typography.headlineLarge);Text("Parlez sur votre réseau local.\nSans PC. Sans compte cloud.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        FosaPanel{FosaLabel("01 / MOBILE NATIVE");Text("Create a session",style=MaterialTheme.typography.titleLarge);Text("Un téléphone accueille votre groupe.");FosaButton("CREATE SESSION",Modifier.fillMaxWidth().testTag("home-create")){route="create"}}
        FosaPanel{FosaLabel("02 / JOIN LOCAL");Text("Join your band",style=MaterialTheme.typography.titleLarge);Text("Rejoignez une session à proximité.");FosaButton("JOIN SESSION",Modifier.fillMaxWidth().testTag("home-join"),secondary=true){route="join";scan()}}
        FosaPanel{FosaLabel("03 / BODYPACK");Text("MR18 personal monitoring",style=MaterialTheme.typography.titleMedium);FosaButton("OPEN BODYPACK",Modifier.fillMaxWidth(),secondary=true){stopService(Intent(this@MobileActivity,MobileService::class.java));startActivity(Intent(this@MobileActivity,BodypackActivity::class.java))}}
        FosaStatus("LOCAL NETWORK · INTERNET NOT REQUIRED");Spacer(Modifier.height(12.dp))
    }}
    @Composable private fun Field(label:String,value:String,onChange:(String)->Unit){OutlinedTextField(value,onChange,label={FosaLabel(label)},singleLine=true,shape=FosaRadius.Control,modifier=Modifier.fillMaxWidth().testTag(label))}
    @Composable private fun Form(host:Boolean){Column(verticalArrangement=Arrangement.spacedBy(16.dp)){FosaButton("‹ BACK",secondary=true){route="home"};FosaLabel(if(host)"CREATE PRIVATE SESSION" else "JOIN LOCAL SESSION");Text(if(host)"Bring your band\ntogether." else "Find your\nconnection.",style=MaterialTheme.typography.headlineLarge)
        if(!host){if(discoveries.isEmpty())FosaPanel{Text("No FOSA session found");Text("Même Wi-Fi ou hotspot. Certains réseaux invités bloquent les connexions entre téléphones.",color=MaterialTheme.colorScheme.onSurfaceVariant);FosaButton("RESCAN",secondary=true){scan()}} else discoveries.forEach{(title,url)->FosaPanel{Text(title,style=MaterialTheme.typography.titleLarge);FosaStatus("LOCAL NETWORK");FosaButton("SELECT"){address=url}}}}
        FosaPanel{if(host)Field("SESSION NAME",sessionName){sessionName=it};Field("YOUR NAME",name){name=it};Field("ROLE / INSTRUMENT",role){role=it}
            if(!host){if(address.isNotBlank())FosaStatus("SESSION SELECTED");Field("SESSION CODE",code){code=it};var manual by remember{mutableStateOf(false)};FosaButton("MANUAL / ADVANCED",secondary=true){manual=!manual};if(manual)Field("HOST ADDRESS",address){address=it}}
            FosaStatus(if(LanAddress.ip(this@MobileActivity)!=null)"LOCAL NETWORK READY" else "WIFI / HOTSPOT REQUIRED",LanAddress.ip(this@MobileActivity)!=null)
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)FosaButton("ENABLE MICROPHONE",Modifier.fillMaxWidth(),secondary=true){askMic()}
            FosaButton(if(host)"CREATE SESSION" else "JOIN SESSION",Modifier.fillMaxWidth().testTag("submit-session"),enabled=name.isNotBlank()&&(host||address.isNotBlank()&&code.length==6)){launch(host)}
            Text("Internet not required. Écoute possible sans microphone.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }}
    @Composable private fun Talk(s:JSONObject){val peers=members(s);BoxWithConstraints(Modifier.fillMaxSize()){
        val wide=maxWidth>600.dp||maxWidth>maxHeight*1.4f
        val compact=wide&&maxHeight<420.dp
        val target=s.optString("target")
        val label=when{target.startsWith("user:")->peers.find{it.optString("id")==target.removePrefix("user:")}?.optString("name") ?: "Member";target.startsWith("group:")->target.removePrefix("group:");else->target.uppercase()}
        val chooseTarget:(String)->Unit={t->when(t){"USER"->modal="users";"GROUP"->modal="groups";else->MobileService.instance?.target(t.lowercase())}}
        val panic:@Composable ()->Unit={FosaButton(if(s.optBoolean("muted"))"UNMUTE" else "PANIC MUTE",Modifier.fillMaxWidth().testTag("panic-button"),secondary=!s.optBoolean("muted")){MobileService.instance?.panic(!s.optBoolean("muted"))}}
        val controls:@Composable ()->Unit={Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
            FosaLabel("TALK TO");Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("ALL","GROUP","LEADER","USER").forEach{t->FosaButton(t,Modifier.weight(1f),secondary=true,compact=true,enabled=t!="LEADER"||!s.optBoolean("host")){chooseTarget(t)}}}
            Text(label,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
            FosaTalkButton(s.optBoolean("talk"),s.optBoolean("mic")&&s.optBoolean("controlConnected")&&!s.optBoolean("muted")&&hasAudioPeer(s),{MobileService.instance?.push(it)},Modifier.widthIn(max=280.dp).fillMaxWidth(.8f))
            FosaMeter(if(s.optBoolean("talk"))s.optDouble("level").takeIf{it.isFinite()} else null,Modifier.widthIn(max=300.dp));FosaLabel(if(s.optBoolean("talk"))"MIC INPUT · MEASURED" else "HOLD TO TRANSMIT")
            if(!s.optBoolean("mic"))FosaButton("ENABLE MICROPHONE"){askMic()}
            panic()
        }}
        val details:@Composable ()->Unit={Column(verticalArrangement=Arrangement.spacedBy(if(compact)8.dp else 16.dp)){FosaLabel(s.optString("sessionName"));Text("${s.optString("name")} — ${s.optString("role")}",style=if(compact)MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){FosaStatus(if(s.optBoolean("controlConnected"))"LOCAL" else "RECONNECTING",s.optBoolean("controlConnected"));FosaLabel("${peers.size} MEMBERS")};if(!performance&&wide&&!compact)FosaPanel{FosaLabel("AUDIO OUTPUT");Text(s.optString("output"));FosaLabel("AUDIO LATENCY · UNKNOWN");Text("End-to-end measurement required",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};if(!wide||compact)Text(s.optString("output"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);if(compact&&!s.optBoolean("mic"))FosaButton("ENABLE MICROPHONE"){askMic()};peers.filter{it.optBoolean("talk")&&it.optString("id")!=s.optString("id")}.take(if(compact)1 else if(wide)8 else 1).forEach{u->if(compact)FosaStatus("${u.optString("name")} · TALKING") else FosaChannel(u.optString("name"),u.optString("role"),true,u.optDouble("level").takeIf{it.isFinite()}){}}}}
        if(compact)Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.weight(.65f).fillMaxHeight().verticalScroll(rememberScrollState())){details()}
            Row(Modifier.weight(1.35f),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    FosaLabel("TALK TO · $label")
                    listOf(listOf("ALL","GROUP"),listOf("LEADER","USER")).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){row.forEach{t->FosaButton(t,Modifier.weight(1f),secondary=true,compact=true,enabled=t!="LEADER"||!s.optBoolean("host")){chooseTarget(t)}}}}
                    panic()
                }
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
                    FosaTalkButton(s.optBoolean("talk"),s.optBoolean("mic")&&s.optBoolean("controlConnected")&&!s.optBoolean("muted")&&hasAudioPeer(s),{MobileService.instance?.push(it)},Modifier.size(180.dp))
                    FosaMeter(if(s.optBoolean("talk"))s.optDouble("level").takeIf{it.isFinite()} else null,Modifier.width(180.dp))
                }
            }
        }else if(wide)Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(24.dp)){Box(Modifier.weight(1f)){details()};Box(Modifier.weight(1f)){controls()}} else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)){details();controls();Spacer(Modifier.height(12.dp))}
    }}
    private fun hasAudioPeer(s:JSONObject):Boolean{val a=s.optJSONArray("metrics") ?: return false;return (0 until a.length()).any{a.getJSONObject(it).optBoolean("connected")}}
    private fun members(s:JSONObject):List<JSONObject>{val a=s.optJSONArray("members") ?: return emptyList();return (0 until a.length()).map{a.getJSONObject(it)}}
    @Composable private fun Members(s:JSONObject){LazyColumn(verticalArrangement=Arrangement.spacedBy(14.dp)){item{Text("Your band",style=MaterialTheme.typography.headlineLarge);FosaLabel("${members(s).size} MEMBERS · PRIVATE LAN")};if(s.optBoolean("host"))item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FosaButton("INVITE",Modifier.weight(1f)){modal="invite"};FosaButton("WEB PAIR",Modifier.weight(1f),secondary=true){modal="pair"}}};members(s).forEach{u->item{val id=u.getString("id");FosaChannel(u.optString("name"),u.optString("role"),u.optBoolean("talk"),u.optDouble("level").takeIf{it.isFinite()},connected=u.optBoolean("online")){if(id!=s.optString("id")){val a=s.optJSONArray("mutedMembers");val muted=a!=null&&(0 until a.length()).any{a.optString(it)==id};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FosaButton("TALK",Modifier.weight(1f),secondary=true){MobileService.instance?.target("user:$id");tab="TALK"};FosaButton(if(muted)"UNMUTE" else "MUTE",Modifier.weight(1f),secondary=true){MobileService.instance?.memberMute(id,!muted)}}};if(s.optBoolean("host")){var group by remember(id){mutableStateOf(u.optString("group","BAND"))};Field("GROUP",group){group=it};FosaButton("SAVE GROUP",secondary=true){MobileService.instance?.group(id,group)}}else FosaLabel("GROUP · ${u.optString("group")}")}}}}}
    @Composable private fun Status(s:JSONObject){LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)){item{Text("Session health",style=MaterialTheme.typography.headlineLarge)};item{FosaPanel{FosaLabel("LOCAL NETWORK");FosaStatus(if(s.optBoolean("controlConnected"))"CONNECTED" else "RECONNECTING",s.optBoolean("controlConnected"));FosaLabel("INTERNET");Text("Not required",style=MaterialTheme.typography.titleLarge);FosaLabel("AUDIO LATENCY");Text("UNKNOWN",fontFamily=FontFamily.Monospace);FosaLabel("SESSION");Text(s.optString("sessionName"));FosaLabel("OUTPUT");Text(s.optString("output"));FosaLabel("BATTERY");Text("${s.optInt("battery")}%")}};item{FosaPanel{FosaLabel("DIRECT AUDIO LINKS");val a=s.optJSONArray("metrics");if(a==null||a.length()==0)Text("Waiting for another member");else for(i in 0 until a.length()){val m=a.getJSONObject(i);val u=members(s).find{it.optString("id")==m.getString("id")};Text(u?.optString("name") ?: "Member",fontWeight=FontWeight.Bold);FosaStatus(if(m.optBoolean("connected"))"SRTP CONNECTED" else "CONNECTING",m.optBoolean("connected"));Text("RTT ${metric(m,"rttMs","ms")} · Jitter ${metric(m,"jitterMs","ms")}\nLoss ${metric(m,"loss","%")}",fontFamily=FontFamily.Monospace,fontSize=12.sp)}}};item{FosaButton("ADVANCED DIAGNOSTICS",Modifier.fillMaxWidth(),secondary=true){modal="advanced"}}}}
    private fun metric(j:JSONObject,k:String,unit:String)=if(j.has(k))"${"%.1f".format(java.util.Locale.US,j.optDouble(k))} $unit · MEASURED" else "UNKNOWN"
    @Composable private fun SettingsScreen(s:JSONObject){val volume=s.optDouble("master",.75).toFloat();LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)){item{Text("Make it yours",style=MaterialTheme.typography.headlineLarge)};item{FosaPanel{FosaLabel("AUDIO");Text(s.optString("output"));FosaSlider("TALKBACK VOLUME",volume){MobileService.instance?.volume(it.toDouble())};FosaStatus("VOICE PROCESSING · WEBRTC APM");Text("Echo cancellation, noise suppression, voice gain. Mic gain / gate / EQ personnalisés : non disponibles.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);FosaButton("MICROPHONE PERMISSION",secondary=true){startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}}};item{FosaPanel{FosaLabel("LIVE");Toggle("Haptic feedback",haptic){haptic=it};Toggle("Performance mode",performance){performance=it};Toggle("Keep screen awake",awake){awake=it;if(it)window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)};Text("Priority Talk : non disponible",color=MaterialTheme.colorScheme.onSurfaceVariant)}};item{FosaPanel{FosaLabel("APP");Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("DARK","LIGHT","SYSTEM").forEach{t->FosaButton(t,Modifier.weight(1f),secondary=theme!=t){theme=t;getSharedPreferences("mobile-ui",MODE_PRIVATE).edit().putString("theme",t).apply()}}};FosaButton("OFFLINE PACKAGE",Modifier.fillMaxWidth(),secondary=true){modal="offline"};FosaButton("ADVANCED / DIAGNOSTICS",Modifier.fillMaxWidth(),secondary=true){modal="advanced"};FosaLabel("FOSA MOBILE · 0.11.0 · NATIVE")}};item{FosaButton("LEAVE SESSION",Modifier.fillMaxWidth(),secondary=true){leave()};Spacer(Modifier.height(10.dp))}}}
    @Composable private fun Toggle(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(value,onChange)}}
    @Composable private fun Qr(value:String){val bitmap=remember(value){try{val m=QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,512,512);Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888).also{b->for(x in 0..511)for(y in 0..511)b.setPixel(x,y,if(m[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)}}catch(_:Exception){null}};if(bitmap!=null)Image(bitmap.asImageBitmap(),"Private session QR",Modifier.fillMaxWidth().height(260.dp).background(androidx.compose.ui.graphics.Color.White)) else Text("QR trop grand. Utilise COPY ANSWER.")}
    @Suppress("DEPRECATION") private fun scan(){if(discovery!=null)return;discoveries.clear();val nsd=getSystemService(NsdManager::class.java);discovery=object:NsdManager.DiscoveryListener{
        override fun onDiscoveryStarted(t:String){}
        override fun onDiscoveryStopped(t:String){discovery=null}
        override fun onStartDiscoveryFailed(t:String,e:Int){discovery=null}
        override fun onStopDiscoveryFailed(t:String,e:Int){discovery=null}
        override fun onServiceLost(i:NsdServiceInfo){runOnUiThread{discoveries.removeAll{it.first==i.serviceName}}}
        override fun onServiceFound(i:NsdServiceInfo){nsd.resolveService(i,object:NsdManager.ResolveListener{override fun onResolveFailed(i:NsdServiceInfo,e:Int){};override fun onServiceResolved(i:NsdServiceInfo){val ip=i.host?.hostAddress ?: return;if(!LanAddress.privateV4(ip))return;runOnUiThread{val p=i.serviceName to "http://$ip:${i.port}";if(p !in discoveries)discoveries.add(p)}}})}
    };nsd.discoverServices("_fosa-mobile._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery)}
    override fun onPause(){MobileService.instance?.push(false);super.onPause()}
    override fun onDestroy(){discovery?.let{try{getSystemService(NsdManager::class.java).stopServiceDiscovery(it)}catch(_:Exception){}};super.onDestroy()}
}
