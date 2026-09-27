package fosa.transport

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

/**
 * FOSA Local native transport.
 * One host advertises a group; any number of nearby clients can discover and join.
 * No QR/camera is required.
 */
class NearbyTransport(
  context: Context,
  private val serviceId: String = "fosa.live",
  private val listener: Listener
) {
  interface Listener {
    fun onPeerFound(id: String, name: String)
    fun onPeerConnected(id: String, name: String)
    fun onPeerDisconnected(id: String)
    fun onPayload(id: String, bytes: ByteArray)
    fun onError(message: String)
  }

  private val client = Nearby.getConnectionsClient(context)
  private val strategy = Strategy.P2P_CLUSTER
  private val peers = linkedSetOf<String>()

  private val payloadCallback = object : PayloadCallback() {
    override fun onPayloadReceived(endpointId: String, payload: Payload) {
      payload.asBytes()?.let { listener.onPayload(endpointId, it) }
    }
    override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
  }

  private val lifecycle = object : ConnectionLifecycleCallback() {
    override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
      client.acceptConnection(endpointId, payloadCallback)
    }
    override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
      if (result.status.isSuccess) {
        peers += endpointId
        listener.onPeerConnected(endpointId, endpointId)
      } else listener.onError("Connexion locale refusée: " + result.status.statusCode)
    }
    override fun onDisconnected(endpointId: String) {
      peers -= endpointId
      listener.onPeerDisconnected(endpointId)
    }
  }

  fun createGroup(name: String) {
    client.stopDiscovery()
    client.startAdvertising(
      name, serviceId, lifecycle,
      AdvertisingOptions.Builder().setStrategy(strategy).build()
    ).addOnFailureListener { listener.onError(it.message ?: "Publicité locale impossible") }
  }

  fun discoverGroups(localName: String) {
    client.stopAdvertising()
    client.startDiscovery(
      serviceId,
      object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
          listener.onPeerFound(endpointId, info.endpointName)
          client.requestConnection(localName, endpointId, lifecycle)
            .addOnFailureListener { listener.onError(it.message ?: "Connexion locale impossible") }
        }
        override fun onEndpointLost(endpointId: String) = Unit
      },
      DiscoveryOptions.Builder().setStrategy(strategy).build()
    ).addOnFailureListener { listener.onError(it.message ?: "Recherche locale impossible") }
  }

  fun send(endpointId: String, bytes: ByteArray) =
    client.sendPayload(endpointId, Payload.fromBytes(bytes))

  fun broadcast(bytes: ByteArray) {
    if (peers.isNotEmpty()) client.sendPayload(peers.toList(), Payload.fromBytes(bytes))
  }

  fun connectedPeers(): Set<String> = peers.toSet()

  fun stop() {
    client.stopAdvertising()
    client.stopDiscovery()
    client.stopAllEndpoints()
    peers.clear()
  }
}
