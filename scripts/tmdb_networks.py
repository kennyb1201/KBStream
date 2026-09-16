#!/usr/bin/env python3
"""Pass 2: resolve NEW networks via flagship-show probes (no /search/network on TMDB)."""
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

def resolve_network(display, probes, variants):
    votes = {}
    for show in probes:
        d = get("/search/tv", query=show)
        for r in d.get("results", [])[:2]:
            detail = get(f"/tv/{r['id']}")
            for net in detail.get("networks", []) or []:
                if norm(net.get("name")) in {norm(v) for v in variants}:
                    votes[net["id"]] = votes.get(net["id"], 0) + 1
        time.sleep(0.1)
    if not votes:
        print(f"  UNRESOLVED {display!r} (no network match via probes)")
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

CANDIDATES = [
    ("HGTV", ["House Hunters", "Fixer to Fabulous"], ["HGTV"]),
    ("History", ["The Curse of Oak Island", "Ancient Aliens"], ["History"]),
    ("Investigation Discovery", ["On the Case with Paula Zahn", "See No Evil"], ["Investigation Discovery", "ID"]),
    ("TLC", ["90 Day Fiance", "My 600-lb Life"], ["TLC"]),
    ("Animal Planet", ["River Monsters", "The Zoo"], ["Animal Planet"]),
    ("Science Channel", ["How the Universe Works", "Mysteries of the Abandoned"], ["Science Channel", "Science"]),
    ("CMT", ["Dallas Cowboys Cheerleaders: Making the Team", "Party Down South"], ["CMT"]),
    ("TV Land", ["Hot in Cleveland", "Younger"], ["TV Land"]),
    ("Disney Channel", ["Big City Greens", "Zombies: The Re-Animated Series"], ["Disney Channel"]),
    ("Disney XD", ["DuckTales", "Ultimate Spider-Man"], ["Disney XD"]),
    ("Disney Junior", ["Puppy Dog Pals", "Mira, Royal Detective"], ["Disney Junior"]),
    ("Boomerang", ["Scooby-Doo and Guess Who?", "Looney Tunes Cartoons"], ["Boomerang"]),
    ("OWN", ["The Haves and the Have Nots", "All Rise"], ["OWN", "Oprah Winfrey Network"]),
    ("Oxygen", ["Snapped", "Dateline: Secrets Uncovered"], ["Oxygen"]),
    ("WE tv", ["Love After Lockup", "Growing Up Hip Hop"], ["WE tv"]),
    ("UPtv", ["Bringing Up Bates", "Date My Dad"], ["UPtv", "UP!"]),
    ("Game Show Network", ["America Says", "Common Knowledge"], ["Game Show Network", "GSN"]),
    ("Hallmark Channel", ["The Way Home", "When Calls the Heart"], ["Hallmark Channel"]),
    ("Hallmark Family", ["Martha's Vineyard Mysteries", "Emma Fielding Mysteries"], ["Hallmark Family", "Hallmark Movies & Mysteries"]),
    ("INSP", ["Ultimate Cowboy Showdown", "Courtship"], ["INSP"]),
    ("Great American Family", ["A Christmas Less Ordinary", "Casa Grande"], ["Great American Family", "GAF"]),
    ("Pop TV", ["Schitt's Creek", "One Day at a Time"], ["Pop TV", "Pop"]),
    ("Bounce TV", ["Saints & Sinners", "Johnson"], ["Bounce TV"]),
    ("PBS", ["Frontline", "Nature"], ["PBS"]),
    ("Telemundo", ["La Reina del Sur", "El Senor de los Cielos"], ["Telemundo"]),
    ("Univision", ["La Rosa de Guadalupe", "Aqui y Ahora"], ["Univision", "Univision Network"]),
    ("truTV", ["Impractical Jokers", "Tacoma FD"], ["truTV"]),
    ("Cinemax", ["Warrior", "Jett"], ["Cinemax"]),
    ("ESPN", ["SportsCenter", "Peyton's Places"], ["ESPN"]),
    ("NFL Network", ["Good Morning Football"], ["NFL Network"]),
    ("MLB Network", ["MLB Tonight"], ["MLB Network"]),
    ("CNBC", ["American Greed"], ["CNBC"]),
    ("MSNBC", ["Morning Joe"], ["MSNBC"]),
    ("Fox News", ["The Five", "Hannity"], ["Fox News"]),
    ("BBC America", ["Orphan Black", "Killing Eve"], ["BBC America"]),
    ("BBC Two", ["Top Gear", "QI"], ["BBC Two"]),
    ("ITV1", ["Coronation Street", "Mr Bates vs The Post Office"], ["ITV1", "ITV"]),
    ("Channel 5", ["All Creatures Great and Small", "The Durrells"], ["Channel 5"]),
    ("Discovery Family", ["My Little Pony: Friendship Is Magic"], ["Discovery Family"]),
    ("MotorTrend", ["Roadkill", "Bitchin' Rides"], ["MotorTrend", "Velocity"]),
    ("Cooking Channel", ["Man Fire Food", "Unique Eats"], ["Cooking Channel"]),
    ("Destination America", ["Mountain Monsters"], ["Destination America"]),
    ("AMC Plus", ["Dark Winds", "Kin"], ["AMC+", "AMC Plus"]),
]
for display, probes, variants in CANDIDATES:
    resolve_network(display, probes, variants)
print("NETWORK PASS DONE")
