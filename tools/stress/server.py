#!/usr/bin/env python3
"""Synthetic Jellyfin 12 subset for browse/fault stress tests, not API conformance."""

import argparse
import json
import re
import threading
import time
import uuid
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from socketserver import TCPServer
from urllib.parse import parse_qs, urlsplit


def synthetic_id(value):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "https://example.test/stress/" + value))


USER = {"Id": synthetic_id("user"), "Name": "Synthetic User",
        "Policy": {"IsAdministrator": False, "EnableAllFolders": True}}


class SyntheticHTTPServer(ThreadingHTTPServer):
    def server_bind(self):
        # Fixtures must not perform host-name discovery or depend on reverse DNS.
        TCPServer.server_bind(self)
        self.server_name = "example.test"
        self.server_port = self.server_address[1]


class Fixture:
    def __init__(self, libraries, items, media=None):
        self.lock = threading.Lock()
        self.delay_ms = 0
        self.fail_every = 0
        self.fail_path = ""
        # Requests that matched fail_path (all of them when it is empty) since the last
        # control change: fail_every counts these alone, so unrelated traffic never shifts
        # which matching request fails.
        self.eligible_requests = 0
        self.broken_art = False
        self.requests = Counter()
        self.media = media
        self.views = [{"Id": synthetic_id(f"library-{n}"), "Name": f"Synthetic Library {n:02}",
                       "Type": "CollectionFolder", "CollectionType": "movies"}
                      for n in range(libraries)]
        self.items = []
        for n in range(items):
            self.items.append({"Id": synthetic_id(f"movie-{n}"), "Name": f"Synthetic Movie {n:06}",
                               "SortName": f"Synthetic Movie {n:06}", "Type": "Movie",
                               "ParentId": self.views[n % libraries]["Id"], "IsFolder": False,
                               "Overview": "Synthetic metadata for repeatable navigation testing. " * 12,
                               "Genres": [f"Genre {n % 12}"], "ProductionYear": 2000 + n % 25,
                               "DateCreated": "2026-01-01T00:00:00Z", "LocationType": "FileSystem",
                               "RunTimeTicks": 72000000000, "ImageTags": {},
                               "UserData": {"Key": synthetic_id(f"movie-{n}"), "Played": False, "PlayCount": 0,
                                            "PlaybackPositionTicks": 0, "IsFavorite": False}})
        self.by_id = {item["Id"].replace("-", ""): item for item in self.items + self.views}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def send_json(self, value, status=200):
        data = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):
        self.do_GET()

    def send_media(self, path):
        size = path.stat().st_size
        start, end = 0, size - 1
        requested = self.headers.get("Range")
        if requested:
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", requested)
            if not match:
                self.send_json({}, 416)
                return
            start = int(match[1])
            end = min(end, int(match[2])) if match[2] else end
            if start > end:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.end_headers()
                return
        self.send_response(206 if requested else 200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if requested:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with path.open("rb") as media:
            media.seek(start)
            remaining = end - start + 1
            try:
                while remaining:
                    chunk = media.read(min(65536, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def do_GET(self):
        fixture = self.server.fixture
        url = urlsplit(self.path)
        path = url.path.lower()
        query = {k.lower(): v[0] for k, v in parse_qs(url.query).items()}
        body = {}
        length = int(self.headers.get("Content-Length", 0))
        if length:
            if length > 1024 * 1024:
                self.send_json({}, 413)
                return
            try:
                body = json.loads(self.rfile.read(length))
            except ValueError:
                self.send_json({}, 400)
                return
        if path == "/__control":
            with fixture.lock:
                if self.command == "POST":
                    fixture.delay_ms = min(30000, max(0, int(body.get("delay_ms", 0))))
                    fixture.fail_every = max(0, int(body.get("fail_every", 0)))
                    fixture.fail_path = str(body.get("fail_path", "")).lower()
                    fixture.eligible_requests = 0
                    fixture.broken_art = bool(body.get("broken_art", fixture.broken_art))
                result = {"delay_ms": fixture.delay_ms, "fail_every": fixture.fail_every,
                          "fail_path": fixture.fail_path,
                          "broken_art": fixture.broken_art,
                          "requests": dict(fixture.requests), "items": len(fixture.items)}
            self.send_json(result)
            return
        # Count route families only: never retain auth headers or request bodies.
        family = "/" + "/".join(segment if not len(segment) >= 32 else "item"
                                 for segment in path.strip("/").split("/")[:3])
        with fixture.lock:
            fixture.requests[family] += 1
            delay = fixture.delay_ms
            eligible = not fixture.fail_path or path.startswith(fixture.fail_path)
            if eligible:
                fixture.eligible_requests += 1
            fail = bool(fixture.fail_every and eligible
                        and fixture.eligible_requests % fixture.fail_every == 0)
        time.sleep(delay / 1000)
        if fail:
            self.send_json({"Message": "Synthetic injected outage"}, 503)
        elif path == "/system/info/public":
            self.send_json({"Id": synthetic_id("server"), "ServerName": "Synthetic Stress Server",
                            "Version": "12.0.0", "StartupWizardCompleted": True})
        elif path == "/users/authenticatebyname":
            self.send_json({"User": USER, "AccessToken": "synthetic-test-token",
                            "ServerId": synthetic_id("server")})
        elif path == "/userviews":
            self.send_json({"Items": fixture.views, "TotalRecordCount": len(fixture.views)})
        elif path == "/items":
            rows = fixture.items
            if query.get("parentid"):
                parent = query["parentid"].replace("-", "")
                rows = [r for r in rows if r["ParentId"].replace("-", "") == parent]
            if query.get("ids"):
                ids = {i.replace("-", "") for i in query["ids"].split(",")}
                rows = [fixture.by_id[i] for i in ids if i in fixture.by_id]
            if query.get("mindatelastsaved"):
                rows = []
            start = max(0, int(query.get("startindex", 0)))
            limit = min(100000, max(0, int(query.get("limit", 100))))
            page = rows[start:start + limit]
            if fixture.broken_art:
                page = [row | {"ImageTags": {"Primary": "synthetic"}} for row in page]
            self.send_json({"Items": page, "TotalRecordCount": len(rows)})
        elif path.endswith("/playbackinfo") and fixture.media:
            self.send_json({"PlaySessionId": synthetic_id("play-session"), "MediaSources": [{
                "Id": synthetic_id("source"), "Container": "mp4", "Protocol": "Http",
                "Name": "Synthetic H264 AAC", "RunTimeTicks": 600000000, "Size": fixture.media.stat().st_size,
                "SupportsDirectPlay": True, "SupportsDirectStream": True, "SupportsTranscoding": False,
                "DefaultAudioStreamIndex": 1, "MediaStreams": [
                    {"Index": 0, "Type": "Video", "Codec": "h264", "Width": 1280, "Height": 720,
                     "RealFrameRate": 30, "AverageFrameRate": 30, "BitDepth": 8, "Profile": "Constrained Baseline"},
                    {"Index": 1, "Type": "Audio", "Codec": "aac", "Channels": 1,
                     "SampleRate": 48000, "IsDefault": True}]}]})
        elif path.startswith("/videos/") and "/stream" in path and fixture.media:
            self.send_media(fixture.media)
        elif path.startswith("/items/") and path.split("/")[-1].replace("-", "") in fixture.by_id:
            self.send_json(fixture.by_id[path.split("/")[-1].replace("-", "")])
        elif path.startswith("/users/") and path.count("/") == 2:
            self.send_json(USER)
        elif path in {"/useritems/resume", "/shows/nextup"}:
            self.send_json({"Items": [], "TotalRecordCount": 0})
        elif path.endswith("/latest"):
            self.send_json(fixture.items[:24])
        elif path.startswith("/sessions") or path.startswith("/displaypreferences"):
            self.send_json({})
        elif path == "/quickconnect/enabled":
            self.send_json(False)
        else:
            self.send_json({"Message": "Unsupported synthetic endpoint"}, 404)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18096)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--items", type=int, default=10000)
    parser.add_argument("--libraries", type=int, default=8)
    parser.add_argument("--media", type=Path)
    args = parser.parse_args()
    if not 1 <= args.libraries <= 100 or not 1 <= args.items <= 100000:
        parser.error("Use 1–100 libraries and 1–100000 items.")
    server = SyntheticHTTPServer((args.bind, args.port), Handler)
    if args.media and not args.media.is_file():
        parser.error("The synthetic media fixture does not exist.")
    server.fixture = Fixture(args.libraries, args.items, args.media)
    print(json.dumps({"ready": True, "items": args.items, "libraries": args.libraries}), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
