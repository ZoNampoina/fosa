package fosa.transport

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class NearbyTransport(
  context: Context,
  private val serviceId: String = "fosa.live"
) {
  private val client = Nearby.getConnectionsClient(context)
  private val strategy = Strategy.P2P_CLUSTER

  fun startDiscovery(endpointName: String) {
    client.startDiscovery(
      serviceId,
      object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
          // Bridge vers FOSA : proposer le pair à l'UI.
        }
        override fun onEndpointLost(endpointId: String) {}
      },
      DiscoveryOptions.Builder().setStrategy(strategy).build()
    )
  }

  fun startAdvertising(endpointName: String) {
    client.startAdvertising(
      endpointName,
      serviceId,
      object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
          client.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {}
        override fun onDisconnected(endpointId: String) {}
      },
      AdvertisingOptions.Builder().setStrategy(strategy).build()
    )
  }

  fun sendControl(endpointId: String, bytes: ByteArray) {
    client.sendPayload(endpointId, Payload.fromBytes(bytes))
  }

  private val payloadCallback = object : PayloadCallback() {
    override fun onPayloadReceived(endpointId: String, payload: Payload) {
      // Audio/control frames vers le moteur FOSA natif.
    }
    override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
  }
}
