import json
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

from server import Fixture, Handler, SyntheticHTTPServer


class SyntheticServerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        media = Path(self.directory.name) / "synthetic.mp4"
        media.write_bytes(bytes(range(256)))
        self.server = SyntheticHTTPServer(("127.0.0.1", 0), Handler)
        self.server.fixture = Fixture(2, 6, media)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"
        self.open = build_opener(ProxyHandler({})).open

    def tearDown(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()
        self.directory.cleanup()

    def get(self, path):
        with self.open(self.base + path, timeout=3) as response:
            return json.load(response)

    def control(self, body):
        request = Request(self.base + "/__control", data=json.dumps(body).encode(),
                          headers={"Content-Type": "application/json"})
        with self.open(request, timeout=3) as response:
            return json.load(response)

    def test_pages_are_disjoint_and_preserve_total(self):
        parent = self.get("/UserViews")["Items"][0]["Id"]
        page1 = self.get(f"/Items?parentId={parent}&limit=2&startIndex=0")
        page2 = self.get(f"/Items?parentId={parent}&limit=2&startIndex=2")
        self.assertEqual([len(page1["Items"]), len(page2["Items"])], [2, 1])
        self.assertEqual(page1["TotalRecordCount"], 3)
        self.assertEqual(page2["TotalRecordCount"], 3)
        rows = page1["Items"] + page2["Items"]
        self.assertEqual(len({r["Id"] for r in rows}), 3)
        self.assertTrue(all(r["UserData"]["Key"] == r["Id"] for r in rows))

    def test_scoped_failure_leaves_control_and_views_available(self):
        self.control({"fail_every": 1, "fail_path": "/items"})
        self.assertEqual(len(self.get("/UserViews")["Items"]), 2)
        with self.assertRaises(HTTPError) as failed:
            self.get("/Items")
        self.assertEqual(failed.exception.code, 503)
        failed.exception.close()
        self.control({"fail_every": 0})
        self.assertEqual(self.get("/Items")["TotalRecordCount"], 6)

    def test_scoped_failure_cadence_ignores_unrelated_traffic(self):
        # Every third /Items request fails, however many other requests land in between.
        self.control({"fail_every": 3, "fail_path": "/items"})
        outcomes = []
        for n in range(6):
            for _ in range(n % 3):
                self.get("/UserViews")
            try:
                self.get("/Items")
                outcomes.append("ok")
            except HTTPError as failed:
                outcomes.append(failed.code)
                failed.close()
        self.assertEqual(outcomes, ["ok", "ok", 503, "ok", "ok", 503])

    def test_video_range_response_preserves_offsets(self):
        request = Request(self.base + "/Videos/synthetic/stream.mp4", headers={"Range": "bytes=64-95"})
        with self.open(request, timeout=3) as response:
            self.assertEqual(response.status, 206)
            self.assertEqual(response.headers["Content-Range"], "bytes 64-95/256")
            self.assertEqual(response.read(), bytes(range(64, 96)))

    def test_unsatisfiable_video_range_is_rejected(self):
        request = Request(self.base + "/Videos/synthetic/stream.mp4", headers={"Range": "bytes=500-"})
        with self.assertRaises(HTTPError) as failed:
            self.open(request, timeout=3)
        self.assertEqual(failed.exception.code, 416)
        failed.exception.close()


if __name__ == "__main__":
    unittest.main()
