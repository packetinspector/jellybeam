#!/usr/bin/env python3
"""Stateful fake Seerr: fake_seerr.py plus requests that persist, show up in mediaInfo and GET /request,
and a stdout log of every request mutation with its body (to check what the app sends).
Usage: python3 stateful_seerr.py [port]; SEED=1 starts with one pending movie request."""
import json, os, re, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fake_seerr as f
from urllib.parse import urlparse, parse_qs
from http.server import ThreadingHTTPServer

REQS = {}   # id -> request dict
NEXT = [100]
def log(s):
    print(f"{time.strftime('%H:%M:%S')} {s}", flush=True)

def reqs_for(kind, tmdb):
    return [r for r in REQS.values() if r["type"] == kind and r["media"]["tmdbId"] == tmdb]

def media_info(kind, tmdb):
    rs = reqs_for(kind, tmdb)
    if not rs:
        return None
    seasons = []
    for r in rs:
        seasons += [{"seasonNumber": s["seasonNumber"], "status": 2} for s in r["seasons"]]
    return {"tmdbId": tmdb, "status": 2, "seasons": seasons, "requests": [dict(r) for r in rs]}

class H(f.Handler):
    def log_message(self, fmt, *a):
        pass

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    def do_POST(self):
        p = urlparse(self.path).path
        if p == "/api/v1/request":
            b = self._body()
            log(f"POST /request body={json.dumps(b)}")
            time.sleep(0.8)
            i = NEXT[0]; NEXT[0] += 1
            kind = b["mediaType"]; tmdb = b["mediaId"]
            r = {"id": i, "status": 1, "is4k": False, "type": kind,
                 "seasons": [{"seasonNumber": s, "status": 1} for s in b.get("seasons", [])],
                 "requestedBy": {"id": 1, "username": "jellybeam-test"},
                 "media": {"tmdbId": tmdb, "status": 2}}
            REQS[i] = r
            return self.send_json(r, 201)
        return super().do_POST()

    def do_PUT(self):
        m = re.fullmatch(r"/api/v1/request/(\d+)", urlparse(self.path).path)
        if m:
            b = self._body()
            i = int(m.group(1))
            log(f"PUT /request/{i} body={json.dumps(b)}")
            if i not in REQS:
                return self.send_json({"message": "nf"}, 404)
            r = REQS[i]
            if "seasons" in b:
                r["seasons"] = [{"seasonNumber": s, "status": 1} for s in b["seasons"]]
            return self.send_json(r)
        return self.send_json({"message": "not found"}, 404)

    def do_DELETE(self):
        m = re.fullmatch(r"/api/v1/request/(\d+)", urlparse(self.path).path)
        if m:
            i = int(m.group(1))
            log(f"DELETE /request/{i}")
            REQS.pop(i, None)
        self.send_response(204); self.end_headers()

    def _get(self):
        u = urlparse(self.path); p = u.path
        if p == "/api/v1/request":
            rs = []
            for r in REQS.values():
                x = dict(r)
                x["media"] = {"tmdbId": r["media"]["tmdbId"], "status": 2, "mediaType": r["type"]}
                rs.append(x)
            return self.send_json({"pageInfo": {"page": 1, "pages": 1, "results": len(rs)}, "results": rs})
        m = re.fullmatch(r"/api/v1/(tv|movie)/(\d+)", p)
        if m:
            kind, i = m.group(1), int(m.group(2))
            d = (f.tv_details if kind == "tv" else f.movie_details)(i)
            mi = media_info(kind, i)
            if mi:
                d["mediaInfo"] = mi
            return self.send_json(d)
        return super().do_GET()

    def send_json(self, obj, status=200, cookie=False):
        def patch(o):
            if isinstance(o, dict):
                if "mediaType" in o and "id" in o and o.get("mediaType") in ("movie","tv") and "mediaInfo" in o and "credits" not in o and "overview" in o:
                    mi = media_info(o["mediaType"], o["id"])
                    if mi: o["mediaInfo"] = mi
                for v in o.values(): patch(v)
            elif isinstance(o, list):
                for v in o: patch(v)
        if self.command == "GET" and "/api/v1/request" not in self.path:
            patch(obj)
        return super().send_json(obj, status, cookie)

    def do_GET(self, _orig=None):
        u = urlparse(self.path)
        if u.path != "/api/v1/auth/me" and u.path != "/api/v1/settings/public":
            log("GET " + self.path[:90])
        return self._get()

if os.environ.get('SEED'):
    REQS[100]={'id':100,'status':1,'is4k':False,'type':'movie','seasons':[],'requestedBy':{'id':1,'username':'jellybeam-test'},'media':{'tmdbId':1100,'status':2}}; NEXT[0]=101
ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1]) if len(sys.argv) > 1 else 5055), H).serve_forever()
