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
import com.arizona.fosa.AudioModeSwitch
import com.google.zxing.*
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap

@OptIn(ExperimentalMaterial3Api::class)
class MobileActivity:ComponentActivity(){
    private var route by mutableStateOf("home");private var tab by mutableStateOf("TALK")
    private var startupVisible by mutableStateOf(true)
    private var name by mutableStateOf("ZO");private var role by mutableStateOf("SAX");private var sessionName by mutableStateOf("BAND LIVE")
    private var address by mutableStateOf("");private var code by mutableStateOf("");private var theme by mutableStateOf("DARK")
    private var haptic by mutableStateOf(true)
    private var performance by mutableStateOf(false);private var awake by mutableStateOf(false);private var modal by mutableStateOf("")
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){MobileService.instance?.armMic();val next=pendingNetwork;pendingNetwork=null;next?.invoke()}
    override fun onCreate(b:Bundle?){super.onCreate(b);WindowCompat.setDecorFitsSystemWindows(window,false)
        val prefs=getSharedPreferences("mobile-ui",MODE_PRIVATE);name=prefs.getString("name","ZO") ?: "ZO";role=prefs.getString("role","SAX") ?: "SAX";theme=prefs.getString("theme","DARK") ?: "DARK"
        startupVisible=b==null&&MobileService.instance==null
        setContent{FosaTheme(theme,haptic){if(startupVisible){FosaSplash();LaunchedEffect(Unit){kotlinx.coroutines.delay(FosaMotion.StartupMs);startupVisible=false}}else MobileRoot()}};handle(intent)
    }
    override fun onNewIntent(i:Intent){super.onNewIntent(i);setIntent(i);handle(i)}
    private fun handle(i:Intent){i.data?.let{u->if(u.host=="join")try{consumeSessionQr(PairingQr.parse(u.toString()))}catch(e:Exception){modeError=e.message.orEmpty()} else if(u.host=="device")MobileService.instance?.addDevice(u.toString()) else if(u.host=="mobile-pair")MobileService.instance?.importWeb(u.toString()) else if(u.host=="mobile"){address=u.getQueryParameter("address") ?: "";code=u.getQueryParameter("code") ?: "";route="join"};Unit}}
    private fun askMic(){val list=mutableListOf(Manifest.permission.RECORD_AUDIO);if(android.os.Build.VERSION.SDK_INT>=33)list.add(Manifest.permission.POST_NOTIFICATIONS);permissions.launch(list.toTypedArray())}
    private var modeError by mutableStateOf("")
    private var pendingNetwork:(()->Unit)?=null
    private var joiningManager:FosaConnectionManager?=null
    private var nearby by mutableStateOf(emptyList<LocalSession>())
    private var deviceInvite:DeviceInvitation?=null
    private var deviceQr by mutableStateOf("")
    private var scanForDevice=false
    private val scanner=registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()){result->
        val value=result.contents
        if(!value.isNullOrBlank())try{val u=PairingQr.parse(value);if(scanForDevice){require(u.host=="device"){"Scanne le QR de l’appareil invité"};MobileService.instance?.addDevice(value)} else {require(u.host=="join"){"Scanne le QR de la session"};consumeSessionQr(u)}}catch(e:Exception){modeError=e.message ?: "QR invalide"}
    }
    private fun scan(device:Boolean=false){scanForDevice=device;scanner.launch(com.journeyapps.barcodescanner.ScanOptions().setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE).setPrompt(if(device)"Scanne le QR de l’appareil invité" else "Scanne le QR court de la session").setBeepEnabled(false).setOrientationLocked(false))}
    private fun consumeSessionQr(u:Uri){address=u.getQueryParameter("h")?.let{"http://$it:${u.getQueryParameter("p") ?: "48765"}"} ?: "";code=u.getQueryParameter("c").orEmpty();joiningSession=u.getQueryParameter("s").orEmpty();route="join";modal="";launch(false)}
    private var joiningSession=""
    private var hostNetwork by mutableStateOf("CURRENT")
    private fun networkPermission(ready:()->Unit){val permission=if(android.os.Build.VERSION.SDK_INT>=33)Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if(checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED)ready() else {pendingNetwork=ready;permissions.launch(arrayOf(permission))}
    }
    private fun showDeviceQr(){networkPermission{
        deviceInvite?.close();deviceInvite=DeviceInvitation(this,name,role){value->deviceInvite?.close();deviceInvite=null;consumeSessionQr(PairingQr.parse(value))};deviceQr=deviceInvite!!.qr;modal="device"
    }}
    private fun closeModal(){if(modal=="device"){deviceInvite?.close();deviceInvite=null};modal=""}
    private fun discoverNearby(ask:Boolean=true){val start:()->Unit={
        joiningManager?.close();joiningManager=FosaConnectionManager(this){_,_,message->if(message.isNotBlank())runOnUiThread{modeError=message}}
        joiningManager!!.nearby{items->runOnUiThread{nearby=items}}
    };if(ask)networkPermission(start)else start()}
    private fun launch(host:Boolean){networkPermission{performLaunch(host)}}
    private fun performLaunch(host:Boolean){joiningManager?.close();joiningManager=null;deviceInvite?.close();deviceInvite=null;getSharedPreferences("mobile-ui",MODE_PRIVATE).edit().putString("name",name).putString("role",role).apply();modeError="";AudioModeSwitch.toMobile(this,{
        startForegroundService(Intent(this,MobileService::class.java).setAction("START").putExtra("host",host).putExtra("name",name).putExtra("role",role).putExtra("session",sessionName).putExtra("address",address).putExtra("code",code).putExtra("sessionId",joiningSession).putExtra("network",hostNetwork));tab="TALK"
    },{modeError=it})}
    private fun openBodypack(){MobileService.instance?.push(false);startActivity(Intent(this,BodypackActivity::class.java))}
    private fun leave(){MobileService.instance?.disconnect() ?: stopService(Intent(this,MobileService::class.java));route="home"}
    @Composable private fun MobileRoot(){val s=MobileService.state;val active=s.optBoolean("active")
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=20.dp)){
            FosaAppBar(status=if(s.optBoolean("talk"))"TALK" else if(active)"LOCAL" else "LAN FIRST",good=!active||s.optBoolean("controlConnected"))
            if(modeError.isNotBlank())Text(modeError,color=FosaColors.Warning)
            if(active){Column(Modifier.weight(1f)){if(s.optString("error").isNotBlank())Text(s.optString("error"),color=FosaColors.Warning,modifier=Modifier.padding(vertical=8.dp));when(tab){"TALK"->Talk(s);"MEMBERS"->Members(s);"STATUS"->Status(s);else->SettingsScreen(s)}}
                Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("TALK","MEMBERS","STATUS","SETTINGS").forEach{item->FosaButton(item,Modifier.weight(1f).testTag("nav-$item"),secondary=tab!=item,compact=true){tab=item}}}
            }else{key(route){LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(20.dp)){item{if(route=="home")Home() else Form(route=="create")};if(s.optString("error").isNotBlank())item{FosaPanel{Text(s.optString("error"),color=FosaColors.Warning);FosaButton("RETRY"){leave()}}}}}}
        }
        if(modal.isNotEmpty())ModalBottomSheet(onDismissRequest={closeModal()},containerColor=MaterialTheme.colorScheme.surface){Column(Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
            when(modal){"users"->{Text("Talk to a member",style=MaterialTheme.typography.titleLarge);members(s).filter{it.optString("id")!=s.optString("id")}.forEach{u->FosaButton("${u.optString("name")} · ${u.optString("role")}",Modifier.fillMaxWidth(),secondary=true){MobileService.instance?.target("user:${u.getString("id")}");modal=""}}}
                "groups"->{Text("Talk to a group",style=MaterialTheme.typography.titleLarge);members(s).map{it.optString("group","BAND")}.distinct().forEach{g->FosaButton(g,Modifier.fillMaxWidth(),secondary=true){MobileService.instance?.target("group:$g");modal=""}}}
                "listen"->{Text("Listen to",style=MaterialTheme.typography.titleLarge);FosaButton("ALL",Modifier.fillMaxWidth(),secondary=true){MobileService.instance?.listen("all");modal=""};if(!s.optBoolean("host"))FosaButton("LEADER",Modifier.fillMaxWidth(),secondary=true){MobileService.instance?.listen("leader");modal=""};members(s).map{it.optString("group","BAND")}.distinct().forEach{g->FosaButton("GROUP · $g",Modifier.fillMaxWidth(),secondary=true){MobileService.instance?.listen("group:$g");modal=""}};members(s).filter{it.optString("id")!=s.optString("id")}.forEach{member->FosaButton("USER · ${member.optString("name")}",Modifier.fillMaxWidth(),secondary=true){MobileService.instance?.listen("user:${member.getString("id")}");modal=""}}}
                "offline"->{Text("Offline package",style=MaterialTheme.typography.headlineLarge);FosaStatus("NATIVE · READY");Text("Interface, moteur audio et icônes sont intégrés à l’APK. Aucun téléchargement n’est requis pendant la session.");FosaButton("CHECK OFFLINE READY"){modal="offline-ready"}}
                "offline-ready"->{FosaStatus("APP SHELL · AUDIO · UI · ICONS READY");Text("Le LAN reste nécessaire pour rejoindre les autres membres. Internet est facultatif.")}
                "invite"->{Text("Invite a member",style=MaterialTheme.typography.titleLarge);Qr(s.optString("join"));FosaLabel("SESSION CODE");Text(s.optString("code"),style=MaterialTheme.typography.displayLarge,fontFamily=FontFamily.Monospace);Text("Le code ou un seul scan suffit. Internet n’est pas nécessaire.")}
                "device"->{Text("Display pairing QR",style=MaterialTheme.typography.titleLarge);Qr(deviceQr);Text("L’hôte choisit MEMBERS → ADD DEVICE et scanne une seule fois. Garde cet écran ouvert. Ce QR expire après 90 secondes.")}
                "web"->{Text("FOSA WEB",style=MaterialTheme.typography.titleLarge);if(s.optString("web").isNotBlank()){Qr(s.getString("web"));Text(s.getString("web"));val clipboard=LocalClipboardManager.current;FosaButton("COPY LINK"){clipboard.setText(androidx.compose.ui.text.AnnotatedString(s.getString("web")))};Text("Pour le micro et la PWA : faire confiance au certificat de cet hôte une fois. Aucun Internet requis.");Text(s.optString("webSetup"));Text("Empreinte à vérifier :\n${s.optString("fingerprint")}",fontSize=11.sp)}else Text(s.optString("webError","Web sécurisé indisponible"))}
                "hotspot"->{Text("Local FOSA network",style=MaterialTheme.typography.titleLarge);if(s.optString("hotspotName").isBlank())Text(s.optString("error").ifBlank{"Création du réseau local…"}) else {var reveal by remember(s.optString("hotspotName")){mutableStateOf(false)};val clipboard=LocalClipboardManager.current;FosaLabel("SCAN TO CONNECT TO WI-FI");Qr(s.optString("wifiQr"),"Connect to Wi-Fi QR");FosaLabel("NETWORK · PROVIDED BY ANDROID");Text(s.optString("hotspotName"));FosaLabel("WIFI PASSWORD");Text(if(reveal)s.optString("hotspotPassword") else "••••••••");Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FosaButton(if(reveal)"HIDE" else "SHOW",secondary=true){reveal=!reveal};FosaButton("COPY",secondary=true){clipboard.setText(androidx.compose.ui.text.AnnotatedString(s.optString("hotspotPassword")))}};Text("Ce QR connecte au Wi-Fi. Rejoins ensuite la session FOSA avec son code ou le QR ci-dessous.");if(s.optString("join").isNotBlank()){FosaLabel("SCAN TO JOIN FOSA SESSION");Qr(s.optString("join"),"Join FOSA session QR");Text(s.optString("code"),fontFamily=FontFamily.Monospace)}}}
                "audio"->{Text("AUDIO DIAGNOSTICS",style=MaterialTheme.typography.titleLarge);AudioHealth(s);FosaButton("TEST AUDIO · 2 SECONDS",Modifier.fillMaxWidth(),enabled=s.optBoolean("mic")&&!s.optBoolean("muted")){MobileService.instance?.testAudio()};Text("Envoie un signal de 660 Hz dans le véritable encodeur Opus vers la destination choisie. Demande au destinataire de vérifier son niveau RX et l’écoute. Ne mesure pas la latence ni l’audibilité physique.");Text(s.optJSONObject("audioDiagnostics")?.toString(2) ?: "Capture non démarrée",fontFamily=FontFamily.Monospace,fontSize=11.sp);Text("Micro système mute : ${s.optBoolean("microphoneSystemMuted")} · volume sortie Android : ${s.optInt("outputSystemVolume")}");val a=s.optJSONArray("metrics");if(a!=null)for(i in 0 until a.length())Text(a.getJSONObject(i).toString(2),fontSize=11.sp,fontFamily=FontFamily.Monospace)}
                "network"->{Text("ADVANCED NETWORK SETTINGS",style=MaterialTheme.typography.titleLarge);Text("Réseau actuel : ${LanAddress.currentIp(this@MobileActivity) ?: "aucune IP Wi-Fi/Ethernet"}\nInterfaces locales : ${LanAddress.ips().joinToString()}\nHôte : ${s.optString("address")}\nWeb HTTPS : ${s.optString("web")}");Text("FOSA conserve la configuration de ton routeur. Vérifie le même sous-réseau, le Wi-Fi invité et l’isolation des clients, le pare-feu TCP de l’hôte et les flux UDP WebRTC. mDNS bloqué : utilise le QR ou l’adresse manuelle. Une connexion de contrôle seule ne prouve pas que le média passe.");FosaButton("OPEN WI-FI SETTINGS",secondary=true){startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))}}

                "pair"->{Text("Pair a Web fallback",style=MaterialTheme.typography.titleLarge);Text("Secours hors ligne uniquement : utilise ce mode si l'appairage Web par code ne peut pas joindre le rendez-vous. Le parcours normal ne demande aucun scan.");if(s.optString("answer").isNotBlank()){Qr(s.getString("answer"));FosaLabel("ANSWER · à scanner depuis la PWA ouverte");val clipboard=LocalClipboardManager.current;FosaButton("COPY ANSWER"){clipboard.setText(androidx.compose.ui.text.AnnotatedString(s.getString("answer")))}}}
                else->{Text("Advanced diagnostics",style=MaterialTheme.typography.titleLarge);Text("${s.optString("address")}\nSession ${s.optString("sessionName")}\n${s.optString("transport")} · ${s.optString("connectionPhase")}\nSignalisation ${s.optString("signaling")} · Reconnexions ${s.optInt("reconnectCount")}\n8 membres maximum\nLatence audio : UNKNOWN");val metrics=s.optJSONArray("metrics");if(metrics!=null)for(i in 0 until metrics.length())Text(metrics.getJSONObject(i).toString(2),fontSize=11.sp,fontFamily=FontFamily.Monospace);Text("Le RTT mesure un aller-retour réseau. Il ne mesure pas la latence microphone → casque.");if(s.optBoolean("host")){Text("Option GitHub Pages : découverte Internet temporaire. WEB ACCESS et les apps natives restent entièrement locaux.");FosaButton(if(s.optBoolean("webPairing"))"INTERNET DISCOVERY ACTIVE" else "ENABLE INTERNET DISCOVERY · 2 MIN",secondary=true){MobileService.instance?.enableWebPairing()}}}
            };FosaButton("CLOSE",Modifier.fillMaxWidth(),secondary=true){closeModal()};Spacer(Modifier.height(24.dp))}}
    }
    @Composable private fun Home(){Column(verticalArrangement=Arrangement.spacedBy(20.dp)){
        Spacer(Modifier.height(8.dp));FosaLabel("PRIVATE NETWORK INTERCOM");Text("Your stage.\nYour connection.",style=MaterialTheme.typography.headlineLarge);Text("Parlez sur votre réseau local.\nSans PC. Sans compte cloud.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        FosaPanel{FosaLabel("01 / MOBILE NATIVE");Text("Create a session",style=MaterialTheme.typography.titleLarge);Text("Un téléphone accueille votre groupe.");FosaButton("CREATE SESSION",Modifier.fillMaxWidth().testTag("home-create")){route="create"}}
        FosaPanel{FosaLabel("02 / JOIN LOCAL");Text("Join your band",style=MaterialTheme.typography.titleLarge);Text("Rejoignez une session à proximité.");FosaButton("JOIN SESSION",Modifier.fillMaxWidth().testTag("home-join"),secondary=true){route="join"}}
        FosaPanel{FosaLabel("03 / BODYPACK");Text("MR18 personal monitoring",style=MaterialTheme.typography.titleMedium);Text("Connecte-toi au serveur FOSA du PC, sur le même réseau local.",style=MaterialTheme.typography.bodySmall);FosaButton("OPEN BODYPACK",Modifier.fillMaxWidth().testTag("home-bodypack"),secondary=true){openBodypack()}}
        FosaStatus("LOCAL NETWORK · INTERNET NOT REQUIRED");Spacer(Modifier.height(12.dp))
    }}
    @Composable private fun Field(label:String,value:String,onChange:(String)->Unit){OutlinedTextField(value,onChange,label={FosaLabel(label)},singleLine=true,shape=FosaRadius.Control,modifier=Modifier.fillMaxWidth().testTag(label))}
    @Composable private fun Form(host:Boolean){LaunchedEffect(host){if(!host)discoverNearby(false)};Column(verticalArrangement=Arrangement.spacedBy(16.dp)){FosaButton("‹ BACK",secondary=true){route="home"};FosaLabel(if(host)"CREATE PRIVATE SESSION" else "JOIN LOCAL SESSION");Text(if(host)"Bring your band\ntogether." else "Enter the code.\nThat's all.",style=MaterialTheme.typography.headlineLarge)
        if(!host){Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){FosaButton("NEARBY",Modifier.weight(1f),secondary=true){discoverNearby()};FosaButton("ENTER CODE",Modifier.weight(1f),secondary=true){address="";joiningSession=""};FosaButton("SCAN QR",Modifier.weight(1f),secondary=true){scan()}}
            nearby.forEach{found->FosaPanel{Text(found.name,style=MaterialTheme.typography.titleLarge);Text("${found.host} · ${found.role}");FosaStatus(found.kind.label);Text("${found.members} MEMBERS");FosaButton("JOIN",Modifier.fillMaxWidth(),secondary=true){address=found.address;code=found.code;joiningSession=found.id;launch(false)}}}
            FosaButton("DISPLAY PAIRING QR",Modifier.fillMaxWidth(),secondary=true){showDeviceQr()}
        }
        FosaPanel{
            if(host)Field("SESSION NAME",sessionName){sessionName=it}
            if(host){FosaLabel("NETWORK SETUP");FosaButton("USE CURRENT NETWORK",Modifier.fillMaxWidth(),secondary=hostNetwork!="CURRENT"){hostNetwork="CURRENT"};Text("LAN actuel : ${LanAddress.currentIp(this@MobileActivity) ?: "adresse locale indisponible"}",style=MaterialTheme.typography.bodySmall);FosaButton("CREATE LOCAL FOSA NETWORK",Modifier.fillMaxWidth(),secondary=hostNetwork!="HOTSPOT"){hostNetwork="HOTSPOT";networkPermission{startForegroundService(Intent(this@MobileActivity,MobileService::class.java).setAction("HOTSPOT"));modal="hotspot"}};FosaButton("WI-FI DIRECT",Modifier.fillMaxWidth(),secondary=hostNetwork!="WIFI_DIRECT"){hostNetwork="WIFI_DIRECT"};FosaButton("ADVANCED NETWORK SETTINGS",Modifier.fillMaxWidth(),secondary=true){modal="network"}}
            if(!host){
                FosaLabel("SESSION CODE · 6 DIGITS")
                Field("SESSION CODE",code){code=it.filter{c->c.isDigit()}.take(6);joiningSession="";address=""}
                FosaStatus("CODE ONLY · AUTO DISCOVERY")
                Text("FOSA trouve automatiquement l'hôte correspondant sur le même Wi-Fi ou hotspot. Aucun QR ni sélection d'appareil.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Field("YOUR NAME",name){name=it};Field("ROLE / INSTRUMENT",role){role=it}
            if(!host){var manual by remember{mutableStateOf(false)};FosaButton("MANUAL / ADVANCED",secondary=true){manual=!manual};if(manual)Field("HOST ADDRESS",address){address=it}}
            FosaStatus(if(LanAddress.ip(this@MobileActivity)!=null)"LOCAL NETWORK READY" else "WI-FI DIRECT AVAILABLE IF SUPPORTED",true)
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)FosaButton("ENABLE MICROPHONE",Modifier.fillMaxWidth(),secondary=true){askMic()}
            FosaButton(if(host)"CREATE SESSION" else "JOIN WITH CODE",Modifier.fillMaxWidth().testTag("submit-session"),enabled=name.isNotBlank()&&(host||code.length==6)){launch(host)}
            Text(if(host)"Internet not required." else "Le code suffit en natif. Internet n'est pas requis pour l'audio.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }}
    @Composable private fun Talk(s:JSONObject){val peers=members(s);val listen=s.optString("listenTarget","all");val listenLabel=when{listen.startsWith("user:")->peers.find{it.optString("id")==listen.removePrefix("user:")}?.optString("name") ?: "USER";listen.startsWith("group:")->listen.removePrefix("group:");else->listen.uppercase()};BoxWithConstraints(Modifier.fillMaxSize()){
        val wide=maxWidth>600.dp||maxWidth>maxHeight*1.4f
        val compact=wide&&maxHeight<420.dp
        val target=s.optString("target")
        val label=when{target.startsWith("user:")->peers.find{it.optString("id")==target.removePrefix("user:")}?.optString("name") ?: "Member";target.startsWith("group:")->target.removePrefix("group:");else->target.uppercase()}
        val chooseTarget:(String)->Unit={t->when(t){"USER"->modal="users";"GROUP"->modal="groups";else->MobileService.instance?.target(t.lowercase())}}
        val panic:@Composable ()->Unit={FosaButton(if(s.optBoolean("muted"))"UNMUTE" else "PANIC MUTE",Modifier.fillMaxWidth().testTag("panic-button"),secondary=!s.optBoolean("muted")){MobileService.instance?.panic(!s.optBoolean("muted"))}}
        val controls:@Composable ()->Unit={Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
            TalkModes(s);FosaLabel("TALK TO");Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("ALL","GROUP","LEADER","USER").forEach{t->FosaButton(t,Modifier.weight(1f),secondary=true,compact=true,enabled=t!="LEADER"||!s.optBoolean("host")){chooseTarget(t)}}}
            Text(label,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
            val audioReady=hasAudioPeer(s);val pttEnabled=s.optBoolean("mic")&&!s.optBoolean("muted");val hint=pttHint(s,audioReady)
            FosaTalkButton(s.optBoolean("talk"),s.optBoolean("talkArmed")&&!s.optBoolean("talk"),pttEnabled,{MobileService.instance?.push(it)},Modifier.widthIn(max=280.dp).fillMaxWidth(.8f),hint,s.optString("talkMode","HOLD"))
            FosaMeter(s.optDouble("level").takeIf{s.optBoolean("capturing")&&it.isFinite()},Modifier.widthIn(max=300.dp));FosaLabel(if(s.optBoolean("talk"))"MIC INPUT · MEASURED" else if(s.optBoolean("talkRequested"))"AUDIO REPAIR · HOLD" else hint)
            if(!audioReady&&members(s).size>1)FosaButton("RECONNECT AUDIO NOW",Modifier.fillMaxWidth(),secondary=true){MobileService.instance?.repairAudio()}
            if(!s.optBoolean("mic"))FosaButton("ENABLE MICROPHONE"){askMic()}
            panic()
        }}
        val details:@Composable ()->Unit={Column(verticalArrangement=Arrangement.spacedBy(if(compact)8.dp else 16.dp)){if(compact)TalkModes(s);FosaLabel(s.optString("sessionName"));Text("${s.optString("name")} — ${s.optString("role")}",style=if(compact)MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){FosaStatus(if(s.optBoolean("controlConnected"))"LOCAL" else "RECONNECTING",s.optBoolean("controlConnected"));FosaLabel("${peers.size} MEMBERS")};FosaButton("LISTEN · $listenLabel",Modifier.fillMaxWidth(),secondary=true,compact=true){modal="listen"};if(!performance&&wide&&!compact)FosaPanel{FosaLabel("AUDIO OUTPUT");Text(s.optString("output"));FosaLabel("AUDIO LATENCY · UNKNOWN");Text("End-to-end measurement required",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};if(!wide||compact)Text(s.optString("output"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);if(!compact)AudioHealth(s);if(compact&&!s.optBoolean("mic"))FosaButton("ENABLE MICROPHONE"){askMic()};peers.filter{it.optBoolean("talk")&&it.optString("id")!=s.optString("id")}.take(if(compact)1 else if(wide)8 else 1).forEach{u->if(compact)FosaStatus("${u.optString("name")} · TALKING") else FosaChannel(u.optString("name"),u.optString("role"),true,u.optDouble("level").takeIf{it.isFinite()}){}}}}
        if(compact)Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.weight(.65f).fillMaxHeight().verticalScroll(rememberScrollState())){details()}
            Row(Modifier.weight(1.35f),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    FosaLabel("TALK TO · $label")
                    listOf(listOf("ALL","GROUP"),listOf("LEADER","USER")).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){row.forEach{t->FosaButton(t,Modifier.weight(1f),secondary=true,compact=true,enabled=t!="LEADER"||!s.optBoolean("host")){chooseTarget(t)}}}}
                    panic()
                }
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
                    val audioReady=hasAudioPeer(s);val pttEnabled=s.optBoolean("mic")&&!s.optBoolean("muted")
                    FosaTalkButton(s.optBoolean("talk"),s.optBoolean("talkArmed")&&!s.optBoolean("talk"),pttEnabled,{MobileService.instance?.push(it)},Modifier.size(180.dp),pttHint(s,audioReady),s.optString("talkMode","HOLD"))
                    FosaMeter(s.optDouble("level").takeIf{s.optBoolean("capturing")&&it.isFinite()},Modifier.width(180.dp))
                }
            }
        }else if(wide)Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(24.dp)){Box(Modifier.weight(1f)){details()};Box(Modifier.weight(1f)){controls()}} else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)){details();controls();Spacer(Modifier.height(12.dp))}
    }}
    @Composable private fun TalkModes(s:JSONObject){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("HOLD","TAP","AUTO").forEach{mode->FosaButton(mode,Modifier.weight(1f).testTag("mode-$mode"),secondary=s.optString("talkMode","HOLD")!=mode,compact=true){MobileService.instance?.talkMode(mode)}}}
    @Composable private fun AudioHealth(s:JSONObject){Column(verticalArrangement=Arrangement.spacedBy(5.dp)){FosaLabel("MICROPHONE · ${if(s.optBoolean("capturing"))"CAPTURING" else "OFF"}");FosaLabel("TRANSMISSION · ${if(s.optBoolean("sending"))"SENDING" else "IDLE"}");FosaLabel("RECEPTION · ${if(s.optBoolean("receiving"))"RECEIVING" else "WAITING"}");FosaMeter(s.optDouble("receiveLevel").takeIf{it.isFinite()});if(s.optBoolean("talkArmed")&&s.optString("talkMode")!="HOLD")FosaStatus("${s.optString("talkMode")} · MICROPHONE ARMED",false)}}
    private fun pttHint(s:JSONObject,audioReady:Boolean):String=when{
        !s.optBoolean("mic")->"Microphone required"
        s.optBoolean("muted")->"Unmute to talk"
        members(s).size<=1->"Waiting for another member"
        audioReady->"Hold to transmit"
        !s.optBoolean("controlConnected")->"Control reconnecting · hold to retry audio"
        else->"Audio connecting · hold to repair"
    }
    private fun hasAudioPeer(s:JSONObject):Boolean{val a=s.optJSONArray("metrics") ?: return false;val target=s.optString("target","all")
        return (0 until a.length()).any{val link=a.getJSONObject(it);val id=link.optString("id");val member=members(s).find{it.optString("id")==id}
            link.optBoolean("connected")&&(target=="all"||target=="user:$id"||target=="leader"&&member?.optBoolean("leader")==true||target.startsWith("group:")&&member?.optString("group")==target.removePrefix("group:"))}
    }
    private fun members(s:JSONObject):List<JSONObject>{val a=s.optJSONArray("members") ?: return emptyList();return (0 until a.length()).map{a.getJSONObject(it)}}
    @Composable private fun Members(s:JSONObject){LazyColumn(verticalArrangement=Arrangement.spacedBy(14.dp)){item{Text("Your band",style=MaterialTheme.typography.headlineLarge);FosaLabel("${members(s).size} MEMBERS · PRIVATE LAN")};if(s.optBoolean("host"))item{FosaPanel{FosaLabel("SESSION CODE");Text(s.optString("code"),style=MaterialTheme.typography.displayLarge,fontFamily=FontFamily.Monospace);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FosaButton("ADD DEVICE",Modifier.weight(1f)){scan(true)};FosaButton("SHOW QR",Modifier.weight(1f),secondary=true){modal="invite"}};FosaButton("WEB ACCESS",Modifier.fillMaxWidth(),secondary=true){modal="web"};FosaButton("CREATE LOCAL FOSA NETWORK",Modifier.fillMaxWidth(),secondary=true){networkPermission{MobileService.instance?.createLocalNetwork();modal="hotspot"}}}};members(s).forEach{u->item{val id=u.getString("id");FosaChannel(u.optString("name"),u.optString("role"),u.optBoolean("talk"),u.optDouble("level").takeIf{it.isFinite()},connected=u.optBoolean("online")){if(id!=s.optString("id")){val a=s.optJSONArray("mutedMembers");val muted=a!=null&&(0 until a.length()).any{a.optString(it)==id};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FosaButton("TALK",Modifier.weight(1f),secondary=true){MobileService.instance?.target("user:$id");tab="TALK"};FosaButton(if(muted)"UNMUTE" else "MUTE",Modifier.weight(1f),secondary=true){MobileService.instance?.memberMute(id,!muted)}}};if(s.optBoolean("host")){var group by remember(id){mutableStateOf(u.optString("group","BAND"))};Field("GROUP",group){group=it};FosaButton("SAVE GROUP",secondary=true){MobileService.instance?.group(id,group)};if(id!=s.optString("id")){Toggle("Allow talk",u.optBoolean("canTalk",true)){MobileService.instance?.permissions(id,it,u.optBoolean("canListen",true))};Toggle("Allow listening",u.optBoolean("canListen",true)){MobileService.instance?.permissions(id,u.optBoolean("canTalk",true),it)}}}else FosaLabel("GROUP · ${u.optString("group")}")}}}}}
    @Composable private fun Status(s:JSONObject){LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)){item{Text("Session health",style=MaterialTheme.typography.headlineLarge)};item{FosaPanel{FosaLabel("CONNECTION");Text(s.optString("transport","Local Wi-Fi"));FosaLabel("SIGNALING");Text("LOCAL");FosaStatus(if(s.optBoolean("controlConnected"))"CONNECTED" else "RECONNECTING",s.optBoolean("controlConnected"));FosaLabel("INTERNET");Text("Not required",style=MaterialTheme.typography.titleLarge);FosaLabel("AUDIO LATENCY");Text("UNKNOWN",fontFamily=FontFamily.Monospace);FosaLabel("SESSION");Text(s.optString("sessionName"));FosaLabel("OUTPUT");Text(s.optString("output"));FosaLabel("BATTERY");Text("${s.optInt("battery")}%")}};item{FosaPanel{FosaLabel("DIRECT AUDIO LINKS");val a=s.optJSONArray("metrics");if(a==null||a.length()==0)Text("Waiting for another member");else for(i in 0 until a.length()){val m=a.getJSONObject(i);val u=members(s).find{it.optString("id")==m.getString("id")};Text(u?.optString("name") ?: "Member",fontWeight=FontWeight.Bold);FosaStatus(if(m.optBoolean("connected"))"TRANSPORT CONNECTED" else "CONNECTING",m.optBoolean("connected"));Text("RTT ${metric(m,"rttMs","ms")} · Jitter ${metric(m,"jitterMs","ms")}\nLoss ${metric(m,"loss","%")}",fontFamily=FontFamily.Monospace,fontSize=12.sp)}}};item{FosaButton("ADVANCED DIAGNOSTICS",Modifier.fillMaxWidth(),secondary=true){modal="advanced"}}}}
    private fun metric(j:JSONObject,k:String,unit:String)=if(j.has(k))"${"%.1f".format(java.util.Locale.US,j.optDouble(k))} $unit · MEASURED" else "UNKNOWN"
    @Composable private fun SettingsScreen(s:JSONObject){val volume=s.optDouble("master",.75).toFloat();LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)){item{Text("Make it yours",style=MaterialTheme.typography.headlineLarge)};item{FosaPanel{FosaLabel("AUDIO");Text(s.optString("output"));FosaSlider("TALKBACK VOLUME",volume){MobileService.instance?.volume(it.toDouble())};FosaStatus("VOICE PROCESSING · WEBRTC APM");Text("Echo cancellation, noise suppression, voice gain. AUTO ouvre sur un seuil d’énergie ; la musique peut aussi le déclencher.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);val d=s.optJSONObject("audioDiagnostics") ?: JSONObject();TalkModes(s);FosaLabel("TAP / AUTO SAFETY TIMEOUT");Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf(0L to "OFF",300000L to "5 MIN",900000L to "15 MIN").forEach{(time,label)->FosaButton(label,Modifier.weight(1f),secondary=d.optLong("timeoutMs")!=time,compact=true){MobileService.instance?.audioOptions(timeout=time)}}};FosaLabel("AUTO · SENSITIVITY ${d.optInt("threshold",-36)} dBFS");Slider(d.optDouble("threshold",-36.0).toFloat(),{MobileService.instance?.audioOptions(threshold=it.toDouble())},valueRange=-60f..-12f);FosaLabel("AUTO · CLOSE DELAY ${d.optLong("closeDelayMs",650)} MS");Slider(d.optLong("closeDelayMs",650).toFloat(),{MobileService.instance?.audioOptions(closeDelay=it.toLong())},valueRange=150f..3000f);Toggle("Noise reduction",d.optBoolean("noiseReduction",true)){MobileService.instance?.audioOptions(noiseReduction=it)};Text("AUTO est désarmé par défaut. Réarme manuellement après une perte de réseau ou de permission.",style=MaterialTheme.typography.bodySmall);FosaButton("AUDIO DIAGNOSTICS",Modifier.fillMaxWidth(),secondary=true){modal="audio"};FosaButton("ADVANCED NETWORK SETTINGS",Modifier.fillMaxWidth(),secondary=true){modal="network"};FosaButton("MICROPHONE PERMISSION",secondary=true){startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}}};item{FosaPanel{FosaLabel("LIVE");Toggle("Haptic feedback",haptic){haptic=it};Toggle("Performance mode",performance){performance=it};Toggle("Keep screen awake",awake){awake=it;if(it)window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)};Text("Priority Talk : non disponible",color=MaterialTheme.colorScheme.onSurfaceVariant)}};item{FosaPanel{FosaLabel("APP");Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("DARK","LIGHT","SYSTEM").forEach{t->FosaButton(t,Modifier.weight(1f),secondary=theme!=t){theme=t;getSharedPreferences("mobile-ui",MODE_PRIVATE).edit().putString("theme",t).apply()}}};FosaButton("OFFLINE PACKAGE",Modifier.fillMaxWidth(),secondary=true){modal="offline"};FosaButton("OPEN BODYPACK",Modifier.fillMaxWidth().testTag("settings-bodypack"),secondary=true){openBodypack()};FosaButton("ADVANCED / DIAGNOSTICS",Modifier.fillMaxWidth(),secondary=true){modal="advanced"};FosaLabel("FOSA MOBILE · ${com.arizona.fosa.BuildConfig.VERSION_NAME} · NATIVE")}};item{FosaButton("LEAVE SESSION",Modifier.fillMaxWidth(),secondary=true){leave()};Spacer(Modifier.height(10.dp))}}}
    @Composable private fun Toggle(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(value,onChange)}}
    @Composable private fun Qr(value:String,description:String="Private session QR"){val bitmap=remember(value){try{val m=QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,512,512);Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888).also{b->for(x in 0..511)for(y in 0..511)b.setPixel(x,y,if(m[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)}}catch(_:Exception){null}};if(bitmap!=null)Image(bitmap.asImageBitmap(),description,Modifier.fillMaxWidth().height(260.dp).background(androidx.compose.ui.graphics.Color.White)) else Text("QR indisponible. Utilise le code de session.")}
    override fun onDestroy(){joiningManager?.close();deviceInvite?.close();super.onDestroy()}
    override fun onPause(){MobileService.instance?.pauseHold();super.onPause()}
}
