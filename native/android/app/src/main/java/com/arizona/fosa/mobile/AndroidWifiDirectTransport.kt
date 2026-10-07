package com.arizona.fosa.mobile

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.net.wifi.p2p.nsd.*
import android.os.*
import org.json.JSONObject

/** Real Android P2P service discovery and group lifecycle; never called by Web. */
class AndroidWifiDirectTransport(private val ctx:Context):LocalTransport {
    override val kind=TransportKind.WIFI_DIRECT
    private val main=Handler(Looper.getMainLooper())
    private val manager=ctx.getSystemService(WifiP2pManager::class.java)
    private val channel=manager?.initialize(ctx,Looper.getMainLooper(),null)
    private var registered=false
    private var local:WifiP2pDnsSdServiceInfo?=null
    private var request:WifiP2pDnsSdServiceRequest?=null
    private var found:((LocalSession)->Unit)?=null
    private var connected:((String)->Unit)?=null
    private var failed:((String)->Unit)?=null
    private var timeout:Runnable?=null
    var owner=false;private set
    var groupInterface="";private set
    var ownerAddress="";private set
    private var ownsGroup=false
    private val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){when(i.action){
        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION->if(i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,0)!=WifiP2pManager.WIFI_P2P_STATE_ENABLED)failed?.invoke("Wi-Fi désactivé ou Wi-Fi Direct indisponible")
        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION->if(permitted())manager?.requestPeers(channel){ /* only advertised FOSA peers are eligible */ }
        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION->refresh()
    }}}
    private fun permitted():Boolean=ctx.checkSelfPermission(if(Build.VERSION.SDK_INT>=33)Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    private fun ready(fail:(String)->Unit):Boolean {
        if(manager==null||channel==null||!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)){fail("Wi-Fi Direct indisponible sur cet appareil");return false}
        if(!permitted()){fail("Permission Appareils à proximité refusée");return false}
        if(!ctx.getSystemService(WifiManager::class.java).isWifiEnabled){fail("Wi-Fi désactivé");return false}
        if(!registered){val f=IntentFilter().apply{addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)}
            if(Build.VERSION.SDK_INT>=33)ctx.registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED)else ctx.registerReceiver(receiver,f);registered=true}
        return true
    }
    private fun action(fail:(String)->Unit={},ok:()->Unit={})=object:WifiP2pManager.ActionListener {
        override fun onSuccess(){ok()}
        override fun onFailure(reason:Int){fail(when(reason){WifiP2pManager.P2P_UNSUPPORTED->"Wi-Fi Direct indisponible";WifiP2pManager.BUSY->"Wi-Fi Direct occupé ; réessaie ou utilise le hotspot";else->"Échec de formation du groupe Wi-Fi Direct ($reason)"})}
    }
    override fun announce(metadata:JSONObject){main.post{
        if(!ready{})return@post
        local?.let{manager?.removeLocalService(channel,it,action{})}
        val txt=linkedMapOf<String,String>();for(k in listOf("session","sessionName","code","hostName","hostRole","port","version","members","device"))if(metadata.has(k))txt[k]=metadata.opt(k).toString().take(64)
        txt["protocol"]="FOSA-LAN/2"
        local=WifiP2pDnsSdServiceInfo.newInstance("FOSA","_fosa._tcp",txt)
        manager?.addLocalService(channel,local,action{})
    }}
    override fun discover(found:(LocalSession)->Unit,failed:(String)->Unit){main.post{
        this.found=found;this.failed=failed;if(!ready(failed))return@post
        manager?.setDnsSdResponseListeners(channel,{_,_,_->},{_,txt,device->
            if(txt["protocol"]=="FOSA-LAN/2")found(LocalSession.from(JSONObject(txt as Map<*,*>),kind=kind,peer=device.deviceAddress))
        })
        if(request==null){request=WifiP2pDnsSdServiceRequest.newInstance();manager?.addServiceRequest(channel,request,action(failed){manager?.discoverServices(channel,action(failed))})}
        else manager?.discoverServices(channel,action(failed))
        manager?.discoverPeers(channel,action(failed))
    }}
    override fun connect(session:LocalSession,connected:(String)->Unit,failed:(String)->Unit){main.post{
        if(!ready(failed))return@post;this.connected=connected;this.failed=failed
        armTimeout(failed)
        val config=WifiP2pConfig().apply{deviceAddress=session.peer;wps.setup=WpsInfo.PBC;groupOwnerIntent=if(session.device)15 else 0}
        manager?.connect(channel,config,action(failed){refresh()})
    }}
    fun createGroup(connected:(String)->Unit,failed:(String)->Unit){main.post{
        if(!ready(failed))return@post;this.connected=connected;this.failed=failed;armTimeout(failed)
        manager?.requestConnectionInfo(channel){info->if(info.groupFormed&&info.isGroupOwner){refresh()}else manager?.createGroup(channel,action(failed){ownsGroup=true;refresh()})}
    }}
    private fun armTimeout(fail:(String)->Unit){timeout?.let{main.removeCallbacks(it)};timeout=Runnable{connected=null;fail("Échec de formation du groupe Wi-Fi Direct. Réessaie ou crée un hotspot local.")}.also{main.postDelayed(it,18000)}}
    private fun refresh(){if(!permitted())return
        manager?.requestGroupInfo(channel){group->groupInterface=group?.`interface` ?: ""}
        manager?.requestConnectionInfo(channel){info->if(info.groupFormed&&info.groupOwnerAddress!=null){owner=info.isGroupOwner;ownerAddress=info.groupOwnerAddress.hostAddress ?: "";timeout?.let{main.removeCallbacks(it)};val cb=connected;connected=null;cb?.invoke("http://$ownerAddress:48765")}}
    }
    fun releaseGroup(done:()->Unit){main.post{if(manager==null||channel==null||!permitted()){done();return@post};manager.requestConnectionInfo(channel){info->if(info.groupFormed)manager.removeGroup(channel,action({done()}){ownsGroup=false;done()})else done()}}}
    override fun close(){main.post{timeout?.let{main.removeCallbacks(it)};connected=null;found=null;failed=null
        if(permitted()){local?.let{manager?.removeLocalService(channel,it,action{})};request?.let{manager?.removeServiceRequest(channel,it,action{})};manager?.stopPeerDiscovery(channel,action{});if(ownsGroup)manager?.removeGroup(channel,action{})}
        if(registered){try{ctx.unregisterReceiver(receiver)}catch(_:Exception){};registered=false};channel?.close()
    }}
}
