#!/usr/bin/env python3
"""Minimal fake Jellyseerr for emulator testing of Jellybeam's Discover screens.

Serves the /api/v1 subset core/seerr-api uses with synthetic data (all values are
placeholders; poster/profile paths are null so no TMDB image traffic happens).
"""
import json
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 5055
USER = {"id": 1, "username": "jellybeam-test", "email": "jellybeam-test@example.test"}


def tv(i, seed=""):
    return {"id": i, "mediaType": "tv", "name": f"Synthetic Series {seed}{i}", "overview": "Synthetic overview.",
            "posterPath": None, "backdropPath": None, "firstAirDate": f"20{10 + i % 15:02d}-01-01", "mediaInfo": None}


def movie(i, seed=""):
    return {"id": i, "mediaType": "movie", "title": f"Synthetic Movie {seed}{i}", "overview": "Synthetic overview.",
            "posterPath": None, "backdropPath": None, "releaseDate": f"20{10 + i % 15:02d}-01-01", "mediaInfo": None}


def page(items, page_no, total_pages=5):
    return {"page": page_no, "totalPages": total_pages, "totalResults": total_pages * len(items), "results": items}


def cast(n, base):
    return [{"id": base + i, "name": f"Synthetic Person {base + i}", "character": "Self" if i else "Host",
             "profilePath": None} for i in range(n)]


def tv_details(i):
    return {**tv(i), "episodeRunTime": [45], "genres": [{"id": 1, "name": "Reality"}],
            "seasons": [{"seasonNumber": s, "name": f"Season {s}", "episodeCount": 10, "status": 1} for s in range(1, 12)],
            "relatedVideos": [], "credits": {"cast": cast(10, 3000), "crew": []}}


def movie_details(i):
    return {**movie(i), "runtime": 118.0, "genres": [{"id": 2, "name": "Drama"}], "relatedVideos": [],
            "credits": {"cast": cast(10, 4000), "crew": []}}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.command, self.path))

    def send_json(self, obj, status=200, cookie=False):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        if cookie:
            self.send_header("Set-Cookie", "connect.sid=fake; Path=/; HttpOnly")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        p = urlparse(self.path).path
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        if p in ("/api/v1/auth/local", "/api/v1/auth/jellyfin"):
            return self.send_json(USER, cookie=True)
        if p == "/api/v1/request":
            return self.send_json({"id": 77, "status": 1, "is4k": False, "seasons": [], "type": "tv"}, status=201)
        return self.send_json({"message": "not found"}, 404)

    def do_DELETE(self):
        self.send_response(204)
        self.end_headers()

    def do_PUT(self):
        return self.do_POST()

    def do_GET(self):
        u = urlparse(self.path)
        p = u.path
        q = parse_qs(u.query)
        page_no = int(q.get("page", ["1"])[0])
        if p == "/api/v1/auth/me":
            return self.send_json(USER)
        if p == "/api/v1/settings/public":
            return self.send_json({"movie4kEnabled": False, "series4kEnabled": False, "cacheImages": False,
                                   "partialRequestsEnabled": True, "applicationTitle": "Fake Seerr"})
        if p == "/api/v1/discover/trending":
            items = [tv(2000 + page_no * 100 + i) if i % 2 else movie(1000 + page_no * 100 + i) for i in range(20)]
            return self.send_json(page(items, page_no))
        if p in ("/api/v1/discover/tv", "/api/v1/discover/tv/upcoming"):
            base = 2000 if p.endswith("/tv") else 2500
            return self.send_json(page([tv(base + page_no * 100 + i) for i in range(20)], page_no))
        if p in ("/api/v1/discover/movies", "/api/v1/discover/movies/upcoming"):
            base = 1000 if p.endswith("/movies") else 1500
            return self.send_json(page([movie(base + page_no * 100 + i) for i in range(20)], page_no))
        if p == "/api/v1/search":
            return self.send_json(page([tv(2900 + i) for i in range(8)], 1, 1))
        if p in ("/api/v1/genres/movie", "/api/v1/genres/tv"):
            return self.send_json([{"id": 1, "name": "Reality"}, {"id": 2, "name": "Drama"}])
        if p == "/api/v1/request":
            return self.send_json({"pageInfo": {"page": 1, "pages": 1, "results": 0}, "results": []})
        if p in ("/api/v1/service/radarr", "/api/v1/service/sonarr"):
            return self.send_json([])
        m = re.fullmatch(r"/api/v1/(tv|movie)/(\d+)(?:/(similar|recommendations|ratings))?", p)
        if m:
            kind, i, sub = m.group(1), int(m.group(2)), m.group(3)
            if sub == "ratings":
                return self.send_json({"message": "no ratings"}, 404)
            if sub:
                base = 6000 if sub == "similar" else 7000
                make = tv if kind == "tv" else movie
                return self.send_json(page([make(base + j) for j in range(8)], 1, 1))
            return self.send_json(tv_details(i) if kind == "tv" else movie_details(i))
        m = re.fullmatch(r"/api/v1/person/(\d+)(?:/(combined_credits))?", p)
        if m:
            i = int(m.group(1))
            if m.group(2):
                return self.send_json({"cast": [tv(2000 + j) for j in range(6)], "crew": [movie(1000 + j) for j in range(4)]})
            return self.send_json({"id": i, "name": f"Synthetic Person {i}", "profilePath": None, "biography": None})
        return self.send_json({"message": "not found"}, 404)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
