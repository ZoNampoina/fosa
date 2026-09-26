import Foundation
import MultipeerConnectivity

final class NearbyTransport: NSObject {
    private let serviceType = "fosa-live"
    private let peerID: MCPeerID
    private let session: MCSession
    private var advertiser: MCNearbyServiceAdvertiser?
    private var browser: MCNearbyServiceBrowser?

    init(name: String) {
        peerID = MCPeerID(displayName: name)
        session = MCSession(peer: peerID, securityIdentity: nil, encryptionPreference: .required)
        super.init()
        session.delegate = self
    }

    func start() {
        advertiser = MCNearbyServiceAdvertiser(peer: peerID, discoveryInfo: nil, serviceType: serviceType)
        browser = MCNearbyServiceBrowser(peer: peerID, serviceType: serviceType)
        advertiser?.delegate = self
        browser?.delegate = self
        advertiser?.startAdvertisingPeer()
        browser?.startBrowsingForPeers()
    }

    func send(_ data: Data, to peers: [MCPeerID]) throws {
        try session.send(data, toPeers: peers, with: .unreliable)
    }
}

extension NearbyTransport: MCSessionDelegate {
    func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {}
    func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {}
    func session(_ session: MCSession, didReceive stream: InputStream, withName streamName: String, fromPeer peerID: MCPeerID) {}
    func session(_ session: MCSession, didStartReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, with progress: Progress) {}
    func session(_ session: MCSession, didFinishReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, at localURL: URL?, withError error: Error?) {}
}

extension NearbyTransport: MCNearbyServiceAdvertiserDelegate {
    func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didReceiveInvitationFromPeer peerID: MCPeerID, withContext context: Data?, invitationHandler: @escaping (Bool, MCSession?) -> Void) {
        invitationHandler(true, session)
    }
}

extension NearbyTransport: MCNearbyServiceBrowserDelegate {
    func browser(_ browser: MCNearbyServiceBrowser, foundPeer peerID: MCPeerID, withDiscoveryInfo info: [String : String]?) {
        browser.invitePeer(peerID, to: session, withContext: nil, timeout: 8)
    }
    func browser(_ browser: MCNearbyServiceBrowser, lostPeer peerID: MCPeerID) {}
}
