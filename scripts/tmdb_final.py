#!/usr/bin/env python3
"""Pass 4: final lookups — streamer network ids, service company ids, remaining keywords."""
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

def norm(s):
    return (s or "").lower().replace("the ", "").replace("&", "and").replace(".", "").strip()

print("== streamer network ids ==")
for nid in [3343, 4262, 4012, 5553, 4331]:
    d = get(f"/network/{nid}")
    name = d.get("name", f"ERR {d.get('__error__')}")
    disc = get("/discover/tv", with_networks=nid, sort_by="first_air_date.desc",
               **{"vote_count.gte": 5, "first_air_date.lte": TODAY})
    rec = [(x.get("name"), (x.get("first_air_date") or "")[:4])
           for x in disc.get("results", [])[:3]] if "__error__" not in disc else "ERR"
    print(f"  network {nid}: {name!r} recent: {rec}")
    time.sleep(0.1)

def resolve_network(display, probes, variants):
    votes = {}
    for show in probes:
        d = get("/search/tv", query=show)
        for r in d.get("results", [])[:2]:
            detail = get(f"/tv/{r['id']}")
            for net in detail.get("networks", []) or []:
                if norm(net.get("name")) in {norm(v) for v in variants}:
                    votes[net["id"]] = votes.get(net["id"], 0) + 1
            time.sleep(0.08)
    if not votes:
        print(f"  UNRESOLVED {display!r}")
        return
    best = max(votes, key=votes.get)
    d = get(f"/network/{best}")
    print(f"  RESOLVED {display!r} -> id {best} tmdb_name={d.get('name')!r} votes={votes}")

print("== retry nets ==")
resolve_network("Hallmark Movies & Mysteries", ["Emma Fielding Mysteries", "Ruby Herring Mysteries"], ["Hallmark Movies & Mysteries", "Hallmark Family"])
resolve_network("Great American Family", ["When Hope Calls", "The Great Christmas Switch"], ["Great American Family"])
resolve_network("NBA TV", ["Open Court"], ["NBA TV"])
resolve_network("NFL Network", ["NFL GameDay Morning"], ["NFL Network"])
resolve_network("Fox News", ["Gutfeld!"], ["Fox News"])
resolve_network("Channel 5", ["The Durrells", "Dalgliesh"], ["Channel 5"])

print("== service companies ==")
for q in ["Plex", "Shudder", "AMC Plus", "AMC+", "Discovery Plus", "Discovery+",
          "Epix", "MGM+", "BritBox", "Acorn TV", "ALLBLK", "fuboTV",
          "Xumo", "Crunchyroll LLC", "DAZN", "ESPN Plus", "Tastemade",
          "CuriosityStream", "Sundance Now", "IFC Films Unlimited",
          "Hallmark Media", "GAC Media", "Roku Media"]:
    d = get("/search/company", query=q)
    res = d.get("results", [])
    hits = ", ".join(f"{r['id']}={r['name']!r}" for r in res[:2]) if res else "NO RESULTS"
    print(f"  {q!r}: {hits}")
    time.sleep(0.1)

print("== remaining keywords ==")
KW = ["formula 1", "nascar", "car racing", "boxing", "mixed martial arts",
      "wrestling", "bank heist", "hacking", "con artist", "undercover",
      "small town", "amusement park", "circus", "summer camp",
      "midlife crisis", "toxic relationship", "obsessive love", "stalker",
      "conspiracy", "cover-up", "whistleblower", "journalist", "lawyer",
      "jury", "forensics", "hospital", "doctor", "nurse", "surgeon",
      "firefighter", "police", "bounty hunter", "gunslinger",
      "stockbroker", "social media", "paparazzi", "fashion", "model",
      "musician", "band", "rapper", "stand-up comedian", "broadway",
      "ballet", "military", "veteran", "homelessness", "immigrant",
      "american dream", "civil rights", "suffrage", "cult leader",
      "doomsday", "nuclear war", "alien contact", "first contact",
      "artificial satellite", "deep space", "colonization", "terraforming",
      "cryogenics", "cloning", "genetic engineering", "mutation",
      "super strength", "superhero team", "vigilante", "antihero",
      "sword and sorcery", "magical object", "cursed object", "haunted doll",
      "ouija", "seance", "medium", "exorcist", "demon", "antichrist",
      "apocalypse", "ragnarok", "flood", "volcano", "earthquake",
      "tornado", "hurricane", "shark", "snake", "spider", "crocodile",
      "bear", "wolf pack", "locust", "infestation"]
for q in KW:
    d = get("/search/keyword", query=q)
    res = d.get("results", [])
    exact = next((r for r in res if r["name"].lower() == q.lower()), None)
    if exact:
        cnt = get(f"/keyword/{exact['id']}/movies").get("total_results", "?")
        print(f"  OK {q!r} id={exact['id']} movies={cnt}")
    else:
        print(f"  MISS {q!r} (top: " + ", ".join(f"{r['name']!r}" for r in res[:2]) + ")")
    time.sleep(0.08)
print("DONE")
