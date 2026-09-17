#!/usr/bin/env python3
"""Pass 6 micro-probes: last stubborn networks."""
import json, time, urllib.request, urllib.parse

import os

# Key comes from the environment (never commit it) — e.g. export it from
# local.properties's TMDB_API_KEY before running.
API = os.environ.get("TMDB_API_KEY", "")
BASE = "https://api.themoviedb.org/3"
TODAY = "2026-09-16"

def get(path, **params):
    params["api_key"] = API
    url = BASE + path + "?" + urllib.parse.urlencode(params)
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=15) as r:
                return json.loads(r.read().decode())
        except Exception as e:
            if attempt == 2:
                return {"__error__": str(e)}
            time.sleep(0.5)

def recent(nid):
    d = get("/discover/tv", with_networks=nid, sort_by="first_air_date.desc",
            **{"vote_count.gte": 5, "first_air_date.lte": TODAY})
    if "__error__" in d: return f"ERR"
    return [(x.get("name"), (x.get("first_air_date") or "")[:4]) for x in d.get("results", [])[:4]] or "EMPTY"

print("== direct id checks ==")
for nid in [385, 5303, 1307, 2853]:
    d = get(f"/network/{nid}")
    print(f"  network {nid}: {d.get('name', 'ERR')!r} recent={recent(nid)}")
    time.sleep(0.1)

print("== show probes (print all networks) ==")
for show in ["Billy the Kid", "From 2022", "Sunday Night Football", "Monday Night Football",
             "MLB World Series", "Bringing Up Bates", "A Christmas Less Ordinary",
             "Christmas on Candy Cane Lane", "The Way Home"]:
    d = get("/search/tv", query=show)
    res = d.get("results", [])
    if not res:
        print(f"  search MISS {show!r}")
        continue
    detail = get(f"/tv/{res[0]['id']}")
    nets = [(n["id"], n["name"]) for n in detail.get("networks", []) or []]
    print(f"  {show!r} -> {res[0]['name']!r} networks={nets}")
    time.sleep(0.1)
print("DONE")
