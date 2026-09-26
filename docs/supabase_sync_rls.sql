-- KBStream sync: table schema + per-user row level security. Run ONCE in the
-- Supabase SQL Editor (Database → SQL Editor → New query → paste → Run).
-- Safe to re-run: every step is IF NOT EXISTS / idempotent, and re-running the
-- ownership migration is a no-op once the tables are already per-user.
--
-- What this guarantees:
--   1. Tables exist with the exact columns the app reads/writes
--      (SyncRowDto: item_id / item_key / pref_key / payload / updated_at).
--   2. Every row is OWNED by the account that wrote it. The app never sends
--      user_id — the column defaults to auth.uid(), so PostgREST inserts are
--      stamped automatically — and RLS stops any other account from reading
--      or writing it (auth.uid() = user_id).
--   3. A composite PRIMARY KEY (user_id, <key column>) — required for
--      PostgREST upsert (Prefer: resolution=merge-duplicates) to work now that
--      the same item_id can legitimately exist for more than one account.
--
-- IMPORTANT — deploying this to an install that is already syncing:
--   The owner of pre-existing rows cannot be inferred from the data (the old
--   schema carried no user_id). They are backfilled to the OLDEST account in
--   auth.users, which preserves a single-account install's cloud data. If the
--   tables hold data for several accounts, only the oldest keeps it; the
--   others re-push from their devices ("Force full resync" in Settings).
--
--   This file REPLACES the old shared-everything policies
--   ("kbstream_authenticated_all_*"), which granted every signed-in account
--   full access to every other account's rows.

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

-- ── Ownership: user_id column, backfill, default, NOT NULL ─────────
-- Runs before the primary-key rebuild below, because the PK references
-- user_id and its NOT NULL constraint needs the backfill to have happened.
do $$
declare
  t text;
  owner uuid;
begin
  foreach t in array array['sync_watch_history','sync_watched_status','sync_prefs']
  loop
    execute format('alter table public.%I add column if not exists user_id uuid', t);
  end loop;

  -- Backfill rows written before ownership existed. Pick the oldest account
  -- so an existing single-account install keeps its cloud data; with no
  -- accounts there is nothing to preserve.
  select id into owner from auth.users order by created_at asc limit 1;

  foreach t in array array['sync_watch_history','sync_watched_status','sync_prefs']
  loop
    if owner is null then
      execute format('delete from public.%I where user_id is null', t);
    else
      execute format('update public.%I set user_id = %L where user_id is null', t, owner);
    end if;

    -- Stamped automatically on PostgREST inserts; the app never sends it.
    execute format('alter table public.%I alter column user_id set default auth.uid()', t);
    execute format('alter table public.%I alter column user_id set not null', t);
  end loop;
end $$;

-- ── Primary key: composite (user_id, key column) ────────────────────
-- The old key-only PK cannot survive per-user rows: two accounts watching the
-- same title have the same item_id and would collide on a single-column key.
-- Rebuild the PK only when it is not already exactly (user_id, key).
do $$
declare
  t text;
  keycol text;
  pkname text;
  pkcols text[];
  want text[];
begin
  for t, keycol in
    select * from unnest(
      array['sync_watch_history','sync_watched_status','sync_prefs'],
      array['item_id','item_key','pref_key']
    )
  loop
    want := array(select unnest(array['user_id', keycol]) order by 1);

    select c.conname,
           array_agg(a.attname order by a.attname)
      into pkname, pkcols
    from pg_constraint c
    join pg_attribute a
      on a.attrelid = c.conrelid and a.attnum = any(c.conkey)
    where c.conrelid = format('public.%I', t)::regclass
      and c.contype = 'p'
    group by c.conname;

    if pkname is not null and pkcols is distinct from want then
      execute format('alter table public.%I drop constraint %I', t, pkname);
      pkname := null;
    end if;

    if pkname is null then
      execute format(
        'alter table public.%I add primary key (user_id, %I)', t, keycol);
    end if;
  end loop;
end $$;

-- ── Row level security: owner-scoped ────────────────────────────────
-- Every row is readable/writable only by its owning account. This replaces
-- the previous "any authenticated account" policies, which shared all rows.
alter table public.sync_watch_history enable row level security;
alter table public.sync_watched_status enable row level security;
alter table public.sync_prefs enable row level security;

-- Retire the old shared-everything policies if they are present.
drop policy if exists "kbstream_authenticated_all_watch_history"
  on public.sync_watch_history;
drop policy if exists "kbstream_authenticated_all_watched_status"
  on public.sync_watched_status;
drop policy if exists "kbstream_authenticated_all_prefs"
  on public.sync_prefs;

-- Owner-scoped policies (re-created on every run).
drop policy if exists "kbstream_own_watch_history" on public.sync_watch_history;
create policy "kbstream_own_watch_history"
  on public.sync_watch_history
  for all
  to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

drop policy if exists "kbstream_own_watched_status" on public.sync_watched_status;
create policy "kbstream_own_watched_status"
  on public.sync_watched_status
  for all
  to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

drop policy if exists "kbstream_own_prefs" on public.sync_prefs;
create policy "kbstream_own_prefs"
  on public.sync_prefs
  for all
  to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);
