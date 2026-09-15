-- KBStream sync: table schema + row level security. Run ONCE in the
-- Supabase SQL Editor (Database → SQL Editor → New query → paste → Run).
--
-- What this guarantees:
--   1. Tables exist with the exact columns the app reads/writes
--      (SyncRowDto: item_id / item_key / pref_key / payload / updated_at).
--   2. A PRIMARY KEY on the key column of each table — required for
--      PostgREST upsert (Prefer: resolution=merge-duplicates) to work.
--      Without a PK, upserts fail and sync can never write.
--   3. RLS policies letting any signed-in account read/write its data.
--
-- Safe to re-run: everything is IF NOT EXISTS / DROP POLICY IF EXISTS.
-- Safe on existing tables: columns are added only when missing.

-- ── sync_watch_history ──────────────────────────────────────────────
create table if not exists public.sync_watch_history (
  item_id text primary key,
  payload jsonb not null,
  updated_at timestamptz not null default now()
);
alter table public.sync_watch_history add column if not exists item_id text;
alter table public.sync_watch_history add column if not exists payload jsonb;
alter table public.sync_watch_history add column if not exists updated_at timestamptz;

-- ── sync_watched_status ─────────────────────────────────────────────
create table if not exists public.sync_watched_status (
  item_key text primary key,
  payload jsonb not null,
  updated_at timestamptz not null default now()
);
alter table public.sync_watched_status add column if not exists item_key text;
alter table public.sync_watched_status add column if not exists payload jsonb;
alter table public.sync_watched_status add column if not exists updated_at timestamptz;

-- ── sync_prefs ──────────────────────────────────────────────────────
create table if not exists public.sync_prefs (
  pref_key text primary key,
  payload jsonb not null,
  updated_at timestamptz not null default now()
);
alter table public.sync_prefs add column if not exists pref_key text;
alter table public.sync_prefs add column if not exists payload jsonb;
alter table public.sync_prefs add column if not exists updated_at timestamptz;

-- Primary keys (no-op if the tables above were just created with them;
-- adds them if the tables pre-existed without one — required for upsert).
do $$
begin
  begin
    execute 'alter table public.sync_watch_history add primary key (item_id)';
  exception when duplicate_table or invalid_table_definition then null;
  end;
  begin
    execute 'alter table public.sync_watched_status add primary key (item_key)';
  exception when duplicate_table or invalid_table_definition then null;
  end;
  begin
    execute 'alter table public.sync_prefs add primary key (pref_key)';
  exception when duplicate_table or invalid_table_definition then null;
  end;
end $$;

-- ── Row level security ──────────────────────────────────────────────
-- Rows carry no per-user column; isolation is the app's account boundary
-- plus per-profile key prefixes ("p:<profileId>:<key>"). Policies grant
-- full access to ANY signed-in account — right model for a personal
-- project where you create the accounts. If you ever open sign-ups to
-- strangers, add a user_id uuid column and scope on auth.uid() instead.

alter table public.sync_watch_history enable row level security;
alter table public.sync_watched_status enable row level security;
alter table public.sync_prefs enable row level security;

drop policy if exists "kbstream_authenticated_all_watch_history"
  on public.sync_watch_history;
create policy "kbstream_authenticated_all_watch_history"
  on public.sync_watch_history
  for all
  to authenticated
  using (true)
  with check (true);

drop policy if exists "kbstream_authenticated_all_watched_status"
  on public.sync_watched_status;
create policy "kbstream_authenticated_all_watched_status"
  on public.sync_watched_status
  for all
  to authenticated
  using (true)
  with check (true);

drop policy if exists "kbstream_authenticated_all_prefs"
  on public.sync_prefs;
create policy "kbstream_authenticated_all_prefs"
  on public.sync_prefs
  for all
  to authenticated
  using (true)
  with check (true);
