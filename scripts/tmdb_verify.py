#!/usr/bin/env python3
"""Live TMDB verification for SearchBrowseCatalog ids + new candidates."""
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

def recent_tv(network_id):
    d = get("/discover/tv", with_networks=network_id, sort_by="first_air_date.desc",
            **{"vote_count.gte": 5, "first_air_date.lte": TODAY, "page": 1})
    if "__error__" in d: return f"ERR {d['__error__']}"
    return [(x.get("name"), x.get("first_air_date")) for x in d.get("results", [])[:3]] or "EMPTY"

def recent_movie(company_id):
    d = get("/discover/movie", with_companies=company_id, sort_by="primary_release_date.desc",
            **{"vote_count.gte": 5, "primary_release_date.lte": TODAY, "page": 1})
    if "__error__" in d: return f"ERR {d['__error__']}"
    return [(x.get("title"), x.get("release_date")) for x in d.get("results", [])[:3]] or "EMPTY"

print("=" * 70)
print("SECTION 1: existing NETWORK entries (name match + RECENT replica)")
print("=" * 70)
NETWORKS = [
    (2, "ABC"), (6, "NBC"), (16, "CBS"), (19, "FOX"), (33, "MTV"),
    (43, "National Geographic"), (4, "BBC One"), (47, "Comedy Central"),
    (13, "Nickelodeon"), (56, "Cartoon Network"), (71, "The CW"),
    (174, "AMC"), (80, "Adult Swim"), (1267, "Freeform"), (49, "HBO"),
    (30, "USA Network"), (74, "Bravo"), (88, "FX"), (1035, "FXX"),
    (41, "TNT"), (68, "TBS"), (77, "Syfy"), (129, "A&E"), (34, "Lifetime"),
    (76, "E!"), (24, "BET"), (64, "Discovery"), (143, "Food Network"),
    (2076, "Paramount Network"), (21, "The WB"), (26, "Channel 4"),
]
for nid, expect in NETWORKS:
    d = get(f"/network/{nid}")
    name = d.get("name", f"ERR:{d.get('__error__')}")
    ok = "OK " if name.lower() == expect.lower() else "MISMATCH"
    print(f"[{ok}] network {nid} expect={expect!r} actual={name!r}")
    print("      recent:", recent_tv(nid))
    time.sleep(0.12)

print()
print("=" * 70)
print("SECTION 2: existing SERVICE network ids + provider ids")
print("=" * 70)
SERVICES = [
    # name, providerId, networkOrCompanyId, isCompany
    ("Netflix", 8, 213, False),
    ("Prime Video", 9, 1024, False),
    ("Disney+", 337, 2739, False),
    ("Apple TV+", 350, 2552, False),
    ("HBO Max", 1899, 3186, False),
    ("Hulu", 15, 453, False),
    ("Paramount+", 2303, 4330, False),
    ("Peacock", 386, 3353, False),
    ("Starz", 43, 318, False),
    ("Showtime", None, 67, False),
    ("Tubi", 73, 5187, False),
    ("Pluto TV", 300, 3245, False),
    ("Crunchyroll", 283, 1112, False),
]
for name, pid, nid, isco in SERVICES:
    d = get(f"/network/{nid}")
    nname = d.get("name", f"ERR:{d.get('__error__')}")
    print(f"service {name!r}: network {nid} -> {nname!r}")
    if pid:
        pm = get("/watch/providers/movie", watch_region="US")
        pv = get("/watch/providers/tv", watch_region="US")
        pmnames = {p["provider_id"]: p["provider_name"] for p in pm.get("results", [])}
        pvnames = {p["provider_id"]: p["provider_name"] for p in pv.get("results", [])}
        print(f"    provider {pid}: movie={pmnames.get(pid)!r} tv={pvnames.get(pid)!r}")
    print("    recent( originals net ):", recent_tv(nid))
    time.sleep(0.12)

print()
print("=" * 70)
print("SECTION 3: existing STUDIO (company) entries")
print("=" * 70)
STUDIOS = [
    (174, "Warner Bros. Pictures"), (2, "Walt Disney Pictures"),
    (33, "Universal Pictures"), (4, "Paramount Pictures"),
    (5, "Columbia Pictures"), (21, "Metro-Goldwyn-Mayer"),
    (25, "20th Century Fox"), (420, "Marvel Studios"), (9993, "DC"),
    (12, "New Line Cinema"), (1632, "Lionsgate"), (41077, "A24"),
    (3172, "Blumhouse Productions"), (3, "Pixar"), (6704, "Illumination"),
    (521, "DreamWorks Animation"), (10342, "Studio Ghibli"),
    (882, "TOHO"), (923, "Legendary Pictures"), (508, "Regency Enterprises"),
    (10146, "Focus Features"), (2251, "Sony Pictures Animation"),
    (10163, "Working Title"), (56, "Amblin Entertainment"),
    (79, "Village Roadshow Pictures"), (97, "Castle Rock Entertainment"),
    (60, "United Artists"), (41, "Orion Pictures"), (14, "Miramax"),
    (491, "Summit Entertainment"), (1088, "Alcon Entertainment"),
    (2188, "Artisan Entertainment"), (9195, "Touchstone Pictures"),
    (11461, "Bad Robot"), (10221, "Walden Media"), (3281, "GK Films"),
    (82819, "Skydance Media"), (143790, "Spyglass Media Group"),
    (559, "TriStar Pictures"), (3287, "Screen Gems"),
    (43, "Fox Searchlight Pictures"), (7295, "Relativity Media"),
]
for cid, expect in STUDIOS:
    d = get(f"/company/{cid}")
    name = d.get("name", f"ERR:{d.get('__error__')}")
    ok = "OK " if name.lower() == expect.lower() else "MISMATCH"
    print(f"[{ok}] company {cid} expect={expect!r} actual={name!r}")
    time.sleep(0.12)

print()
print("=" * 70)
print("SECTION 4: NEW network candidates (resolve by name)")
print("=" * 70)
NEW_NETWORKS = [
    "HGTV", "History", "Investigation Discovery", "TLC", "Animal Planet",
    "Science Channel", "American Heroes Channel", "MTV2", "CMT", "TV Land",
    "Nickelodeon", "Nick at Nite", "Disney Channel", "Disney XD",
    "Disney Junior", "Cartoon Network", "Boomerang", "Discovery Life",
    "OWN", "Oxygen", "WE tv", "UPtv", "Game Show Network", "Hallmark Channel",
    "Hallmark Movies & Mysteries", "INSP", "Great American Family",
    "Pop TV", "Comedy.TV", "Bounce TV", "Ion Television", "MyNetworkTV",
    "PBS", "Telemundo", "Univision", "UniMás", "Estrella TV", "truTV",
    "HBO2", "HBO Comedy", "Cinemax", "Starz Encore", "ESPN", "SEC Network",
    "Big Ten Network", "NFL Network", "NBA TV", "MLB Network", "NHL Network",
    "Golf Channel", "Tennis Channel", "CNBC", "MSNBC", "Fox News",
    "Fox Business", "BBC America", "BBC Two", "ITV1", "Channel 5",
    "Showtime 2", "The Movie Channel", "Flix", "Shox", "AMC Plus",
    "Reelz", "Discovery Family", "MotorTrend", "Velocity", "DIY Network",
    "Cooking Channel", "Destination America", "Road TV",
]
for q in NEW_NETWORKS:
    d = get("/search/network", query=q)
    res = d.get("results", [])
    if not res:
        print(f"  {q!r}: NO RESULTS")
    else:
        hits = ", ".join(f"{r['id']}={r['name']!r}" for r in res[:3])
        print(f"  {q!r}: {hits}")
    time.sleep(0.12)

print()
print("=" * 70)
print("SECTION 5: NEW studio/company candidates (resolve by name)")
print("=" * 70)
NEW_COMPANIES = [
    "Amazon MGM Studios", "Amazon Studios", "YouTube", "YouTube Premium",
    "Netflix Animation", "Greater Skies", "The Grande Orchestra",
    "Gorilla Postra", "Tubi Films", "Roku Originals", "The Roku Channel",
    "Pluto TV", "Hulu Originals", "Onyx Collective", "20th Television",
    "20th Century Fox Television", "ABC Signature", "Touchstone Television",
    "Warner Bros. Television", "Sony Pictures Television",
    "Universal Content Productions", "CBS Studios", "Lionsgate Television",
    "Metro-Goldwyn-Mayer Studios", "United Artists Releasing",
    "Open Road Films", "STX Entertainment", "Global Road Entertainment",
    "Broad Green Pictures", "Annapurna Pictures", "Plan B Entertainment",
    "Heyday Films", "Pokémon Company", "The Pokémon Company",
    "Lucasfilm", "Walt Disney Animation Studios", "Disneynature",
    "Blue Sky Studios", "Sony Pictures Classics", "Fox Atomic",
    "Warner Animation Group", "Cartoon Network Studios",
    "Nickelodeon Movies", "MTV Films", "Paramount Players",
    "Paramount Animation", "Dimension Films", "The Weinstein Company",
    "Reliance Entertainment", "Amasia Entertainment", "Pascal Pictures",
    "Broken Road Productions", "RatPac-Dune Entertainment",
    "Village Roadshow", "Shondaland", "Barunson E&A", "Moho Film",
    "CJ Entertainment", "Toho Studios", "Kadokawa", "Bandai Visual",
    "Sunrise", "Kyoto Animation", "Madhouse", "Toei Animation",
    "Wit Studio", "MAPPA", "Ufotable", "A-1 Pictures", "Bones",
    "Production I.G", "Shaft", "Trigger", "CoMix Wave Films",
    "Amblimation", "Imagine Entertainment", " Scott Free Productions",
    "Fuzzy Door", "Gracie Films", "Curmudgeon Films", "Harpo Productions",
    "Higher Ground", "SpringHill Company", "A24 Television",
    "Picturestart", "Temple Hill Entertainment", "Color Force",
    "Lionsgate", " Millennium Media", "Nu Image", "Campbell Grobman Films",
    "Participant", "Condé Nast Entertainment", "Vox Media Studios",
    "Documentary+, " "Blackfin", "Break Thru Films",
]
for q in NEW_COMPANIES:
    d = get("/search/company", query=q.strip())
    res = d.get("results", [])
    if not res:
        print(f"  {q!r}: NO RESULTS")
    else:
        hits = ", ".join(f"{r['id']}={r['name']!r}" for r in res[:3])
        print(f"  {q!r}: {hits}")
    time.sleep(0.12)

print()
print("=" * 70)
print("SECTION 6: new service provider ids (US registry)")
print("=" * 70)
pm = get("/watch/providers/movie", watch_region="US")
pv = get("/watch/providers/tv", watch_region="US")
allp = {}
for p in pm.get("results", []) + pv.get("results", []):
    allp[p["provider_id"]] = p["provider_name"]
for q in ["YouTube", "YouTube Premium", "Roku", "The Roku Channel", "Plex",
          "AMC Plus", "AMC+", "Discovery+", "Max", "ESPN", "Apple TV",
          "MGM Plus", "MGM+", "Cinemax", "Crackle", "Xumo", "Fandor",
          "Mubi", "Criterion Channel", "BritBox", "Acorn TV", "Shudder",
          "Allblktv", "ALLBLK", "BET+", "Hoopla", "Kanopy", "Vaudeville"]:
    matches = {k: v for k, v in allp.items() if q.lower() in v.lower()}
    print(f"  {q!r}: {matches}")

print()
print("SECTION 7: keyword sanity — resolve a few NEW keyword names")
KW = ["cyberbullying", "esports", "influencer", "vlogger", "gamer",
      "chess", "poker", "strip club", "lgbt", "nonbinary", "sleepover",
      "graduation", "quinceanera", "bar mitzvah", "election", "scandal",
      "wall street", "startup", "silicon valley", "artificial intelligence",
      "virtual reality", "space race", "astronaut", "mars", "moon landing",
      "piracy", "cowboy", "outlaw", "gold rush", "prohibition",
      "great depression", "holocaust", "titanic", "chernobyl", "pandemic",
      "virus", "outbreak", "survivalist", "prepper", "bunker",
      "body swap", "time machine", "parallel universe", "multiverse",
      "alternate reality", "doppelganger", "changeling", "shapeshifting",
      "immortality", "genie", "mermaid", "centaur", "valkyrie",
      "olympics", "figure skating", "gymnastics", "swimming", "running",
      "marathon", "climbing", "mountaineering", "surfing", "skateboarding",
      "motorsport", "formula 1", "rally", "nascar", "car racing",
      "air force", "navy", "marines", "special forces", "sniper",
      "spy ring", "double agent", "codebreaker", "cryptographer",
      "art theft", "bank robbery", "train robbery", "prison escape",
      "false imprisonment", "wrongful conviction", "death row",
      "billionaire", "heir", "aristocracy", "royal family", "monarchy",
      "queen", "king", "empress", "tsar", "dynasty"]
for q in KW:
    d = get("/search/keyword", query=q)
    res = d.get("results", [])
    exact = next((r for r in res if r["name"].lower() == q.lower()), None)
    if exact:
        cnt = get(f"/keyword/{exact['id']}/movies").get("total_results", "?")
        print(f"  OK {q!r} id={exact['id']} movies={cnt}")
    else:
        print(f"  MISS {q!r} (top: " + ", ".join(f"{r['name']!r}" for r in res[:2]) + ")")
    time.sleep(0.1)
print("DONE")
