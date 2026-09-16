#!/usr/bin/env python3
"""Pass 5: confirm candidate company ids via /company/{id}, locate missing
provider ids in the US registry, and re-probe the remaining networks with
alternate flagship shows (printing every network each probe carries so
misses are diagnosable)."""
import json, time, urllib.request, urllib.parse

API = "7539ae4ce828a013443d8e496396b125"
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

def norm(s):
    return (s or "").lower().replace("the ", "").replace("&", "and").replace(".", "").strip()

# id -> (expected name fragment). Confirmed when /company/{id} name contains it
# AND a movie discover returns results.
CANDIDATE_COMPANIES = [
    (210099, "Amazon MGM Studios"),
    (114247, "YouTube"),
    (171251, "Netflix Animation"),
    (290879, "The Roku Channel"),
    (156742, "Onyx Collective"),
    (21659, "20th Television"),
    (236613, "ABC Signature"),
    (1957, "Warner Bros. Television"),
    (11073, "Sony Pictures Television"),
    (7938, "Universal Content Productions"),
    (1081, "CBS"),
    (224196, "CBS Studios"),
    (28523, "Lionsgate Television"),
    (224800, "Lionsgate Television"),
    (10427, "Open Road Films"),
    (270158, "STX Entertainment"),
    (117057, "Annapurna Pictures"),
    (13184, "Annapurna Pictures"),
    (81, "Plan B Entertainment"),
    (1, "Lucasfilm"),
    (6125, "Walt Disney Animation"),
    (158526, "Walt Disney Animation"),
    (9383, "Blue Sky Studios"),
    (58, "Sony Pictures Classics"),
    (25120, "Warner Animation Group"),
    (7899, "Cartoon Network Studios"),
    (2348, "Nickelodeon Movies"),
    (746, "MTV Films"),
    (96540, "Paramount Players"),
    (24955, "Paramount Animation"),
    (147786, "Dimension Films"),
    (23, "Imagine Entertainment"),
    (1645, "Scott Free Productions"),
    (18, "Gracie Films"),
    (34209, "shondaland"),
    (7036, "CJ Entertainment"),
    (2073, "KADOKAWA"),
    (528, "Bandai Visual"),
    (3153, "SUNRISE"),
    (5438, "Kyoto Animation"),
    (3464, "Madhouse"),
    (5542, "Toei Animation"),
    (31058, "WIT STUDIO"),
    (21444, "MAPPA"),
    (5887, "ufotable"),
    (13113, "A-1 Pictures"),
    (2849, "BONES"),
    (529, "Production I.G"),
    (6689, "SHAFT"),
    (50908, "TRIGGER"),
    (3756, "CoMix Wave Films"),
    (12292, "Temple Hill Entertainment"),
    (5420, "Color Force"),
    (6735, "Participant"),
    (1020, "Millennium Media"),
    (12654, "The Pokémon Company"),
    (3052, "Troma Entertainment"),
    # Service companies from the final pass:
    (283072, "Plex"),
    (142877, "Shudder"),
    (226350, "AMC+"),
    (225743, "discovery plus"),
    (159102, "BritBox"),
    (212651, "Acorn TV"),
    (172801, "Allblk"),
    (135210, "DAZN"),
    (109306, "Tastemade"),
    (96320, "CuriosityStream"),
    (181515, "Sundance Now"),
    (304438, "Hallmark Media"),
    (279515, "Roku Media"),
    (6805, "Epix"),
    (57501, "fuboTV"),
    (198847, "Crunchyroll"),
]

print("== company id confirmation (/company/{id} + movie discover count) ==")
for cid, expect in CANDIDATE_COMPANIES:
    d = get(f"/company/{cid}")
    name = d.get("name")
    if not name:
        print(f"  DEAD/ERR company {cid} expect={expect!r}: {d.get('__error__')}")
        time.sleep(0.08)
        continue
    disc = get("/discover/movie", with_companies=cid,
               sort_by="primary_release_date.desc",
               **{"vote_count.gte": 5, "primary_release_date.lte": TODAY})
    total = disc.get("total_results", 0) if "__error__" not in disc else -1
    newest = disc.get("results", [{}])[0].get("release_date", "?")[:4] if disc.get("results") else "-"
    ok = "OK " if expect.lower() in name.lower() else "NAME-MISMATCH"
    print(f"  [{ok}] company {cid}: {name!r} movies={total} newest={newest} (expect {expect!r})")
    time.sleep(0.08)

print("== full US provider registry scan (name contains key) ==")
pm = get("/watch/providers/movie", watch_region="US")
pv = get("/watch/providers/tv", watch_region="US")
allp = {}
for p in pm.get("results", []) + pv.get("results", []):
    allp[p["provider_id"]] = p["provider_name"]
for key in ["AMC", "Discovery +", "Hallmark", "ESPN", "MGM", "Starz", "Freevee",
            "Roku", "Plex", "Shudder", "ALLBLK", "Sundance", "Curiosity",
            "Tastemade", "fubo", "Xumo", "DAZN", "MUBI", "Criterion", "Acorn",
            "hoopla", "Kanopy", "BritBox", "Peacock", "Paramount", "Apple TV",
            "YouTube", "Max", "Crave", "Tubi", "Pluto"]:
    matches = {k: v for k, v in sorted(allp.items()) if key.lower() in v.lower()}
    print(f"  {key!r}: {matches}")

print("== remaining networks: probe + print ALL networks of each probe show ==")
def probe_all(display, probes):
    votes = {}
    for show in probes:
        d = get("/search/tv", query=show)
        res = d.get("results", [])
        if not res:
            print(f"    [{display}] search MISS {show!r}")
            continue
        detail = get(f"/tv/{res[0]['id']}")
        nets = [(n["id"], n["name"]) for n in detail.get("networks", []) or []]
        print(f"    [{display}] {show!r} -> {res[0]['name']!r} networks={nets}")
        for nid, nm in nets:
            votes[nid] = votes.get(nid, 0) + 1
        time.sleep(0.08)
    return votes

def best_recent(votes, display):
    if not votes:
        print(f"  UNRESOLVED {display!r}")
        return
    best = max(votes, key=votes.get)
    d = get(f"/network/{best}")
    disc = get("/discover/tv", with_networks=best, sort_by="first_air_date.desc",
               **{"vote_count.gte": 5, "first_air_date.lte": TODAY})
    rec = [(x.get("name"), (x.get("first_air_date") or "")[:4])
           for x in disc.get("results", [])[:3]] if "__error__" not in disc else "ERR"
    print(f"  => {display!r} candidate id {best} tmdb_name={d.get('name')!r} votes={votes}")
    print(f"       recent: {rec}")

v = probe_all("UPtv", ["Bringing Up Bates", "Date My Dad", "When Calls the Heart"])
best_recent(v, "UPtv")
v = probe_all("Hallmark Movies & Mysteries", ["Aurora Teagarden Mysteries", "Martha's Vineyard Mysteries", "Emma Fielding Mysteries"])
best_recent(v, "Hallmark Movies & Mysteries")
v = probe_all("Great American Family", ["When Hope Calls", "A Cinderella Christmas Ball", "Casa Grande"])
best_recent(v, "Great American Family")
v = probe_all("NFL Network", ["Good Morning Football", "NFL Total Access"])
best_recent(v, "NFL Network")
v = probe_all("MLB Network", ["MLB Tonight"])
best_recent(v, "MLB Network")
v = probe_all("Fox News", ["The Five", "Hannity", "Gutfeld!"])
best_recent(v, "Fox News")
v = probe_all("Channel 5 UK", ["All Creatures Great and Small", "The Durrells", "Dalgliesh"])
best_recent(v, "Channel 5 UK")
v = probe_all("AMC+ network", ["Dark Winds", "Kin", "Lucky Hank"])
best_recent(v, "AMC+ network")
v = probe_all("BET+", ["Tyler Perry's Divorced Sistas", "Lil Kev"])
best_recent(v, "BET+")
v = probe_all("MotorTrend+", ["Roadkill Garage", "Hot Rod Garage"])
best_recent(v, "MotorTrend+")
print("DONE")
