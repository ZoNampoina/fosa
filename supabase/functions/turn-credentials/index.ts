Deno.serve(async (req) => {
  const cors = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  };
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });

  const suppliedKey = req.headers.get("apikey") || "";
  let validKeys: string[] = [];
  try {
    const raw = Deno.env.get("SUPABASE_PUBLISHABLE_KEYS") || "{}";
    validKeys = Object.values(JSON.parse(raw));
  } catch {}
  if (!suppliedKey || !validKeys.includes(suppliedKey)) {
    return new Response(JSON.stringify({ error: "Unauthorized" }), {
      status: 401,
      headers: { ...cors, "content-type": "application/json" },
    });
  }

  const upstream = Deno.env.get("TURN_CREDENTIALS_URL");
  const token = Deno.env.get("TURN_API_TOKEN");
  if (!upstream || !token) {
    return new Response(JSON.stringify({ error: "TURN credential provider is not configured" }), {
      status: 503,
      headers: { ...cors, "content-type": "application/json" },
    });
  }

  const r = await fetch(upstream, {
    headers: { Authorization: `Bearer ${token}`, Accept: "application/json" },
  });
  const body = await r.text();
  return new Response(body, {
    status: r.status,
    headers: {
      ...cors,
      "content-type": r.headers.get("content-type") || "application/json",
      "cache-control": "no-store",
    },
  });
});
