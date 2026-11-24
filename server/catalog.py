"""Retailer price list (catalog), kept in its own SQLite file next to the sync database.

Import (stdlib only, streams the .xlsx so 200k rows is fine):
    python3 catalog.py import Prisfil.xlsx [--supplier "Retailer name"] [--db catalog.db] [--price-per 1]
Look something up:
    python3 catalog.py search "minibrytare s201"

Columns used from the retailer's file: Artnr (the E-number), Artben, Sortkod, Antsort, Stdfp,
GNP (gross), Rabatt (discount %), KN (net, your price), Försk ('M' = copper surcharge not
included), GDatum (valid from).

PRICE_PER: the KN price in the file is per 1 sort unit (confirmed against real items; dividing by
10 gave a tenth of the real price). Change PRICE_PER (or pass --price-per) if an invoice shows
otherwise, then re-import.
"""
import re
import sqlite3
import sys
import zipfile
import xml.etree.ElementTree as ET

PRICE_PER = 1.0
NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
UNITS = {"ST": "pcs", "M": "m", "PR": "pair", "KG": "kg", "CM": "cm", "V": "pcs", "G": "g", "FP": "pack"}

SCHEMA = """
CREATE TABLE IF NOT EXISTS catalog (
  enr TEXT PRIMARY KEY, name TEXT NOT NULL, search TEXT NOT NULL, unit TEXT NOT NULL,
  per_pack REAL NOT NULL DEFAULT 1, gross REAL NOT NULL DEFAULT 0, discount REAL NOT NULL DEFAULT 0,
  net REAL NOT NULL DEFAULT 0, metal INTEGER NOT NULL DEFAULT 0, valid TEXT NOT NULL DEFAULT '',
  supplier TEXT NOT NULL DEFAULT '');
CREATE INDEX IF NOT EXISTS catalog_name ON catalog(search);
"""


def open_db(path):
    db = sqlite3.connect(path, check_same_thread=False)
    db.executescript(SCHEMA)
    return db


def _col_index(ref):
    n = 0
    for ch in re.match(r"[A-Z]+", ref).group(0):
        n = n * 26 + ord(ch) - 64
    return n - 1


def read_xlsx_rows(path):
    """Yield each row of the first sheet as a list of strings (stdlib only, streaming)."""
    with zipfile.ZipFile(path) as z:
        shared = []
        if "xl/sharedStrings.xml" in z.namelist():
            with z.open("xl/sharedStrings.xml") as f:
                for _, el in ET.iterparse(f):
                    if el.tag == NS + "si":
                        shared.append("".join(t.text or "" for t in el.iter(NS + "t")))
                        el.clear()
        sheet = sorted(n for n in z.namelist() if n.startswith("xl/worksheets/sheet"))[0]
        with z.open(sheet) as f:
            for _, el in ET.iterparse(f):
                if el.tag != NS + "row":
                    continue
                row = []
                for c in el.findall(NS + "c"):
                    i = _col_index(c.get("r"))
                    while len(row) < i:
                        row.append("")
                    t = c.get("t")
                    v = c.find(NS + "v")
                    if t == "inlineStr":
                        val = "".join(x.text or "" for x in c.iter(NS + "t"))
                    elif v is None or v.text is None:
                        val = ""
                    elif t == "s":
                        val = shared[int(v.text)]
                    else:
                        val = v.text
                    row.append(val)
                yield row
                el.clear()


def _num(s):
    try:
        return float(str(s).replace(",", ".")) if str(s).strip() else 0.0
    except ValueError:
        return 0.0


def import_xlsx(db, path, supplier=""):
    rows = read_xlsx_rows(path)
    header = [h.strip() for h in next(rows)]
    ix = {h: i for i, h in enumerate(header)}
    for need in ("Artnr", "Artben", "Sortkod", "KN"):
        if need not in ix:
            raise SystemExit(f"Column '{need}' not found. Columns in file: {header}")

    def get(r, name):
        i = ix.get(name)
        return r[i].strip() if i is not None and i < len(r) else ""

    batch, n = [], 0
    db.execute("DELETE FROM catalog")
    for r in rows:
        enr = get(r, "Artnr")
        name = get(r, "Artben")
        if not enr or not name:
            continue
        sort = get(r, "Sortkod").upper()
        net = _num(get(r, "KN")) / PRICE_PER
        gross = _num(get(r, "GNP")) / PRICE_PER
        pack = _num(get(r, "Antsort")) or 1
        v = get(r, "GDatum")
        valid = f"{v[:4]}-{v[4:6]}-{v[6:8]}" if re.fullmatch(r"\d{8}", v) else ""
        batch.append((enr, name, (enr + " " + name).lower(), UNITS.get(sort, sort.lower() or "pcs"), pack,
                      round(gross, 2), _num(get(r, "Rabatt")), round(net, 2),
                      1 if get(r, "Försk").upper() == "M" else 0, valid, supplier))
        n += 1
        if len(batch) >= 5000:
            db.executemany("INSERT OR REPLACE INTO catalog VALUES (?,?,?,?,?,?,?,?,?,?,?)", batch)
            batch = []
    if batch:
        db.executemany("INSERT OR REPLACE INTO catalog VALUES (?,?,?,?,?,?,?,?,?,?,?)", batch)
    db.commit()
    return n


def _row(r):
    return {"eNumber": r[0], "name": r[1], "unit": r[2], "perPack": r[3], "gross": r[4], "discount": r[5],
            "net": r[6], "metalSurcharge": bool(r[7]), "valid": r[8], "supplier": r[9]}

COLS = "enr, name, unit, per_pack, gross, discount, net, metal, valid, supplier"


def search(db, query, limit=30):
    """All words must appear in the E-number or the name. An exact E-number is returned first."""
    words = [w for w in re.split(r"\s+", query.lower().strip()) if w][:6]
    if not words:
        return []
    where = " AND ".join("search LIKE ? ESCAPE '\\'" for _ in words)
    args = ["%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" for w in words]
    exact = query.strip().upper()
    rows = db.execute(
        f"SELECT {COLS} FROM catalog WHERE {where} ORDER BY (enr = ?) DESC, length(name), name LIMIT ?",
        args + [exact, max(1, min(limit, 100))],
    ).fetchall()
    return [_row(r) for r in rows]


def lookup(db, enr):
    r = db.execute(f"SELECT {COLS} FROM catalog WHERE enr = ?", (enr.strip().upper(),)).fetchone()
    return _row(r) if r else None


def main(argv):
    args = argv[1:]
    db_path = "catalog.db"
    supplier = ""
    global PRICE_PER
    for flag in ("--db", "--supplier", "--price-per"):
        if flag in args:
            i = args.index(flag)
            value = args[i + 1]
            del args[i:i + 2]
            if flag == "--db":
                db_path = value
            elif flag == "--price-per":
                PRICE_PER = float(value)
            else:
                supplier = value
    if len(args) == 2 and args[0] == "import":
        db = open_db(db_path)
        n = import_xlsx(db, args[1], supplier)
        print(f"Imported {n} articles into {db_path}")
    elif len(args) >= 2 and args[0] == "search":
        for it in search(open_db(db_path), " ".join(args[1:]), 15):
            print(it["eNumber"], "|", it["name"], "|", it["unit"], "|", it["net"], "kr", "(excl. metal surcharge)" if it["metalSurcharge"] else "")
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
