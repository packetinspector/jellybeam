#!/usr/bin/env python3
"""Showcase Jellyfin seeding (tools/showcase-server/up.sh): wizard, libraries, scan, match report,
and watch state so Continue Watching and Next Up have something to show. Idempotent."""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8098"
USER, PASSWORD = "sam", "showcase"
SERVER_NAME = "Living Room"
CLIENT = 'Client="Jellybeam Showcase Setup", Device="setup-script", DeviceId="jellybeam-showcase", Version="1.0.0"'
LIBRARIES = [("Films", "movies"), ("Series", "tvshows"), ("Open Movies", "movies")]
TICKS_PER_MIN = 600_000_000

# Watch state: (title, fraction watched or "played", minutes ago). Newer entries lead the rows.
MOVIE_STATE = [
    ("Sintel", 0.35, 30),
    ("Charade", 0.62, 60 * 20),
    ("Metropolis", 0.41, 60 * 50),
    ("Nosferatu", "played", 60 * 24 * 4),
    ("The General", "played", 60 * 24 * 6),
    ("Big Buck Bunny", "played", 60 * 24 * 9),
]
FAVOURITES = ["Tears of Steel", "His Girl Friday", "Bonanza"]
# Episodes watched through (series, season, last episode played), then a partial on the next one.
SERIES_STATE = [("Bonanza", 1, 4, 0.28, 90), ("Sherlock Holmes", 1, 5, None, 60 * 26), ("The Beverly Hillbillies", 1, 2, None, 60 * 24 * 3)]


def call(method, path, body=None, token=None, raw=False):
    auth = f"MediaBrowser {CLIENT}" + (f', Token="{token}"' if token else "")
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Authorization": auth, "Content-Type": "application/json"})
    for attempt in range(60):  # Jellyfin answers 503 until startup finishes
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                out = r.read()
            break
        except urllib.error.HTTPError as e:
            if e.code != 503 or attempt == 59:
                raise
            time.sleep(2)
    return out if raw else (json.loads(out) if out else None)


def wait_up():
    for _ in range(120):
        try:
            return call("GET", "/System/Info/Public")
        except (urllib.error.URLError, ConnectionError):
            time.sleep(1)
    sys.exit("server did not come up")


def wizard(info):
    if info.get("StartupWizardCompleted"):
        return
    call("POST", "/Startup/Configuration", {"ServerName": SERVER_NAME, "UICulture": "en-US",
                                            "MetadataCountryCode": "US", "PreferredMetadataLanguage": "en"})
    call("POST", "/Startup/RemoteAccess", {"EnableRemoteAccess": True})
    call("GET", "/Startup/User")
    call("POST", "/Startup/User", {"Name": USER, "Password": PASSWORD})
    call("POST", "/Startup/Complete")


def login():
    for _ in range(30):
        try:
            r = call("POST", "/Users/AuthenticateByName", {"Username": USER, "Pw": PASSWORD})
            return r["AccessToken"], r["User"]["Id"]
        except urllib.error.HTTPError:
            time.sleep(1)
    sys.exit("could not sign in")


def ensure_libraries(token):
    existing = {f["Name"] for f in call("GET", "/Library/VirtualFolders", token=token)}
    for name, ctype in LIBRARIES:
        if name in existing:
            continue
        # Trickplay only where real video plays; stubs are a flat frame. The media mount is read-only.
        trick = name == "Open Movies"
        opts = {"LibraryOptions": {
            "EnablePhotos": False, "EnableRealtimeMonitor": False, "EnableEmbeddedTitles": False,
            "EnableChapterImageExtraction": False, "ExtractChapterImagesDuringLibraryScan": False,
            "EnableTrickplayImageExtraction": trick, "ExtractTrickplayImagesDuringLibraryScan": trick,
            "SaveTrickplayWithMedia": False, "SaveLocalMetadata": False,
            # TMDB only: the frame grabbers would turn a stub's flat frame into a blank still.
            "TypeOptions": [{"Type": t, "MetadataFetchers": ["TheMovieDb"], "MetadataFetcherOrder": ["TheMovieDb"],
                             "ImageFetchers": ["TheMovieDb"], "ImageFetcherOrder": ["TheMovieDb"]}
                            for t in (("Movie",) if ctype == "movies" else ("Series", "Season", "Episode"))],
        }}
        q = urllib.parse.urlencode({"name": name, "collectionType": ctype, "paths": f"/media/{name}",
                                    "refreshLibrary": "false"})
        call("POST", f"/Library/VirtualFolders?{q}", opts, token=token)
    call("POST", "/Library/Refresh", token=token)


def wait_scan(token, started):
    """Waits for a library scan that finished after `started`; an Idle task alone may not have begun."""
    for _ in range(600):
        task = next(t for t in call("GET", "/ScheduledTasks?isHidden=false", token=token) if t["Key"] == "RefreshLibrary")
        end = (task.get("LastExecutionResult") or {}).get("EndTimeUtc", "")
        if task["State"] == "Idle" and end[:19] >= started:
            return
        time.sleep(3)
    sys.exit("library scan did not finish")


def items(token, uid, kinds):
    q = urllib.parse.urlencode({"userId": uid, "recursive": "true", "includeItemTypes": kinds,
                                "fields": "ProviderIds,ProductionYear", "limit": 1000})
    return call("GET", f"/Items?{q}", token=token)["Items"]


def report(token, uid):
    films = items(token, uid, "Movie,Series")
    missing = [i["Name"] for i in films if not i.get("ProviderIds", {}).get("Tmdb")]
    print(f"{len(films)} movies and series; unmatched: {', '.join(missing) or 'none'}")


def set_data(token, uid, item_id, **fields):
    call("POST", f"/UserItems/{item_id}/UserData?userId={uid}", fields, token=token)


def when(minutes_ago):
    return (datetime.now(timezone.utc) - timedelta(minutes=minutes_ago)).strftime("%Y-%m-%dT%H:%M:%S.0000000Z")


def seed(token, uid):
    by_name = {i["Name"]: i for i in items(token, uid, "Movie,Series")}
    for title, state, ago in MOVIE_STATE:
        item = by_name.get(title)
        if not item:
            print(f"seed: no item named {title!r}"); continue
        full = call("GET", f"/Items/{item['Id']}?userId={uid}", token=token)
        if state == "played":
            set_data(token, uid, item["Id"], Played=True, PlaybackPositionTicks=0, LastPlayedDate=when(ago))
        else:
            set_data(token, uid, item["Id"], Played=False, LastPlayedDate=when(ago),
                     PlaybackPositionTicks=int(full.get("RunTimeTicks", 0) * state))
    for title in FAVOURITES:
        if title in by_name:
            set_data(token, uid, by_name[title]["Id"], IsFavorite=True)
    for show, season, through, partial, ago in SERIES_STATE:
        series = by_name.get(show)
        if not series:
            print(f"seed: no series named {show!r}"); continue
        eps = call("GET", f"/Shows/{series['Id']}/Episodes?userId={uid}&season={season}", token=token)["Items"]
        eps.sort(key=lambda e: e.get("IndexNumber", 0))
        for n, ep in enumerate(eps, 1):
            if n <= through:
                set_data(token, uid, ep["Id"], Played=True, PlaybackPositionTicks=0, LastPlayedDate=when(ago + (through - n) * 60 * 24))
            elif n == through + 1 and partial:
                set_data(token, uid, ep["Id"], Played=False, LastPlayedDate=when(ago),
                         PlaybackPositionTicks=int(ep.get("RunTimeTicks", 0) * partial))


def main():
    info = wait_up()
    wizard(info)
    token, uid = login()
    call("POST", "/System/Configuration", {**call("GET", "/System/Configuration", token=token),
                                           "ServerName": SERVER_NAME}, token=token)
    started = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
    ensure_libraries(token)
    wait_scan(token, started)
    report(token, uid)
    seed(token, uid)


if __name__ == "__main__":
    main()
