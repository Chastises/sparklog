"""Black-box test: starts the server on a temp DB and simulates two phones syncing."""
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.error

PORT = 18787
TOKEN = "test-token-1234567890"
BASE = f"http://127.0.0.1:{PORT}"


def post(body, token=TOKEN):
    req = urllib.request.Request(
        BASE + "/sync", data=json.dumps(body).encode(),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def main():
    tmp = tempfile.mkdtemp()
    env = dict(os.environ, SPARKLOG_TOKEN=TOKEN, SPARKLOG_DB=os.path.join(tmp, "t.db"),
               SPARKLOG_PORT=str(PORT), SPARKLOG_HOST="127.0.0.1")
    here = os.path.dirname(os.path.abspath(__file__))
    proc = subprocess.Popen([sys.executable, "-I", os.path.join(here, "server.py")], env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(50):
            try:
                urllib.request.urlopen(BASE + "/health"); break
            except Exception:
                time.sleep(0.1)

        # auth
        try:
            post({}, token="wrong"); raise AssertionError("expected 401")
        except urllib.error.HTTPError as e:
            assert e.code == 401

        # phone A pushes a work entry and a trip
        w = {"id": "w1", "date": "2026-11-24", "customerId": "c1", "description": "Fuse box",
             "hours": 7.5, "updatedAt": 1000, "deleted": False}
        t = {"id": "t1", "date": "2026-11-24", "fromPlace": "Home", "toPlace": "Site", "km": 12.4,
             "purpose": "Andersson", "source": "gps", "updatedAt": 1000, "deleted": False}
        c1 = {"id": "c1", "name": "Andersson AB", "contactPerson": "Anna", "phone": "070-1", "email": "a@x.se",
              "address": "Storgatan 1", "postalCode": "111 22", "city": "Stockholm", "orgNumber": "556000-0000",
              "propertyId": "Sthlm 1:1", "notes": "", "finished": False, "updatedAt": 1000, "deleted": False}
        a = post({"since": 0, "customers": [c1], "work_entries": [w], "trips": [t], "materials": []})
        assert a["serverTime"] > 0

        # phone B (fresh) pulls everything
        b = post({"since": 0})
        assert [r["id"] for r in b["work_entries"]] == ["w1"]
        assert b["customers"][0]["city"] == "Stockholm" and b["work_entries"][0]["customerId"] == "c1"
        assert b["trips"][0]["km"] == 12.4 and b["work_entries"][0]["hours"] == 7.5

        # B edits the entry (newer), A pushes a stale edit: stale must lose
        w_new = dict(w, hours=8.0, updatedAt=3000)
        post({"since": b["serverTime"], "work_entries": [w_new]})
        stale = dict(w, hours=1.0, updatedAt=2000)
        a2 = post({"since": a["serverTime"], "work_entries": [stale]})
        assert a2["work_entries"][0]["hours"] == 8.0, a2

        # delete propagates
        d = dict(w_new, deleted=True, updatedAt=4000)
        post({"since": 0, "work_entries": [d]})
        c = post({"since": a2["serverTime"]})
        assert c["work_entries"][0]["deleted"] is True

        # marking a customer finished syncs ("finished" is stored via the generic columns? check)
        post({"since": 0, "customers": [dict(c1, finished=True, updatedAt=5000)]})
        f = post({"since": c["serverTime"]})
        assert f["customers"][0]["finished"] is True and f["customers"][0]["updatedAt"] == 5000

        # nothing new: empty pull
        e = post({"since": f["serverTime"]})
        assert e["customers"] == [] and e["work_entries"] == [] and e["trips"] == [] and e["materials"] == []

        # jobs: two phones both create job 1001 -> second one is renumbered, no duplicates
        j1 = {"id": "j1", "number": 1001, "customerId": "c1", "title": "Fuse box", "finished": False,
              "updatedAt": 6000, "deleted": False}
        j2 = {"id": "j2", "number": 1001, "customerId": "c1", "title": "Kitchen", "finished": False,
              "updatedAt": 6001, "deleted": False}
        r1 = post({"since": e["serverTime"], "jobs": [j1]})
        r2 = post({"since": r1["serverTime"], "jobs": [j2]})
        nums = {j["id"]: j["number"] for j in r2["jobs"]}
        assert nums["j2"] == 1002, nums
        assert r2["jobs"][0]["updatedAt"] > 6001  # bumped, so the phone accepts the corrected row
        everything = post({"since": 0})
        numbers = [j["number"] for j in everything["jobs"]]
        assert sorted(numbers) == [1001, 1002], numbers

        # material with invoice fields round-trips
        m = {"id": "m1", "date": "2026-11-24", "jobId": "j1", "customerId": "c1", "name": "Cable 3G1.5",
             "quantity": 100, "unit": "m", "unitPrice": 12.5, "supplier": "Ahlsell", "invoiceRef": "INV-9",
             "source": "invoice", "updatedAt": 7000, "deleted": False}
        post({"since": 0, "materials": [m]})
        got = post({"since": 0})["materials"][0]
        assert got["unitPrice"] == 12.5 and got["source"] == "invoice" and got["jobId"] == "j1"

        # bad payload
        try:
            post({"since": 0, "trips": [{"id": "x"}]}); raise AssertionError("expected 400")
        except urllib.error.HTTPError as err:
            assert err.code == 400
        print("all server tests passed")
    finally:
        proc.terminate()


if __name__ == "__main__":
    main()
