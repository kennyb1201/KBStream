#!/usr/bin/env python3
"""Live TMDB verification for SearchBrowseCatalog collection chip names.

The browse catalog's Collections submenu is a list of franchise NAMES that
SearchViewModel resolves at runtime via /search/collection, preferring an
exact-name hit (an inexact name silently loses the chip even when TMDB has a
page for it). New names therefore have to be checked against the live endpoint
before they are committed, exactly like the keyword/service/studio probes:

  python3 scripts/tmdb_collections.py            # the candidate lists below
  python3 scripts/tmdb_collections.py "Name" ...  # ad-hoc names

Each line prints EXACT (with the canonical id and how many films the
collection page carries), MISS (with TMDB's nearest hits) or ERR. Only EXACT
hits with 2+ films are worth shipping as a franchise chip.

Key comes from the environment (never commit it) — the workspace .env/.env.local
already carry TMDB_API_KEY.
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

API = os.environ.get("TMDB_API_KEY", "")
BASE = "https://api.themoviedb.org/3"


def get(path, **params):
    params["api_key"] = API
    url = BASE + path + "?" + urllib.parse.urlencode(params)
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=15) as r:
                return json.loads(r.read().decode())
        except Exception as e:  # noqa: BLE001 - probe tool, report and move on
            if attempt == 2:
                return {"__error__": str(e)}
            time.sleep(0.6)
    return {"__error__": "unreachable"}


def check(name):
    d = get("/search/collection", query=name)
    if "__error__" in d:
        return "ERR", d["__error__"]
    results = d.get("results", [])
    exact = next(
        (r for r in results if r["name"].strip().lower() == name.strip().lower()),
        None,
    )
    if exact is None:
        top = ", ".join(repr(r["name"]) for r in results[:2]) or "(none)"
        return "MISS", top
    detail = get(f"/collection/{exact['id']}")
    titles = [
        (p.get("title") or "") + (f" ({(p.get('release_date') or '')[:4]})" if p.get("release_date") else "")
        for p in (detail.get("parts", []) or [])
    ]
    shown = ", ".join(t for t in titles[:3] if t)
    more = "" if len(titles) <= 3 else f" +{len(titles) - 3}"
    return "EXACT", f"id={exact['id']} parts={len(titles)} [{shown}{more}]"


# Candidate adult-leaning franchises (non-kids: crime, action, horror, sci-fi,
# western, comedy, prestige) — appended to BROWSE_COLLECTION_NAMES when EXACT.
ADULT_CANDIDATES = [
    "Sicario Collection", "Jack Reacher Collection", "Den of Thieves Collection",
    "Extraction Collection", "The Mechanic Collection", "Death Race Collection",
    "xXx Collection", "Riddick Collection", "Escape Plan Collection",
    "The Hitman's Bodyguard Collection", "Universal Soldier Collection",
    "Conan the Barbarian Collection", "G.I. Joe Collection", "Silent Hill Collection",
    "Hitman Collection", "Tomb Raider Collection", "Mortal Kombat Collection",
    "Kick-Ass Collection", "Hellboy Collection", "Scarface Collection",
    "Cloverfield Collection", "Knives Out Collection", "Twister Collection",
    "Speed Collection", "Point Break Collection", "Starship Troopers Collection",
    "Stargate Collection", "Total Recall Collection", "The Thing Collection",
    "Terrifier Collection", "Hatchet Collection", "Hostel Collection",
    "Wolf Creek Collection", "The Collector Collection", "Candyman Collection",
    "The Omen Collection", "Poltergeist Collection", "Pet Sematary Collection",
    "Sinister Collection", "It Collection", "Ip Man Collection",
    "Ong-Bak Collection", "The Raid Collection", "Infernal Affairs Collection",
    "Train to Busan Collection", "True Grit Collection",
    "The Magnificent Seven Collection", "Young Guns Collection", "Ted Collection",
    "21 Jump Street Collection", "Horrible Bosses Collection", "Neighbors Collection",
    "Vacation Collection", "Ace Ventura Collection", "Shanghai Noon Collection",
    "The Twilight Saga Collection", "After Collection", "Step Up Collection",
    "Bring It On Collection", "Grease Collection", "Mamma Mia! Collection",
    "Barbershop Collection", "Ride Along Collection", "Madea Collection",
    "The Girl with the Dragon Tattoo Collection", "King Kong Collection",
    "Anaconda Collection", "Lake Placid Collection", "Piranha Collection",
    "The Dollars Trilogy", "The Man with No Name Collection",
    "Sherlock Holmes Collection", "Hercule Poirot Collection",
    "The Da Vinci Code Collection", "The Robert Langdon Collection",
    "The Addams Family Collection", "The Wizard of Oz Collection",
    "Journey to the Center of the Earth Collection", "Bratz Collection",
    "Minecraft Collection", "The Expendables Collection", "The Purge Collection",
    "Blade Collection", "The Texas Chainsaw Massacre Collection",
]

# Candidate kids franchises — appended to KIDS_COLLECTION_NAMES when EXACT.
KIDS_CANDIDATES = [
    "Honey, I Shrunk the Kids Collection", "Minions Collection",
    "Space Jam Collection", "Tom and Jerry Collection", "Looney Tunes Collection",
    "Popeye Collection", "Peanuts Collection", "The NeverEnding Story Collection",
    "Free Willy Collection", "Benji Collection", "The Brave Little Toaster Collection",
    "Spirit Collection", "Dora the Explorer Collection",
    "Clifford the Big Red Dog Collection", "Sesame Street Collection",
    "Thomas & Friends Collection", "Peppa Pig Collection", "Power Rangers Collection",
    "My Little Pony Collection", "Masters of the Universe Collection",
    "Digimon Collection", "Yu-Gi-Oh! Collection", "Dragon Ball Collection",
    "Naruto Collection", "One Piece Collection", "Sailor Moon Collection",
    "Doraemon Collection", "Mario Collection", "Super Mario Bros. Collection",
    "Matilda Collection", "Willy Wonka Collection",
    "Charlie and the Chocolate Factory Collection", "Dr. Seuss Collection",
    "How the Grinch Stole Christmas Collection", "Yogi Bear Collection",
    "Strawberry Shortcake Collection", "Blue's Clues Collection",
    "Bob the Builder Collection", "Fireman Sam Collection", "Postman Pat Collection",
    "The Wiggles Collection", "Berenstain Bears Collection", "Little Bear Collection",
    "Franklin Collection", "Caillou Collection", "The Magic School Bus Collection",
    "The Jetsons Collection", "Rudolph the Red-Nosed Reindeer Collection",
    "Teen Titans Collection", "Ben 10 Collection", "Snoopy Collection",
    "The Pink Panther Collection", "Chip 'n Dale Collection",
    "Aladdin Collection", "The Little Mermaid Collection", "Wreck-It Ralph Collection",
    "Big Hero 6 Collection", "Zootopia Collection", "Moana Collection",
    "Raya and the Last Dragon Collection", "Luca Collection", "Soul Collection",
    "Onward Collection", "Cars Collection", "Planes Collection",
    "Tinker Bell Collection", "The Tigger Movie Collection",
]


def main():
    if not API:
        print("TMDB_API_KEY is not set — nothing to verify.")
        return
    argv = [a for a in sys.argv[1:]]
    batches = (
        [("ADULT CANDIDATES", argv, "browse")]
        if argv
        else [
            ("ADULT CANDIDATES", ADULT_CANDIDATES, "browse"),
            ("KIDS CANDIDATES", KIDS_CANDIDATES, "kids"),
        ]
    )
    for label, names, _target in batches:
        print("=" * 70)
        print(label)
        print("=" * 70)
        for name in names:
            status, info = check(name)
            print(f"[{status:5}] {name}  {info}")
            time.sleep(0.12)
    print("DONE")


if __name__ == "__main__":
    main()
