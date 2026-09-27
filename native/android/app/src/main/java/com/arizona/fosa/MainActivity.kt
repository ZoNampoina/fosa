package com.arizona.fosa

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import fosa.transport.NearbyTransport

class MainActivity : Activity(), NearbyTransport.Listener {
  private lateinit var transport: NearbyTransport
  private lateinit var status: TextView
  private lateinit var peers: TextView
  private val names = linkedMapOf<String,String>()
  private val downloadUrl = "https://github.com/ZoNampoina/fosa/releases/latest/download/FOSA-Android.apk"
  private val webUrl = "https://zonampoina.github.io/fosa/"

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    transport = NearbyTransport(this, listener=this)
    val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(36,50,36,36); setBackgroundColor(Color.rgb(7,9,11)) }
    fun text(s:String,size:Float)=TextView(this).apply { text=s; textSize=size; setTextColor(Color.WHITE); setPadding(0,12,0,12) }
    root.addView(text("FOSA  0.6.1",28f))
    status=text("LOCAL NATIF · prêt",16f); root.addView(status)
    val name=EditText(this).apply { hint="Votre nom"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }; root.addView(name)
    val host=Button(this).apply { text="CRÉER UN GROUPE" }
    val join=Button(this).apply { text="REJOINDRE" }
    root.addView(host); root.addView(join)
    peers=text("Aucun appareil connecté",15f); root.addView(peers)
    root.addView(text("Les appareils FOSA proches sont détectés automatiquement. Aucun QR nécessaire pour la connexion.",13f))

    root.addView(text("PARTAGER / INSTALLER FOSA",16f))
    val qr=ImageView(this).apply { setImageBitmap(makeQr(downloadUrl, 520)); adjustViewBounds=true; contentDescription="QR code de téléchargement FOSA" }
    root.addView(qr, LinearLayout.LayoutParams(-1, 520))
    root.addView(text("Scannez ce QR sur Android pour télécharger l'application.",13f))
    val browser=Button(this).apply { text="OUVRIR FOSA DANS LE NAVIGATEUR" }
    root.addView(browser)

    setContentView(ScrollView(this).apply { addView(root) })
    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.NEARBY_WIFI_DEVICES),10)
    host.setOnClickListener { transport.createGroup(name.text.toString().ifBlank{"FOSA"}); status.text="Groupe FOSA visible · en attente…" }
    join.setOnClickListener { transport.discoverGroups(name.text.toString().ifBlank{"Musicien"}); status.text="Recherche des groupes FOSA proches…" }
    browser.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))) }
  }

  private fun makeQr(value:String,size:Int):Bitmap {
    val matrix=MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size,size,Bitmap.Config.RGB_565).also { bmp ->
      for(y in 0 until size) for(x in 0 until size) bmp.setPixel(x,y,if(matrix[x,y]) Color.BLACK else Color.WHITE)
    }
  }
  private fun refresh(){ runOnUiThread { peers.text=if(names.isEmpty())"Aucun appareil connecté" else names.values.joinToString("\n"){"● $it"} } }
  override fun onPeerFound(id:String,name:String){ runOnUiThread{status.text="FOSA trouvé : $name · connexion…"} }
  override fun onPeerConnected(id:String,name:String){ names[id]=name; runOnUiThread{status.text="CONNECTÉ"}; refresh() }
  override fun onPeerDisconnected(id:String){ names.remove(id); refresh() }
  override fun onPayload(id:String,bytes:ByteArray)=Unit
  override fun onError(message:String){ runOnUiThread{status.text=message} }
  override fun onDestroy(){ transport.stop(); super.onDestroy() }
}
