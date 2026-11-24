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

        # job markup: a job without the field keeps "use default" (-1); an explicit value round-trips
        by_id = {j["id"]: j for j in everything["jobs"]}
        assert by_id["j1"]["markupPercent"] == -1.0, by_id["j1"]
        post({"since": 0, "jobs": [dict(j1, markupPercent=12.5, updatedAt=6500)]})
        assert {j["id"]: j for j in post({"since": 0})["jobs"]}["j1"]["markupPercent"] == 12.5

        # material with invoice fields round-trips
        m = {"id": "m1", "date": "2026-11-24", "jobId": "j1", "customerId": "c1", "name": "Cable 3G1.5",
             "eNumber": "1234567", "quantity": 100, "unit": "m", "unitPrice": 12.5, "supplier": "Ahlsell", "invoiceRef": "INV-9",
             "source": "invoice", "updatedAt": 7000, "deleted": False}
        post({"since": 0, "materials": [m]})
        got = post({"since": 0})["materials"][0]
        assert got["unitPrice"] == 12.5 and got["source"] == "invoice" and got["jobId"] == "j1"
        assert got["markupPercent"] == -1.0  # no per-line markup unless set
        assert got["eNumber"] == "1234567"
        post({"since": 0, "materials": [dict(m, markupPercent=20.0, updatedAt=7100)]})
        assert post({"since": 0})["materials"][0]["markupPercent"] == 20.0

        # todos: epoch-millis due time and done flag round-trip, completion syncs
        due = 1795600000000
        todo = {"id": "td1", "title": "Order cable", "notes": "3G1.5", "jobId": "j1", "dueAt": due,
                "done": False, "updatedAt": 8000, "deleted": False}
        post({"since": 0, "todos": [todo]})
        got = post({"since": 0})["todos"][0]
        assert got["dueAt"] == due and got["done"] is False and got["title"] == "Order cable"
        post({"since": 0, "todos": [dict(todo, done=True, updatedAt=9000)]})
        assert post({"since": 0})["todos"][0]["done"] is True

        # bad payload
        try:
            post({"since": 0, "trips": [{"id": "x"}]}); raise AssertionError("expected 400")
        except urllib.error.HTTPError as err:
            assert err.code == 400
        # price list: 404 before any import, then search/lookup after a (tiny) import
        def get(path, token=TOKEN):
            req = urllib.request.Request(BASE + path, headers={"Authorization": f"Bearer {token}"})
            return json.load(urllib.request.urlopen(req))
        try:
            get("/catalog/search?q=kabel"); raise AssertionError("expected 404")
        except urllib.error.HTTPError as err:
            assert err.code == 404
        try:
            get("/catalog/search?q=kabel", token="wrong"); raise AssertionError("expected 401")
        except urllib.error.HTTPError as err:
            assert err.code == 401
        sys.path.insert(0, here)
        import catalog
        db = catalog.open_db(os.path.join(tmp, "catalog.db"))
        db.executemany("INSERT INTO catalog VALUES (?,?,?,?,?,?,?,?,?,?,?)", [
            ("E2100012", "MINIBRYTARE S201M-B6UC", "e2100012 minibrytare s201m-b6uc", "pcs", 1, 378.0, 69, 117.18, 0, "2026-11-25", "X"),
            ("E0001200", "EXQJ PURE 2X2,5/2,5", "e0001200 exqj pure 2x2,5/2,5", "m", 1, 15.5, 69.5, 4.73, 1, "2026-11-25", "X"),
        ])
        db.commit()
        items = get("/catalog/search?q=minibrytare%20s201")["items"]
        assert [i["eNumber"] for i in items] == ["E2100012"] and items[0]["net"] == 117.18, items
        assert get("/catalog/search?q=e0001200")["items"][0]["metalSurcharge"] is True
        assert get("/catalog/item?enr=e2100012")["name"] == "MINIBRYTARE S201M-B6UC"
        assert get("/catalog/search?q=%25")["items"] == []  # wildcard characters are literal
        try:
            get("/catalog/item?enr=nope"); raise AssertionError("expected 404")
        except urllib.error.HTTPError as err:
            assert err.code == 404
        # upload page + upload endpoint (build a tiny xlsx by hand)
        assert b"Upload price list" in urllib.request.urlopen(BASE + "/upload").read()
        import zipfile
        xl = os.path.join(tmp, "p.xlsx")
        with zipfile.ZipFile(xl, "w") as z:
            z.writestr("xl/sharedStrings.xml", '<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                + "".join(f"<si><t>{x}</t></si>" for x in ["Artnr", "Artben", "Sortkod", "KN", "E9", "TEST ARTICLE", "ST"]) + "</sst>")
            z.writestr("xl/worksheets/sheet1.xml", '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'
                '<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row>'
                '<row r="2"><c r="A2" t="s"><v>4</v></c><c r="B2" t="s"><v>5</v></c><c r="C2" t="s"><v>6</v></c><c r="D2"><v>1000</v></c></row>'
                '</sheetData></worksheet>')
        data = open(xl, "rb").read()
        up = urllib.request.Request(BASE + "/catalog/upload?supplier=Test", data=data, method="POST",
                                    headers={"Authorization": f"Bearer {TOKEN}"})
        assert json.load(urllib.request.urlopen(up))["imported"] == 1
        assert get("/catalog/item?enr=E9")["net"] == 100.0  # 1000 per 10 units
        bad = urllib.request.Request(BASE + "/catalog/upload", data=data, method="POST", headers={"Authorization": "Bearer nope"})
        try:
            urllib.request.urlopen(bad); raise AssertionError("expected 401")
        except urllib.error.HTTPError as err:
            assert err.code == 401
        print("all server tests passed")
    finally:
        proc.terminate()


if __name__ == "__main__":
    main()
