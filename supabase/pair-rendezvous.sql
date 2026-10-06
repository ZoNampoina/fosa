-- FOSA code-only Web pairing rendezvous.
-- Clients never access these tables directly; the Edge Function uses the service role.
create table if not exists public.fosa_pair_hosts (
  code_hash text primary key,
  host_secret_hash text not null,
  session_id text not null,
  refreshed_at timestamptz not null default now(),
  expires_at timestamptz not null
);

create table if not exists public.fosa_pair_requests (
  id uuid primary key,
  code_hash text not null,
  offer text not null,
  guest_name text not null default '',
  guest_role text not null default '',
  remote_hash text not null,
  answer text,
  claimed_at timestamptz,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);

create index if not exists fosa_pair_requests_code_pending_idx
  on public.fosa_pair_requests (code_hash, created_at)
  where answer is null;

create index if not exists fosa_pair_requests_remote_created_idx
  on public.fosa_pair_requests (remote_hash, created_at);

alter table public.fosa_pair_hosts enable row level security;
alter table public.fosa_pair_requests enable row level security;

revoke all on table public.fosa_pair_hosts from anon, authenticated;
revoke all on table public.fosa_pair_requests from anon, authenticated;
grant all on table public.fosa_pair_hosts to service_role;
grant all on table public.fosa_pair_requests to service_role;
