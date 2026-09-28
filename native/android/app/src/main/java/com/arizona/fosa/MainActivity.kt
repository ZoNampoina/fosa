package com.arizona.fosa

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import fosa.transport.NearbyTransport

class MainActivity : Activity(), NearbyTransport.Listener {
  private lateinit var transport: NearbyTransport
  private lateinit var status: TextView
  private lateinit var peers: TextView
  private lateinit var backgroundState: TextView
  private val names = linkedMapOf<String,String>()
  private val downloadUrl = "https://github.com/ZoNampoina/fosa/releases/latest/download/FOSA-Android.apk"
  private val webUrl = "https://zonampoina.github.io/fosa/"

  private val bg = Color.rgb(7,9,11)
  private val card = Color.rgb(16,20,25)
  private val line = Color.rgb(36,43,50)
  private val textColor = Color.rgb(243,246,248)
  private val muted = Color.rgb(140,152,162)
  private val green = Color.rgb(37,211,148)
  private val greenSoft = Color.rgb(18,55,43)

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    transport = NearbyTransport(this, listener=this)

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(14), dp(20), dp(14), dp(28))
      setBackgroundColor(bg)
    }

    val header = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(0,0,0,dp(10))
    }
    val logo = ImageView(this).apply {
      setImageResource(R.drawable.fosa_logo)
      contentDescription = "FOSA"
    }
    header.addView(logo, LinearLayout.LayoutParams(dp(54),dp(54)).apply { marginEnd=dp(12) })
    val brand = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    brand.addView(label("FOSA",26f,textColor,true))
    brand.addView(label("TALKBACK LIVE  ·  v0.8.0",10f,muted,true))
    header.addView(brand,LinearLayout.LayoutParams(0,-2,1f))
    root.addView(header)

    val connect = cardBox()
    connect.addView(section("CONNEXION LOCALE"))
    status = label("LOCAL NATIF · prêt",13f,green,true)
    connect.addView(status)
    backgroundState = label("ARRIÈRE-PLAN · se lance avec la session",11f,muted,false)
    connect.addView(backgroundState, blockParams(dp(3)))
    val name = EditText(this).apply {
      hint="Votre nom"
      setTextColor(textColor)
      setHintTextColor(muted)
      textSize=15f
      setPadding(dp(14),dp(12),dp(14),dp(12))
      background=rounded(Color.rgb(11,15,19),line,14)
    }
    connect.addView(name, blockParams(dp(8)))
    val host = actionButton("CRÉER UN GROUPE", true)
    val join = actionButton("REJOINDRE", false)
    connect.addView(host, blockParams(dp(8)))
    connect.addView(join, blockParams(dp(7)))
    peers=label("Aucun appareil connecté",14f,textColor,false)
    connect.addView(peers, blockParams(dp(10)))
    connect.addView(label("Les appareils FOSA proches se détectent automatiquement. La notification FOSA reste active lorsque vous ouvrez une autre application.",12f,muted,false))
    root.addView(connect, blockParams(dp(4)))

    val share = cardBox()
    share.addView(section("PARTAGER / INSTALLER"))
    share.addView(label("FOSA Android",18f,textColor,true))
    share.addView(label("Scannez pour installer l’application sur un autre appareil Android.",12f,muted,false))
    val qr = ImageView(this).apply {
      setImageBitmap(makeQr(downloadUrl, 480))
      adjustViewBounds=true
      setPadding(dp(14),dp(14),dp(14),dp(14))
      background=rounded(Color.WHITE,Color.TRANSPARENT,18)
      contentDescription="QR code de téléchargement FOSA"
    }
    share.addView(qr, LinearLayout.LayoutParams(-1,dp(300)).apply { topMargin=dp(12) })
    val browser=actionButton("OUVRIR FOSA DANS LE NAVIGATEUR", false)
    share.addView(browser, blockParams(dp(10)))
    share.addView(label("iPhone / iPad : ouvrir FOSA dans Safari. Android : application native recommandée pour le maintien de la session locale en arrière-plan.",11f,muted,false))
    root.addView(share, blockParams(dp(10)))

    setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })

    val permissions = mutableListOf(
      Manifest.permission.RECORD_AUDIO,
      Manifest.permission.BLUETOOTH_SCAN,
      Manifest.permission.BLUETOOTH_ADVERTISE,
      Manifest.permission.BLUETOOTH_CONNECT,
      Manifest.permission.NEARBY_WIFI_DEVICES
    )
    if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    requestPermissions(permissions.toTypedArray(),10)

    host.setOnClickListener {
      startPersistentService()
      transport.createGroup(name.text.toString().ifBlank{"FOSA"})
      status.text="GROUPE VISIBLE · en attente…"
      backgroundState.text="ARRIÈRE-PLAN ACTIF · notification permanente"
      backgroundState.setTextColor(green)
    }
    join.setOnClickListener {
      startPersistentService()
      transport.discoverGroups(name.text.toString().ifBlank{"Musicien"})
      status.text="RECHERCHE DES FOSA PROCHES…"
      backgroundState.text="ARRIÈRE-PLAN ACTIF · notification permanente"
      backgroundState.setTextColor(green)
    }
    browser.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))) }
  }

  private fun startPersistentService() {
    val intent = Intent(this, FosaForegroundService::class.java).apply {
      action = FosaForegroundService.ACTION_START
    }
    startForegroundService(intent)
  }

  private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
  private fun rounded(fill:Int,stroke:Int,r:Int)=GradientDrawable().apply {
    shape=GradientDrawable.RECTANGLE
    cornerRadius=dp(r).toFloat()
    setColor(fill)
    if(stroke!=Color.TRANSPARENT) setStroke(dp(1),stroke)
  }
  private fun label(value:String,size:Float,color:Int,bold:Boolean)=TextView(this).apply {
    text=value
    textSize=size
    setTextColor(color)
    if(bold) setTypeface(typeface,Typeface.BOLD)
    setPadding(0,dp(3),0,dp(3))
  }
  private fun section(value:String)=label(value,11f,muted,true).apply { letterSpacing=.12f }
  private fun cardBox()=LinearLayout(this).apply {
    orientation=LinearLayout.VERTICAL
    setPadding(dp(16),dp(15),dp(16),dp(16))
    background=rounded(card,line,20)
  }
  private fun blockParams(top:Int)=LinearLayout.LayoutParams(-1,-2).apply { topMargin=top }
  private fun actionButton(title:String,primary:Boolean)=Button(this).apply {
    text=title
    textSize=13f
    isAllCaps=false
    setTypeface(typeface,Typeface.BOLD)
    setTextColor(if(primary) Color.rgb(229,255,245) else textColor)
    setPadding(dp(14),dp(11),dp(14),dp(11))
    background=rounded(if(primary) greenSoft else Color.rgb(11,15,19), if(primary) green else line,14)
  }

  private fun makeQr(value:String,size:Int):Bitmap {
    val matrix=MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size,size,Bitmap.Config.RGB_565).also { bmp ->
      for(y in 0 until size) for(x in 0 until size) bmp.setPixel(x,y,if(matrix[x,y]) Color.BLACK else Color.WHITE)
    }
  }
  private fun refresh(){ runOnUiThread { peers.text=if(names.isEmpty())"Aucun appareil connecté" else names.values.joinToString("\n"){"● $it"} } }
  override fun onPeerFound(id:String,name:String){ runOnUiThread{status.text="FOSA trouvé : $name · connexion…"} }
  override fun onPeerConnected(id:String,name:String){ names[id]=name; runOnUiThread{status.text="CONNECTÉ · ${names.size} appareil(s)"}; refresh() }
  override fun onPeerDisconnected(id:String){ names.remove(id); runOnUiThread{status.text=if(names.isEmpty())"LOCAL NATIF · prêt" else "CONNECTÉ · ${names.size} appareil(s)"}; refresh() }
  override fun onPayload(id:String,bytes:ByteArray)=Unit
  override fun onError(message:String){ runOnUiThread{status.text=message} }

  override fun onDestroy(){
    if(isFinishing){
      transport.stop()
      stopService(Intent(this,FosaForegroundService::class.java))
    }
    super.onDestroy()
  }
}
