package com.arizona.fosa

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import fosa.transport.NearbyTransport

class MainActivity : Activity(), NearbyTransport.Listener {
  private lateinit var transport: NearbyTransport
  private lateinit var status: TextView
  private lateinit var peers: TextView
  private val names = linkedMapOf<String,String>()

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    transport = NearbyTransport(this, listener=this)
    val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(36,50,36,36); setBackgroundColor(Color.rgb(7,9,11)) }
    fun text(s:String,size:Float)=TextView(this).apply { text=s; textSize=size; setTextColor(Color.WHITE); setPadding(0,12,0,12) }
    root.addView(text("FOSA  0.6.0",28f))
    status=text("LOCAL NATIF · prêt",16f); root.addView(status)
    val name=EditText(this).apply { hint="Votre nom"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }; root.addView(name)
    val host=Button(this).apply { text="CRÉER UN GROUPE" }
    val join=Button(this).apply { text="REJOINDRE" }
    root.addView(host); root.addView(join)
    peers=text("Aucun appareil connecté",15f); root.addView(peers)
    root.addView(text("Les appareils FOSA proches sont détectés automatiquement. Aucun QR.",13f))
    setContentView(root)
    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.NEARBY_WIFI_DEVICES),10)
    host.setOnClickListener { transport.createGroup(name.text.toString().ifBlank{"FOSA"}); status.text="Groupe FOSA visible · en attente…" }
    join.setOnClickListener { transport.discoverGroups(name.text.toString().ifBlank{"Musicien"}); status.text="Recherche des groupes FOSA proches…" }
  }
  private fun refresh(){ runOnUiThread { peers.text=if(names.isEmpty())"Aucun appareil connecté" else names.values.joinToString("\n"){"● $it"} } }
  override fun onPeerFound(id:String,name:String){ runOnUiThread{status.text="FOSA trouvé : $name · connexion…"} }
  override fun onPeerConnected(id:String,name:String){ names[id]=name; runOnUiThread{status.text="CONNECTÉ"}; refresh() }
  override fun onPeerDisconnected(id:String){ names.remove(id); refresh() }
  override fun onPayload(id:String,bytes:ByteArray)=Unit
  override fun onError(message:String){ runOnUiThread{status.text=message} }
  override fun onDestroy(){ transport.stop(); super.onDestroy() }
}
