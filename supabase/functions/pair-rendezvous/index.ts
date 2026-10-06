const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Cache-Control": "no-store",
  "Content-Type": "application/json; charset=utf-8",
};

const reply = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: cors });

async function sha(value: string) {
  const bytes = new TextEncoder().encode(value);
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(hash), b => b.toString(16).padStart(2, "0")).join("");
}

function keys(name: string): string[] {
  try {
    return Object.values(JSON.parse(Deno.env.get(name) || "{}")).filter((v): v is string => typeof v === "string");
  } catch {
    return [];
  }
}

function serviceKey() {
  const modern = keys("SUPABASE_SECRET_KEYS")[0];
  if (modern) return modern;
  const legacy = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  if (!legacy) throw new Error("Server database key unavailable");
  return legacy;
}

async function rest(path: string, init: RequestInit = {}) {
  const base = Deno.env.get("SUPABASE_URL");
  if (!base) throw new Error("SUPABASE_URL unavailable");
  const key = serviceKey();
  const headers = new Headers(init.headers || {});
  headers.set("apikey", key);
  headers.set("Content-Type", "application/json");
  if (key.startsWith("eyJ")) headers.set("Authorization", "Bearer " + key);
  const r = await fetch(base + "/rest/v1/" + path, { ...init, headers });
  const text = await r.text();
  const body = text ? JSON.parse(text) : null;
  if (!r.ok) throw new Error(body?.message || body?.error || ("Database error " + r.status));
  return { body, headers: r.headers };
}

async function cleanup() {
  const now = encodeURIComponent(new Date().toISOString());
  await Promise.allSettled([
    rest("fosa_pair_requests?expires_at=lt." + now, { method: "DELETE" }),
    rest("fosa_pair_hosts?expires_at=lt." + now, { method: "DELETE" }),
  ]);
}

async function hostRow(codeHash: string) {
  const now = encodeURIComponent(new Date().toISOString());
  const q = "fosa_pair_hosts?select=code_hash,host_secret_hash,session_id,expires_at&code_hash=eq." +
    encodeURIComponent(codeHash) + "&expires_at=gt." + now + "&limit=1";
  const { body } = await rest(q);
  return Array.isArray(body) ? body[0] : null;
}

async function authorizeHost(code: string, secret: string) {
  if (!/^\d{6}$/.test(code) || secret.length < 32) return null;
  const codeHash = await sha("fosa-code:" + code);
  const secretHash = await sha("fosa-secret:" + secret);
  const row = await hostRow(codeHash);
  return row && row.host_secret_hash === secretHash ? { codeHash, row } : null;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return reply({ error: "POST required" }, 405);

  const supplied = req.headers.get("apikey") || "";
  const allowed = keys("SUPABASE_PUBLISHABLE_KEYS");
  if (!supplied || !allowed.includes(supplied)) return reply({ error: "Unauthorized" }, 401);

  let q: any;
  try {
    const text = await req.text();
    if (text.length > 48000) return reply({ error: "Payload too large" }, 413);
    q = JSON.parse(text || "{}");
  } catch {
    return reply({ error: "Invalid JSON" }, 400);
  }

  try {
    const action = String(q.action || "");
    const code = String(q.code || "").trim();
    await cleanup();

    if (action === "host-register") {
      const secret = String(q.secret || "");
      const session = String(q.session || "").slice(0, 80);
      if (!/^\d{6}$/.test(code) || secret.length < 32 || !session) return reply({ error: "Invalid host registration" }, 400);
      const codeHash = await sha("fosa-code:" + code);
      const secretHash = await sha("fosa-secret:" + secret);
      const existing = await hostRow(codeHash);
      if (existing && existing.host_secret_hash !== secretHash) return reply({ error: "Session code already active" }, 409);
      const expires = new Date(Date.now() + 90_000).toISOString();
      const body = [{ code_hash: codeHash, host_secret_hash: secretHash, session_id: session, refreshed_at: new Date().toISOString(), expires_at: expires }];
      await rest("fosa_pair_hosts?on_conflict=code_hash", {
        method: "POST",
        headers: { Prefer: "resolution=merge-duplicates,return=minimal" },
        body: JSON.stringify(body),
      });
      return reply({ ok: true, expiresAt: expires });
    }

    if (action === "guest-offer") {
      const offer = String(q.offer || "");
      const name = String(q.name || "").trim().slice(0, 40);
      const role = String(q.role || "").trim().slice(0, 40);
      if (!/^\d{6}$/.test(code) || !name || offer.length < 50 || offer.length > 20000) return reply({ error: "Invalid pairing request" }, 400);
      const codeHash = await sha("fosa-code:" + code);
      if (!await hostRow(codeHash)) return reply({ error: "Session unavailable" }, 404);
      const remote = req.headers.get("x-forwarded-for") || req.headers.get("cf-connecting-ip") || req.headers.get("x-real-ip") || "unknown";
      const remoteHash = await sha("fosa-remote:" + remote.split(",")[0].trim());
      const since = encodeURIComponent(new Date(Date.now() - 60_000).toISOString());
      const rate = "fosa_pair_requests?select=id&remote_hash=eq." + encodeURIComponent(remoteHash) + "&created_at=gt." + since + "&limit=9";
      const { body: recent } = await rest(rate);
      if (Array.isArray(recent) && recent.length >= 8) return reply({ error: "Too many attempts. Wait one minute." }, 429);
      const id = crypto.randomUUID();
      const expires = new Date(Date.now() + 90_000).toISOString();
      await rest("fosa_pair_requests", {
        method: "POST",
        headers: { Prefer: "return=minimal" },
        body: JSON.stringify([{ id, code_hash: codeHash, offer, guest_name: name, guest_role: role, remote_hash: remoteHash, expires_at: expires }]),
      });
      return reply({ ok: true, id, expiresAt: expires });
    }

    if (action === "host-poll") {
      const auth = await authorizeHost(code, String(q.secret || ""));
      if (!auth) return reply({ error: "Host authorization failed" }, 403);
      const now = encodeURIComponent(new Date().toISOString());
      const path = "fosa_pair_requests?select=id,offer,guest_name,guest_role,created_at&code_hash=eq." +
        encodeURIComponent(auth.codeHash) + "&answer=is.null&expires_at=gt." + now + "&order=created_at.asc&limit=8";
      const { body } = await rest(path);
      return reply({ ok: true, requests: body || [] });
    }

    if (action === "host-answer") {
      const auth = await authorizeHost(code, String(q.secret || ""));
      const id = String(q.id || "");
      const answer = String(q.answer || "");
      if (!auth) return reply({ error: "Host authorization failed" }, 403);
      if (!/^[0-9a-f-]{36}$/i.test(id) || answer.length < 50 || answer.length > 20000) return reply({ error: "Invalid answer" }, 400);
      const path = "fosa_pair_requests?id=eq." + encodeURIComponent(id) + "&code_hash=eq." + encodeURIComponent(auth.codeHash);
      await rest(path, { method: "PATCH", headers: { Prefer: "return=minimal" }, body: JSON.stringify({ answer, claimed_at: new Date().toISOString() }) });
      return reply({ ok: true });
    }

    if (action === "guest-poll") {
      const id = String(q.id || "");
      if (!/^\d{6}$/.test(code) || !/^[0-9a-f-]{36}$/i.test(id)) return reply({ error: "Invalid request" }, 400);
      const codeHash = await sha("fosa-code:" + code);
      const now = encodeURIComponent(new Date().toISOString());
      const path = "fosa_pair_requests?select=answer,expires_at&id=eq." + encodeURIComponent(id) + "&code_hash=eq." +
        encodeURIComponent(codeHash) + "&expires_at=gt." + now + "&limit=1";
      const { body } = await rest(path);
      const row = Array.isArray(body) ? body[0] : null;
      if (!row) return reply({ error: "Pairing request expired" }, 404);
      return reply({ ok: true, answer: row.answer || null });
    }

    if (action === "host-unregister") {
      const auth = await authorizeHost(code, String(q.secret || ""));
      if (!auth) return reply({ ok: true });
      await rest("fosa_pair_hosts?code_hash=eq." + encodeURIComponent(auth.codeHash), { method: "DELETE" });
      return reply({ ok: true });
    }

    return reply({ error: "Unknown action" }, 400);
  } catch (e) {
    return reply({ error: e instanceof Error ? e.message : "Pairing service unavailable" }, 500);
  }
});