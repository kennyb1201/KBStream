# Play listing — copy and assets

Phase 4 needs a phone listing in addition to the TV one. The copy below is
written to be pasted into Play Console as-is; the assets cannot be produced here
(there is no device or emulator in the build sandbox), so they are a capture
list with the exact states to shoot.

The TV banner stays the TV listing's; this file is the phone listing.

## Copy

**App name** (30 chars max)

```
KBStream
```

**Short description** (80 chars max)

```
Your add-ons, your library, your streams — on Android TV and on your phone.
```

**Full description** (4000 chars max)

```
KBStream is a personal streaming hub for people who already know where their
media lives.

Connect your add-ons, point it at your IPTV playlist and EPG, and KBStream puts
everything behind one interface: a home screen built from the rails you choose,
a library of what you actually watch, and a player that stays out of the way.

BUILT FOR THE BIG SCREEN
• D-pad first: every screen is reachable with a remote, with no pointer in sight
• Dolby Vision, HDR10 and frame-rate matching handled per display
• ExoPlayer for everyday streams, MPV for the files that need it, or hand off to
  VLC, MX Player or Kodi
• Channel guide with EPG import, favourites and REMIND ME alerts

AND IT WORKS ON YOUR PHONE
• The same library, profiles and watch history, with layouts built for touch
• Tap for controls, swipe to seek, drag for brightness or volume, pinch to
  change the aspect, hold to go fullscreen
• Picture-in-picture, so browsing does not stop what is playing

YOUR DATA, YOUR ACCOUNTS
• Trakt, Simkl and MDBList sync, TMDB metadata and artwork
• Multiple profiles with a kids mode that is a lock, not a filter
• Add-on manifests, per-title source memory and subtitle search

KBStream ships no content and hosts nothing. It plays what you point it at:
your add-ons, your playlist, your files.

Add-on support follows the Stremio add-on protocol. IPTV support reads M3U
playlists and XMLTV guides.
```

**What's new** (500 chars max, per release)

```
The phone build lands: every screen has a touch layout, the player answers a
finger instead of a remote (tap, swipe, pinch, hold), and the phone is a
first-class device in the same single APK — your profiles, library, add-ons and
watch history carry over from the TV with no separate install.
```

## Assets

| Asset | Spec | Source |
|---|---|---|
| App icon | 512 × 512 PNG, 32-bit, no transparency | `app/src/main/ic_launcher` art, exported at 512 |
| Feature graphic | 1024 × 500 PNG or JPEG, no alpha | **to make**: brand mark on the void background, no device mockup |
| Phone screenshots | 2–8, PNG/JPEG, ≥ 1080 px on the short side, 9:16 portrait | capture list below |
| TV banner | 1280 × 720 PNG | already shipped for the TV listing — reuse |
| Promo video | optional, YouTube URL | not planned |

### Phone screenshot shot list

Capture in this order; each state exists in the app already. Use a light-mode
and a dark-mode device pair only if the listing wants both — otherwise AMOLED off
(the default) for consistency.

1. **Home** — arranged rails loaded, one poster focused/pressed so the ripple is
   in frame. This is the store thumbnail.
2. **Detail** — a film with a clearlogo, PLAY button and the action row visible.
3. **Player, portrait, chrome up** — the video band on top and the controls
   below it (the Phase 3 layout), playing something with subtitles on.
4. **Player, landscape** — a frame from the same title with the chrome hidden,
   to show the picture and nothing else.
5. **Live TV guide** — channel list with now/next populated.
6. **Sports hub** — a league with a live game card.
7. **Settings** — the Playback section (the phone's two-column rows).
8. **Library** — a populated watched/watchlist grid.

Capture with the debug build over adb, then crop nothing: Play accepts the raw
frames as long as the short side is ≥ 1080 px.

```bash
adb shell screencap -p /sdcard/shot-home.png
adb pull /sdcard/shot-home.png
```

Keep the system status bar and navigation bar visible in at least one shot;
that is what a phone screenshot is expected to look like.

## Pre-publish checklist

- [ ] Feature graphic exists at 1024 × 500
- [ ] All screenshots ≥ 1080 px on the short side, no TV UI in any of them
- [ ] Short description ≤ 80 characters (the one above is 74)
- [ ] Full description pasted without markdown (Play renders plain text)
- [ ] `What's new` matches the release being uploaded
- [ ] Data safety form updated for the phone build (same data as before: no new
      collection paths were added by the port)
- [ ] TV listing untouched: its banner and its screenshots still describe the
      TV app
