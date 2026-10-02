/* Connection setup only. Audio and live controls use the direct WebRTC link. */
(() => {
  'use strict';
  const encode = new TextEncoder(), decode = new TextDecoder();
  const b64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const unb64 = text => Uint8Array.from(atob(text.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
  const unwrap = result => {
    if (result.status >= 400) throw Object.assign(new Error(result.data?.error || 'Erreur du bridge'), {code: result.data?.code});
    return result.data;
  };

  class FosaRpcChannel {
    constructor(channel) {
      this.channel = channel;
      this.pending = new Map();
      channel.addEventListener('message', event => {
        try {
          const result = JSON.parse(event.data), waiting = this.pending.get(result.id);
          if (waiting) { this.pending.delete(result.id); clearTimeout(waiting.timer); waiting.resolve(result); }
        } catch (_) { /* An unrelated/malformed message is not an RPC response. */ }
      });
      channel.addEventListener('close', () => {
        for (const waiting of this.pending.values()) { clearTimeout(waiting.timer); waiting.reject(new Error('Liaison directe interrompue')); }
        this.pending.clear();
      });
    }
    get ready() { return this.channel.readyState === 'open'; }
    async request(path, body, timeout = 5000) {
      if (!this.ready || this.channel.bufferedAmount > 128*1024 || this.pending.size >= 32) throw new Error('Liaison directe occupée ou interrompue');
      const id = crypto.randomUUID();
      const result = new Promise((resolve, reject) => {
        const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('Le PC ne répond plus sur le réseau local')); }, timeout);
        this.pending.set(id, {resolve, reject, timer});
      });
      this.channel.send(JSON.stringify({id, path, body}));
      return unwrap(await result);
    }
  }

  class FosaRelay {
    static async create(descriptor) {
      if (!crypto.subtle || !/^[a-f0-9]{32}$/.test(descriptor.relay || '') ||
          !/^[a-f0-9]{32}$/.test(descriptor.epoch || '') || !/^[A-Za-z0-9_-]{87}$/.test(descriptor.key || '')) {
        throw new Error('QR sécurisé incomplet. Scanne le QR actuel du PC.');
      }
      const publicBytes = unb64(descriptor.key);
      const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', publicBytes))].map(v => v.toString(16).padStart(2, '0')).join('');
      if (!hash.startsWith(descriptor.relay)) throw new Error('Identité du bridge invalide dans le QR');
      const configResponse = await fetch('./network-relay-config.json', {cache: 'no-store'});
      if (!configResponse.ok) throw new Error('Configuration de connexion indisponible');
      const config = await configResponse.json();
      const keys = await crypto.subtle.generateKey({name: 'ECDH', namedCurve: 'P-256'}, false, ['deriveBits']);
      const serverKey = await crypto.subtle.importKey('raw', publicBytes, {name: 'ECDH', namedCurve: 'P-256'}, false, []);
      const shared = await crypto.subtle.deriveBits({name: 'ECDH', public: serverKey}, keys.privateKey, 256);
      const hkdf = await crypto.subtle.importKey('raw', shared, 'HKDF', false, ['deriveBits']);
      const material = await crypto.subtle.deriveBits({name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0),
        info: encode.encode('fosa-relay-v1:' + descriptor.relay)}, hkdf, 512);
      const requestKey = await crypto.subtle.importKey('raw', material.slice(0, 32), 'AES-GCM', false, ['encrypt']);
      const responseKey = await crypto.subtle.importKey('raw', material.slice(32), 'AES-GCM', false, ['decrypt']);
      const peer = b64(await crypto.subtle.exportKey('raw', keys.publicKey));
      return new FosaRelay(descriptor, config, peer, requestKey, responseKey);
    }
    constructor(descriptor, config, peer, requestKey, responseKey) {
      Object.assign(this, {descriptor, config, peer, requestKey, responseKey});
      this.aad = encode.encode(descriptor.relay + ':' + descriptor.epoch + ':' + peer);
      this.topic = 'realtime:fosa-bridge:' + descriptor.relay;
      this.pending = new Map(); this.socket = null; this.connecting = null;
      this.joined = false; this.offset = 0; this.ref = 1;
    }
    async connect() {
      if (this.joined && this.socket?.readyState === WebSocket.OPEN) return;
      if (this.connecting) return this.connecting;
      this.connecting = new Promise((resolve, reject) => {
        const url = new URL(this.config.url); url.protocol = 'wss:'; url.pathname = '/realtime/v1/websocket';
        url.search = new URLSearchParams({apikey: this.config.publishableKey, vsn: '1.0.0'}).toString();
        const socket = new WebSocket(url.href); this.socket = socket;
        const timer = setTimeout(() => { socket.close(); reject(new Error('Connexion Internet au relais indisponible')); }, 12000);
        const fail = () => { clearTimeout(timer); this.joined = false; clearInterval(this.heartbeat); reject(new Error('Connexion au relais interrompue. Vérifie Internet.')); };
        socket.onopen = () => socket.send(JSON.stringify({topic: this.topic, event: 'phx_join', ref: '1', join_ref: '1',
          payload: {config: {broadcast: {ack: false, self: false}, presence: {enabled: false}, private: false}}}));
        socket.onmessage = async event => {
          try {
            const message = JSON.parse(event.data);
            if (message.event === 'phx_reply' && message.ref === '1') {
              if (message.payload?.status !== 'ok') { fail(); socket.close(); return; }
              clearTimeout(timer); this.joined = true;
              this.heartbeat = setInterval(() => this.push('heartbeat', {}, 'phoenix'), 20000);
              resolve();
            } else if (['phx_close', 'phx_error'].includes(message.event)) { fail(); socket.close(); }
            else if (message.event === 'broadcast' && message.payload?.event === 'rpc') {
              const envelope = message.payload.payload;
              if (envelope?.kind !== 'response' || envelope.peer !== this.peer || envelope.epoch !== this.descriptor.epoch) return;
              const clear = await crypto.subtle.decrypt({name: 'AES-GCM', iv: unb64(envelope.iv), additionalData: this.aad}, this.responseKey, unb64(envelope.data));
              const result = JSON.parse(decode.decode(clear)), waiting = this.pending.get(result.id);
              if (waiting) { this.offset = result.serverTime - Date.now(); waiting.resolve(result); }
            }
          } catch (_) { /* Ignore ciphertext that does not authenticate to this browser. */ }
        };
        socket.onerror = fail;
        socket.onclose = () => { fail(); for (const item of this.pending.values()) item.reject(new Error('Relais déconnecté')); };
      }).finally(() => { this.connecting = null; });
      return this.connecting;
    }
    push(event, payload, topic = this.topic) {
      if (this.socket?.readyState !== WebSocket.OPEN) return;
      this.socket.send(JSON.stringify({topic, event, payload, ref: String(++this.ref), join_ref: '1'}));
    }
    pause() {
      if (this.pending.size) { setTimeout(() => this.pause(), 100); return; }
      this.joined = false; clearInterval(this.heartbeat); this.socket?.close(); this.socket = null;
    }
    async request(path, body, token = '', timeout = 12000, retryClock = true) {
      await this.connect();
      const id = crypto.randomUUID(), iv = crypto.getRandomValues(new Uint8Array(12));
      const data = encode.encode(JSON.stringify({id, path, body, token, sentAt: Date.now() + this.offset}));
      const ciphertext = await crypto.subtle.encrypt({name: 'AES-GCM', iv, additionalData: this.aad}, this.requestKey, data);
      const envelope = {v: 1, kind: 'request', epoch: this.descriptor.epoch, peer: this.peer, iv: b64(iv), data: b64(ciphertext)};
      const send = () => this.push('broadcast', {type: 'broadcast', event: 'rpc', payload: envelope});
      let timer, resend;
      try {
        const result = await new Promise((resolve, reject) => {
          this.pending.set(id, {resolve, reject});
          timer = setTimeout(() => reject(new Error('Le PC ne répond pas. Garde FOSA ouvert et scanne son QR actuel.')), timeout);
          resend = setInterval(send, 3000); send();
        });
        if (result.data?.code === 'CLOCK_SKEW' && retryClock) return this.request(path, body, token, timeout, false);
        return unwrap(result);
      } finally { clearTimeout(timer); clearInterval(resend); this.pending.delete(id); }
    }
  }
  window.FosaRelay = FosaRelay;
  window.FosaRpcChannel = FosaRpcChannel;
})();
