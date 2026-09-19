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

-- ── Legacy user_id columns / primary keys ──────────────────────────
-- Some earlier table versions carry a NOT NULL user_id the app never
-- sends — sometimes as part of the PRIMARY KEY ("column "user_id" is in
-- a primary key" / "null value in column "user_id" …"). Handle both:
-- rebuild any PK that includes user_id onto the app's key column, then
-- default user_id to auth.uid() and drop its NOT NULL. No-ops when the
-- tables already match the app's schema.
do $$
declare
  t text;
  keycol text;
  pkname text;
  pk_has_user bool;
begin
  for t, keycol in
    select * from unnest(
      array['sync_watch_history','sync_watched_status','sync_prefs'],
      array['item_id','item_key','pref_key']
    )
  loop
    select c.conname, bool_or(a.attname = 'user_id')
      into pkname, pk_has_user
    from pg_constraint c
    join pg_attribute a
      on a.attrelid = c.conrelid and a.attnum = any(c.conkey)
    where c.conrelid = format('public.%I', t)::regclass
      and c.contype = 'p'
    group by c.conname;

    if pkname is not null and pk_has_user then
      execute format('alter table public.%I drop constraint %I', t, pkname);
      execute format('alter table public.%I add primary key (%I)', t, keycol);
    end if;

    if exists (
      select 1 from information_schema.columns
      where table_schema = 'public' and table_name = t and column_name = 'user_id'
    ) then
      execute format(
        'alter table public.%I alter column user_id set default auth.uid()', t);
      execute format(
        'alter table public.%I alter column user_id drop not null', t);
    end if;
  end loop;
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
