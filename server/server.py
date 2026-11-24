#!/usr/bin/env python3
"""SparkLog sync server: stdlib only (Python 3.9+), SQLite storage.

Run:  SPARKLOG_TOKEN=some-long-secret python3 server.py
Env:  SPARKLOG_TOKEN (required), SPARKLOG_DB (default sparklog.db),
      SPARKLOG_HOST (default 0.0.0.0), SPARKLOG_PORT (default 8787)

POST /sync  (Authorization: Bearer <token>)
  body:     {"since": <server ts>, "customers": [...], "work_entries": [...], "trips": [...], "materials": [...]}
  response: {"serverTime": <ts>, same four lists}
Rows from the client are accepted only if their updatedAt is newer than the stored row
(last write wins). The response holds every row whose server timestamp is greater than `since`.
"""
import hmac
import json
import os
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 5 * 1024 * 1024

# table -> (text columns, real columns, boolean columns)
TABLES = {
    "customers": (["name", "contactPerson", "phone", "email", "address", "postalCode", "city",
                   "orgNumber", "propertyId", "notes"], [], ["finished"]),
    "work_entries": (["date", "customerId", "description"], ["hours"], []),
    "trips": (["date", "customerId", "fromPlace", "toPlace", "purpose", "source"], ["km"], []),
    "materials": (["date", "customerId", "name", "unit"], ["quantity"], []),
}

lock = threading.Lock()


def connect(path):
    db = sqlite3.connect(path, check_same_thread=False)
    db.row_factory = sqlite3.Row
    for table, (texts, reals, bools) in TABLES.items():
        cols = ", ".join([f'"{c}" TEXT' for c in texts] + [f'"{c}" REAL' for c in reals]
                         + [f'"{c}" INTEGER NOT NULL DEFAULT 0' for c in bools])
        db.execute(
            f'CREATE TABLE IF NOT EXISTS {table} (id TEXT PRIMARY KEY, {cols}, '
            f'updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, server_ts INTEGER NOT NULL)'
        )
        # add columns introduced after the table was first created
        have = {r["name"] for r in db.execute(f"PRAGMA table_info({table})")}
        for c in texts:
            if c not in have:
                db.execute(f'ALTER TABLE {table} ADD COLUMN "{c}" TEXT')
        for c in reals:
            if c not in have:
                db.execute(f'ALTER TABLE {table} ADD COLUMN "{c}" REAL')
        for c in bools:
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


def apply_rows(db, table, rows, ts):
    texts, reals, bools = TABLES[table]
    cols = ["id"] + texts + reals + bools + ["updatedAt", "deleted", "server_ts"]
    sql = f'INSERT OR REPLACE INTO {table} ({", ".join(chr(34) + c + chr(34) for c in cols)}) VALUES ({", ".join("?" * len(cols))})'
    for r in rows:
        existing = db.execute(f"SELECT updatedAt FROM {table} WHERE id = ?", (r["id"],)).fetchone()
        if existing is not None and int(r["updatedAt"]) <= existing["updatedAt"]:
            continue
        values = (
            [str(r["id"])]
            + [str(r.get(c, "")) for c in texts]
            + [float(r.get(c, 0) or 0) for c in reals]
            + [1 if r.get(c) else 0 for c in bools]
            + [int(r["updatedAt"]), 1 if r.get("deleted") else 0, ts]
        )
        db.execute(sql, values)


def rows_since(db, table, since):
    texts, reals, bools = TABLES[table]
    out = []
    for row in db.execute(f"SELECT * FROM {table} WHERE server_ts > ? ORDER BY server_ts", (since,)):
        d = {"id": row["id"], "updatedAt": row["updatedAt"], "deleted": bool(row["deleted"])}
        for c in texts + reals:
            d[c] = row[c]
        for c in bools:
            d[c] = bool(row[c])
        out.append(d)
    return out


class Handler(BaseHTTPRequestHandler):
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
        if self.path == "/health":
            self._send(200, {"ok": True})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
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
                # strictly increasing, even across requests that write nothing
                ts = max(int(time.time() * 1000), Handler.clock + 1, last_ts(self.db) + 1)
                Handler.clock = ts
                for table in TABLES:
                    apply_rows(self.db, table, payload.get(table, []), ts)
                self.db.commit()
                resp = {"serverTime": max(ts, last_ts(self.db))}
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
    host = os.environ.get("SPARKLOG_HOST", "0.0.0.0")
    port = int(os.environ.get("SPARKLOG_PORT", "8787"))
    print(f"SparkLog server listening on {host}:{port}", flush=True)
    ThreadingHTTPServer((host, port), Handler).serve_forever()


if __name__ == "__main__":
    main()
