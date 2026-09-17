#!/usr/bin/env python3
"""Pass 3: retry unresolved networks + verify ESPN + new companies + providers + keywords."""
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
        time.sleep(0.08)
    if not votes:
        print(f"  UNRESOLVED {display!r}")
        return
    best = max(votes, key=votes.get)
    d = get(f"/network/{best}")
    name = d.get("name", "?")
    disc = get("/discover/tv", with_networks=best, sort_by="first_air_date.desc",
               **{"vote_count.gte": 5, "first_air_date.lte": TODAY})
    rec = [(x.get("name"), (x.get("first_air_date") or "")[:4])
           for x in disc.get("results", [])[:3]] if "__error__" not in disc else "ERR"
    print(f"  RESOLVED {display!r} -> id {best} tmdb_name={name!r} votes={votes}")
    print(f"      recent: {rec}")

print("== round 2 networks ==")
resolve_network("Boomerang", ["Yabba Dabba Dinosaurs", "Scooby-Doo and Guess Who?"], ["Boomerang"])
resolve_network("UPtv", ["Bringing Up Bates", "Living with Joan Lunden"], ["UPtv", "UP!"])
resolve_network("Hallmark Movies & Mysteries", ["Aurora Teagarden Mysteries", "Martha's Vineyard Mysteries"], ["Hallmark Movies & Mysteries", "Hallmark Family"])
resolve_network("Great American Family", ["When Hope Calls", "Casa Grande"], ["Great American Family", "GAF"])
resolve_network("NFL Network", ["Good Morning Football", "NFL Total Access"], ["NFL Network"])
resolve_network("MLB Network", ["MLB Tonight"], ["MLB Network"])
resolve_network("Fox News", ["The Five", "Hannity", "Gutfeld!"], ["Fox News", "FOX News"])
resolve_network("Channel 5", ["All Creatures Great and Small", "The Durrells"], ["Channel 5"])
resolve_network("ESPN recheck", ["The Last Dance", "30 for 30"], ["ESPN"])

print("== new companies ==")
NEW_COMPANIES = [
    "Amazon MGM Studios", "Amazon Studios", "YouTube", "YouTube Premium",
    "Netflix Animation", "Roku Originals", "The Roku Channel",
    "Hulu Originals", "Onyx Collective", "20th Television",
    "ABC Signature", "Warner Bros. Television", "Sony Pictures Television",
    "Universal Content Productions", "CBS Studios", "Lionsgate Television",
    "Open Road Films", "STX Entertainment", "Annapurna Pictures",
    "Plan B Entertainment", "Lucasfilm", "Walt Disney Animation Studios",
    "Blue Sky Studios", "Sony Pictures Classics",
    "Warner Animation Group", "Cartoon Network Studios",
    "Nickelodeon Movies", "MTV Films", "Paramount Players",
    "Paramount Animation", "Dimension Films",
    "Imagine Entertainment", "Scott Free Productions", "Gracie Films",
    "Shondaland", "CJ Entertainment", "Kadokawa", "Bandai Visual",
    "Sunrise", "Kyoto Animation", "Madhouse", "Toei Animation",
    "Wit Studio", "MAPPA", "Ufotable", "A-1 Pictures", "Bones",
    "Production I.G", "Shaft", "Trigger", "CoMix Wave Films",
    "Temple Hill Entertainment", "Color Force", "Participant",
    "Millennium Media", "Pokémon Company", "Toho", "Troma",
]
for q in NEW_COMPANIES:
    d = get("/search/company", query=q)
    res = d.get("results", [])
    if not res:
        print(f"  MISS {q!r}")
    else:
        hits = ", ".join(f"{r['id']}={r['name']!r}" for r in res[:2])
        print(f"  {q!r}: {hits}")
    time.sleep(0.1)

print("== new provider ids (US registry) ==")
pm = get("/watch/providers/movie", watch_region="US")
pv = get("/watch/providers/tv", watch_region="US")
allp = {}
for p in pm.get("results", []) + pv.get("results", []):
    allp[p["provider_id"]] = p["provider_name"]
for q in ["Roku", "Plex", "AMC Plus", "Discovery", "Max", "ESPN Plus",
          "MGM Plus", "Crackle", "Xumo", "Mubi", "Criterion", "BritBox",
          "Acorn", "Shudder", "ALLBLK", "BET", "Hoopla", "Kanopy",
          "Fubo", "Sling", "YouTube", "Apple TV", "Peacock", "Freevee",
          "Hoopla", "Dove", "Great American", "Hallmark", "Interest"]:
    matches = {k: v for k, v in allp.items() if q.lower() in v.lower()}
    print(f"  {q!r}: {matches}")

print("== new keywords ==")
KW = ["esports", "influencer", "gamer", "chess", "poker",
      "election", "wall street", "startup", "artificial intelligence",
      "virtual reality", "space race", "astronaut", "moon landing",
      "cowboy", "outlaw", "gold rush", "prohibition",
      "pandemic", "outbreak", "body swap", "time machine",
      "parallel universe", "multiverse", "doppelganger", "shapeshifting",
      "immortality", "genie", "mermaid", "olympics", "gymnastics",
      "surfing", "skateboarding", "formula 1", "nascar",
      "air force", "navy", "marines", "sniper", "double agent",
      "art theft", "bank robbery", "prison escape", "wrongful conviction",
      "death row", "billionaire", "royal family", "monarchy",
      "bank heist", "hacking", "con artist", "undercover",
      "small town", "big city", "amusement park", "circus",
      "circus performer", "summer camp", "road trip", "bachelorette party",
      "midlife crisis", "empty nest", "toxic relationship",
      " obsessive love", "stalker", "conspiracy", "cover-up",
      "whistleblower", "journalist", "lawyer", "judge", "jury",
      "forensic science", "csi", "autopsy", "medical examination",
      "hospital", "doctor", "nurse", "surgeon", "paramedic",
      "firefighter", "police officer", "detective inspector",
      "bounty hunter", "cowgirl", "gunslinger", " saloon",
      "banker", "stockbroker", "hedge fund", "crypto",
      "influencer culture", "social media", " paparazzi",
      "fashion", "model", "musician", "band", "rapper",
      "dj", "stand-up comedian", "broadway", "theatre", "opera",
      "ballet", "dancer", "choir", "military", "veteran",
      "ptsd", "homelessness", "poverty", "immigrant",
      "american dream", "racism", "civil rights", "suffrage"]
for q in KW:
    q = q.strip()
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
