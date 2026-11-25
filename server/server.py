#!/usr/bin/env python3
"""SparkLog sync server: stdlib only (Python 3.9+), SQLite storage.

Run:  SPARKLOG_TOKEN=some-long-secret python3 server.py
Env:  SPARKLOG_TOKEN (required), SPARKLOG_DB (default sparklog.db),
      SPARKLOG_HOST (default 0.0.0.0), SPARKLOG_PORT (default 8787)
      Email invoice import is switched on by SPARKLOG_IMAP_HOST (see mailimport.py).

POST /sync  (Authorization: Bearer <token>)
  body:     {"since": <server ts>, "customers": [...], "jobs": [...], "todos": [...],
             "work_entries": [...], "trips": [...], "materials": [...]}
  response: {"serverTime": <ts>, same six lists}
Rows from the client are accepted only if their updatedAt is newer than the stored row
(last write wins). The response holds every row whose server timestamp is greater than `since`.
"""
import hmac
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # also under python -I
import catalog  # noqa: E402

MAX_BODY = 5 * 1024 * 1024

# table -> columns by type (every table also has id, updatedAt, deleted, server_ts)
# columns whose "no value" default is not 0 (markupPercent -1 = use the app's default markup)
REAL_DEFAULTS = {"markupPercent": -1.0, "hourlyRate": -1.0, "ratePer10Km": -1.0, "calloutFee": -1.0}

TABLES = {
    "customers": dict(
        texts=["name", "contactPerson", "phone", "email", "address", "postalCode", "city",
               "orgNumber", "propertyId", "notes"],
        reals=[], ints=[], bools=["finished"]),
    "jobs": dict(texts=["customerId", "title", "reference"], reals=["markupPercent", "hourlyRate", "ratePer10Km", "calloutFee"], ints=["number"], bools=["finished"]),
    "notes": dict(texts=["title", "text"], reals=[], ints=[], bools=[]),
    "todos": dict(texts=["title", "notes", "jobId", "finishBy"], reals=[], ints=["dueAt"], bools=["done"]),
    "work_entries": dict(texts=["date", "jobId", "customerId", "description"], reals=["hours", "hourlyRate"], ints=[], bools=["callout"]),
    "trips": dict(texts=["date", "jobId", "customerId", "fromPlace", "toPlace", "purpose", "source"],
                  reals=["km"], ints=[], bools=[]),
    "materials": dict(texts=["date", "jobId", "customerId", "eNumber", "name", "unit", "supplier", "invoiceRef", "source"],
                      reals=["quantity", "unitPrice", "markupPercent"], ints=[], bools=[]),
}

lock = threading.Lock()


def connect(path):
    db = sqlite3.connect(path, check_same_thread=False)
    db.row_factory = sqlite3.Row
    for table, cols in TABLES.items():
        defs = (
            [f'"{c}" TEXT' for c in cols["texts"]]
            + [f'"{c}" REAL' + (f" DEFAULT {REAL_DEFAULTS[c]}" if c in REAL_DEFAULTS else "") for c in cols["reals"]]
            + [f'"{c}" INTEGER NOT NULL DEFAULT 0' for c in cols["ints"] + cols["bools"]]
        )
        db.execute(
            f'CREATE TABLE IF NOT EXISTS {table} (id TEXT PRIMARY KEY, {", ".join(defs)}, '
            f'updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, server_ts INTEGER NOT NULL)'
        )
        # add columns introduced after the table was first created
        have = {r["name"] for r in db.execute(f"PRAGMA table_info({table})")}
        for c in cols["texts"]:
            if c not in have:
                db.execute(f'ALTER TABLE {table} ADD COLUMN "{c}" TEXT')
        for c in cols["reals"]:
            if c not in have:
                db.execute(f'ALTER TABLE {table} ADD COLUMN "{c}" REAL' + (f" DEFAULT {REAL_DEFAULTS[c]}" if c in REAL_DEFAULTS else ""))
        for c in cols["ints"] + cols["bools"]:
            if c not in have:
                db.execute(f'ALTER TABLE {table} ADD COLUMN "{c}" INTEGER NOT NULL DEFAULT 0')
        db.execute(f"CREATE INDEX IF NOT EXISTS idx_{table}_ts ON {table}(server_ts)")
    db.commit()
    return db


def last_ts(db):
    best = 0
    for table in TABLES:
        row = db.execute(f"SELECT COALESCE(MAX(server_ts), 0) AS m FROM {table}").fetchone()
        best = max(best, row["m"])
    return best


def next_job_number(db):
    row = db.execute("SELECT MAX(number) AS m FROM jobs").fetchone()
    return max(row["m"] or 0, 1000) + 1


def apply_rows(db, table, rows, ts):
    """Insert/update rows (last write wins). Job numbers are kept unique: if a pushed job
    collides with another job's number it gets the next free number, which then syncs back."""
    cols = TABLES[table]
    names = ["id"] + cols["texts"] + cols["reals"] + cols["ints"] + cols["bools"] + ["updatedAt", "deleted", "server_ts"]
    sql = (f'INSERT OR REPLACE INTO {table} ({", ".join(chr(34) + c + chr(34) for c in names)}) '
           f'VALUES ({", ".join("?" * len(names))})')
    for r in rows:
        existing = db.execute(f"SELECT updatedAt FROM {table} WHERE id = ?", (r["id"],)).fetchone()
        if existing is not None and int(r["updatedAt"]) <= existing["updatedAt"]:
            continue
        updated_at = int(r["updatedAt"])
        ints = {c: int(r.get(c, 0) or 0) for c in cols["ints"]}
        if table == "jobs":
            clash = db.execute("SELECT id FROM jobs WHERE number = ? AND id != ?", (ints["number"], r["id"])).fetchone()
            if ints["number"] <= 0 or clash is not None:
                ints["number"] = next_job_number(db)
                updated_at = max(updated_at + 1, int(time.time() * 1000))
        values = (
            [str(r["id"])]
            + [str(r.get(c, "") or "") for c in cols["texts"]]
            + [float(r.get(c, REAL_DEFAULTS.get(c, 0)) or 0) for c in cols["reals"]]
            + [ints[c] for c in cols["ints"]]
            + [1 if r.get(c) else 0 for c in cols["bools"]]
            + [updated_at, 1 if r.get("deleted") else 0, ts]
        )
        db.execute(sql, values)


def rows_since(db, table, since):
    cols = TABLES[table]
    out = []
    for row in db.execute(f"SELECT * FROM {table} WHERE server_ts > ? ORDER BY server_ts", (since,)):
        d = {"id": row["id"], "updatedAt": row["updatedAt"], "deleted": bool(row["deleted"])}
        for c in cols["texts"]:
            d[c] = row[c] if row[c] is not None else ""
        for c in cols["reals"] + cols["ints"]:
            d[c] = row[c] if row[c] is not None else REAL_DEFAULTS.get(c, 0)
        for c in cols["bools"]:
            d[c] = bool(row[c])
        out.append(d)
    return out


def next_ts(db):
    """Strictly increasing server timestamp, even across requests that write nothing. Hold `lock`."""
    ts = max(int(time.time() * 1000), Handler.clock + 1, last_ts(db) + 1)
    Handler.clock = ts
    return ts


def store(db, rows_by_table):
    """Write rows from inside the server (e.g. the invoice importer), same rules as /sync."""
    with lock:
        ts = next_ts(db)
        for table, rows in rows_by_table.items():
            apply_rows(db, table, rows, ts)
        db.commit()


MAX_UPLOAD = 300 * 1024 * 1024

# Sync format version. Bump it here AND in the app (SyncWorker.SYNC_API) whenever a synced field is
# added or changed, so an app and server that don't match refuse to sync instead of losing data.
API_VERSION = 3

UPLOAD_PAGE = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<title>SparkLog price list</title>
<body style="font-family:sans-serif;max-width:30em;margin:1em auto;padding:0 1em">
<h2>Upload price list</h2>
<p>Choose the retailer's price file (.xlsx). Importing takes about 20 seconds.</p>
<input id=token placeholder="Token" style="width:100%;padding:.6em;margin:.3em 0"><br>
<input id=supplier placeholder="Retailer name (optional)" style="width:100%;padding:.6em;margin:.3em 0"><br>
<input id=file type=file accept=".xlsx" style="margin:.6em 0"><br>
<button id=go style="padding:.7em 1.4em;font-size:1em">Upload</button>
<p id=out></p>
<script>
document.getElementById('go').onclick = async () => {
  const out = document.getElementById('out'), f = document.getElementById('file').files[0];
  if (!f) { out.textContent = 'Choose a file first.'; return; }
  out.textContent = 'Uploading and importing, please wait...';
  try {
    const r = await fetch('/catalog/upload?supplier=' + encodeURIComponent(document.getElementById('supplier').value),
      {method: 'POST', body: f, headers: {Authorization: 'Bearer ' + document.getElementById('token').value.trim()}});
    const j = await r.json();
    out.textContent = r.ok ? 'Done: ' + j.imported + ' articles imported.' : 'Failed: ' + (j.error || r.status);
  } catch (e) { out.textContent = 'Failed: ' + e; }
};
</script>"""


class Handler(BaseHTTPRequestHandler):
    catalog = None  # price list database, opened on first use once one has been imported
    catalog_path = "catalog.db"
    catalog_lock = threading.Lock()
    db = None
    token = ""
    clock = 0

    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args), flush=True)

    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        header = self.headers.get("Authorization", "")
        supplied = header[7:] if header.startswith("Bearer ") else ""
        return hmac.compare_digest(supplied.encode(), self.token.encode())

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        if url.path == "/health":
            return self._send(200, {"ok": True, "api": API_VERSION})
        if url.path == "/upload":
            body = UPLOAD_PAGE.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return self.wfile.write(body)
        if url.path in ("/catalog/search", "/catalog/item"):
            if not self._authorized():
                return self._send(401, {"error": "unauthorized"})
            qs = urllib.parse.parse_qs(url.query)
            with self.catalog_lock:
                if Handler.catalog is None and os.path.exists(Handler.catalog_path):
                    Handler.catalog = catalog.open_db(Handler.catalog_path)  # imported after startup
                if Handler.catalog is None:
                    return self._send(404, {"error": "no price list imported on the server"})
                if url.path == "/catalog/item":
                    item = catalog.lookup(Handler.catalog, qs.get("enr", [""])[0])
                    return self._send(200 if item else 404, item or {"error": "not found"})
                try:
                    limit = int(qs.get("limit", ["30"])[0])
                except ValueError:
                    limit = 30
                return self._send(200, {"items": catalog.search(Handler.catalog, qs.get("q", [""])[0], limit)})
        self._send(404, {"error": "not found"})

    def _upload_catalog(self, query):
        """Receives the price list .xlsx (raw request body) and imports it. Used by the /upload page."""
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_UPLOAD:
            return self._send(413, {"error": "bad file size"})
        supplier = urllib.parse.parse_qs(query).get("supplier", [""])[0][:80]
        with tempfile.NamedTemporaryFile(suffix=".xlsx", delete=False) as tmp:
            remaining = length
            while remaining > 0:
                chunk = self.rfile.read(min(65536, remaining))
                if not chunk:
                    break
                tmp.write(chunk)
                remaining -= len(chunk)
        try:
            with self.catalog_lock:
                db = catalog.open_db(Handler.catalog_path)
                try:
                    n = catalog.import_xlsx(db, tmp.name, supplier)
                finally:
                    db.close()
            self._send(200, {"imported": n})
        except Exception as e:  # bad file, missing columns, ...
            self._send(400, {"error": f"could not read the file: {e}"})
        finally:
            os.unlink(tmp.name)

    def _reset(self):
        """Development only: back up the database, then delete every synced row (job numbers restart at 1001)."""
        if not self._authorized():
            return self._send(401, {"error": "unauthorized"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length)) if 0 < length <= 1024 else {}
        except ValueError:
            body = {}
        if body.get("confirm") != "RESET":
            return self._send(400, {"error": 'send {"confirm": "RESET"}'})
        with lock:
            backup = f"{Handler.db_path}.bak-{time.strftime('%Y%m%d-%H%M%S')}"
            dst = sqlite3.connect(backup)
            try:
                self.db.backup(dst)
            finally:
                dst.close()
            for table in TABLES:
                self.db.execute(f"DELETE FROM {table}")
            self.db.commit()
        self._send(200, {"ok": True, "backup": os.path.basename(backup)})

    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        if url.path == "/catalog/upload":
            if not self._authorized():
                return self._send(401, {"error": "unauthorized"})
            return self._upload_catalog(url.query)
        if url.path == "/admin/reset":
            return self._reset()
        if self.path != "/sync":
            return self._send(404, {"error": "not found"})
        if not self._authorized():
            return self._send(401, {"error": "unauthorized"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_BODY:
                return self._send(413, {"error": "bad body size"})
            payload = json.loads(self.rfile.read(length))
            since = int(payload.get("since", 0))
            with lock:
                ts = next_ts(self.db)
                for table in TABLES:
                    apply_rows(self.db, table, payload.get(table, []), ts)
                self.db.commit()
                resp = {"serverTime": max(ts, last_ts(self.db)), "api": API_VERSION}
                for table in TABLES:
                    resp[table] = rows_since(self.db, table, since)
            self._send(200, resp)
        except (ValueError, KeyError, TypeError) as e:
            with lock:
                self.db.rollback()
            self._send(400, {"error": f"bad request: {e}"})


def main():
    token = os.environ.get("SPARKLOG_TOKEN", "")
    if len(token) < 16:
        raise SystemExit("Set SPARKLOG_TOKEN to a secret of at least 16 characters")
    Handler.token = token
    Handler.db = connect(os.environ.get("SPARKLOG_DB", "sparklog.db"))
    Handler.db_path = os.environ.get("SPARKLOG_DB", "sparklog.db")
    Handler.clock = last_ts(Handler.db)
    db_path = os.environ.get("SPARKLOG_DB", "sparklog.db")
    cat_path = os.environ.get("SPARKLOG_CATALOG", os.path.join(os.path.dirname(os.path.abspath(db_path)), "catalog.db"))
    Handler.catalog_path = cat_path
    if os.environ.get("SPARKLOG_IMAP_HOST"):
        import mailimport  # optional: only needed for email invoice import
        mailimport.start_background(db_path, lambda rows: store(Handler.db, rows))
        print("Invoice import from mailbox enabled", flush=True)
    host = os.environ.get("SPARKLOG_HOST", "0.0.0.0")
    port = int(os.environ.get("SPARKLOG_PORT", "8787"))
    print(f"SparkLog server listening on {host}:{port}", flush=True)
    ThreadingHTTPServer((host, port), Handler).serve_forever()


if __name__ == "__main__":
    main()
