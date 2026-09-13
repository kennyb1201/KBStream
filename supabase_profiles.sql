-- KBStream profiles: run once in Supabase SQL Editor.
-- Profiles live as rows keyed by the owning account; the app carries the
-- list in the sync_prefs "profiles" blob, so the table below is optional —
-- but creating it lets you inspect/manage profiles from the dashboard.
create table if not exists public.kbstream_profiles (
  user_id uuid not null references auth.users on delete cascade,
  profile_id uuid not null,
  name text not null,
  avatar_index int not null default 0,
  created_at timestamptz not null default now(),
  primary key (user_id, profile_id)
);

alter table public.kbstream_profiles enable row level security;

create policy "own profiles all" on public.kbstream_profiles
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
