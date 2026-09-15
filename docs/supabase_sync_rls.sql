-- KBStream sync tables: row level security policies.
-- Run once in Supabase SQL Editor (Database → SQL Editor → New query).
--
-- What this fixes: the sync tables exist but only allow SELECT for
-- authenticated users — every INSERT/UPDATE/DELETE fails with
-- 42501 "new row violates row-level security policy". The app hits this
-- on its first sync (sign-in seeds the cloud with local rows).
--
-- Security model: rows carry no per-user column; isolation is the app's
-- account boundary + per-profile key prefixes. Policies below grant full
-- access to ANY signed-in account on the project. Fine for a personal
-- project where you create the accounts; if you ever open sign-ups to
-- strangers, add a user_id column and scope policies on auth.uid().

alter table public.sync_watch_history enable row level security;
alter table public.sync_watched_status enable row level security;
alter table public.sync_prefs enable row level security;

drop policy if exists "kbstream_authenticated_all_sync_watch_history"
  on public.sync_watch_history;
create policy "kbstream_authenticated_all_sync_watch_history"
  on public.sync_watch_history
  for all
  to authenticated
  using (true)
  with check (true);

drop policy if exists "kbstream_authenticated_all_sync_watched_status"
  on public.sync_watched_status;
create policy "kbstream_authenticated_all_sync_watched_status"
  on public.sync_watched_status
  for all
  to authenticated
  using (true)
  with check (true);

drop policy if exists "kbstream_authenticated_all_sync_prefs"
  on public.sync_prefs;
create policy "kbstream_authenticated_all_sync_prefs"
  on public.sync_prefs
  for all
  to authenticated
  using (true)
  with check (true);
