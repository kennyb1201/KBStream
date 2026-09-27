-- KBStream sync: enable Supabase Realtime for the three sync tables.
-- Run ONCE in the Supabase SQL Editor (Database → SQL Editor → New query →
-- paste → Run). Safe to re-run: the guard below adds only the tables that are
-- missing and reports what it did.
--
-- Why this is a SEPARATE file from supabase_sync_rls.sql:
--   Row level security and Realtime are independent settings. Realtime rides on
--   Postgres logical replication, and only tables that are MEMBERS of the
--   `supabase_realtime` publication have their changes streamed to clients. A
--   table can have perfect owner-scoped RLS and still emit nothing at all.
--
--   When a table is not a member, the app's realtime channel for it is rejected
--   with a `system` error, and supabase-kt leaves the channel UNSUBSCRIBED
--   forever. SupabaseSync.watchRealtimeHealth() reads that as a dead channel,
--   rebuilds it on a timer, and after a bounded number of attempts gives up
--   with:
--       realtime <table>: join rejected N times — giving up; add the table to
--       the supabase_realtime publication to enable live sync
--   Live propagation between devices then never happens; remote changes still
--   arrive, but only via the manual/periodic pull and the outbox flush.
--
-- IMPORTANT — `alter publication ... add table` has NO `if not exists`
-- (Postgres raises "is already member of publication" if the table is already
-- there), which is why the add is wrapped in a guarded DO block instead of
-- written as three plain ALTERs. The same block creates the publication if it
-- is somehow missing, so this file works on its own.
--
-- RLS still applies to Realtime: a client only receives change events for rows
-- it is allowed to SELECT. The kbstream_own_* policies created by
-- supabase_sync_rls.sql are therefore also what stops an account from being
-- pushed another account's rows over the websocket.

-- ── Add the three sync tables to the realtime publication ─────────────
do $$
declare
  t text;
begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    execute 'create publication supabase_realtime';
    raise notice 'created publication supabase_realtime';
  end if;

  for t in
    select unnest(array['sync_watch_history', 'sync_watched_status', 'sync_prefs'])
  loop
    if exists (
      select 1
      from pg_publication_tables
      where pubname = 'supabase_realtime'
        and schemaname = 'public'
        and tablename = t
    ) then
      raise notice 'public.% is already in supabase_realtime', t;
    else
      execute format('alter publication supabase_realtime add table public.%I', t);
      raise notice 'added public.% to supabase_realtime', t;
    end if;
  end loop;
end $$;

-- ── Optional: full replica identity for UPDATE/DELETE payloads ────────
-- Realtime sends the OLD row on UPDATE/DELETE, but by default that old record
-- carries only the primary key columns (user_id, <key column>) — which is all
-- this app needs, and is what lets it identify which row changed. Setting FULL
-- identity additionally sends every old column, at the cost of extra work on
-- every write and more data on the wire. Left disabled deliberately; enable it
-- only if you start needing the previous values in the change event.
--
-- alter table public.sync_watch_history replica identity full;
-- alter table public.sync_watched_status replica identity full;
-- alter table public.sync_prefs replica identity full;

-- ── Verify ───────────────────────────────────────────────────────────
-- Expect all three rows. If a table is missing here, the app will log the
-- "join rejected ... giving up" warning above for that table.
select schemaname, tablename
from pg_publication_tables
where pubname = 'supabase_realtime'
  and tablename in ('sync_watch_history', 'sync_watched_status', 'sync_prefs')
order by tablename;
