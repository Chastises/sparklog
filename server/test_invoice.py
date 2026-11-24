"""Tests for invoice parsing and mail import, using a generated PDF and a fake IMAP server.
These check the logic. Real supplier layouts still need checking against real sample invoices."""
import io
import os
import sys
import tempfile
from email.message import EmailMessage

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import invoice
import mailimport
import server


def make_pdf(lines):
    from reportlab.pdfgen import canvas
    buf = io.BytesIO()
    c = canvas.Canvas(buf)
    c.setFont("Courier", 9)
    y = 800
    for line in lines:
        c.drawString(40, y, line)
        y -= 14
    c.save()
    return buf.getvalue()


INVOICE_LINES = [
    "Ahlsell Sverige AB                         Fakturanummer: 8812345",
    "Fakturadatum: 2026-11-20",
    "Er referens: Jobb 1042",
    "",
    "Artikelnr   Benamning                 Antal Enhet  A-pris   Rabatt   Belopp",
    "5012345     Kabel EXQ 3G1.5 100m        2 rle    1 234,50     10%  2 221,10",
    "6000111     Fixtur Jordfelsbrytare      4 st       189,00             756,00",
    "7000222     Dosa uttag 1-vagg          25 st        12,40             310,00",
    "Summa exkl moms                                                    3 287,10",
    "Moms 25%                                                             821,78",
    "Att betala                                                         4 108,88",
]


def test_parse_lines_text():
    items = invoice.parse_lines("\n".join(INVOICE_LINES))
    assert len(items) == 3, items
    cable, rcd, box = items
    assert cable["article"] == "5012345" and cable["quantity"] == 2 and cable["unit"] == "rle"
    assert abs(cable["unit_price"] - 1110.55) < 0.01  # net price after 10% discount: 2221.10 / 2
    assert rcd["quantity"] == 4 and rcd["unit_price"] == 189.0
    assert box["quantity"] == 25 and box["unit_price"] == 12.4
    # totals and header lines are not material lines
    assert not any("Summa" in i["name"] or "Moms" in i["name"] or "betala" in i["name"] for i in items)


def test_no_article_layout():
    items = invoice.parse_lines("Kabelstege 3 m   2 st   145,00   290,00")
    assert items and items[0]["name"].startswith("Kabelstege") and items[0]["quantity"] == 2


def test_job_number_rules():
    known = [1042, 1043]
    assert invoice.find_job_number("Faktura", "Er referens: Jobb 1042", known) == 1042
    assert invoice.find_job_number("Faktura #1043", "", known) == 1043
    assert invoice.find_job_number("Faktura", "Märkning: 1042", known) == 1042
    # unknown numbers and plain invoice numbers must not match
    assert invoice.find_job_number("Faktura 8812345", "Fakturanummer: 8812345", known) is None
    assert invoice.find_job_number("x", "Jobb 9999", known) is None


def make_mail(subject="Faktura 8812345", sender="Ahlsell <faktura@ahlsell.se>", msg_id="<m1@ahlsell.se>", lines=INVOICE_LINES):
    m = EmailMessage()
    m["From"], m["To"], m["Subject"], m["Message-ID"] = sender, "inbox@example.com", subject, msg_id
    m["Date"] = "Fri, 20 Nov 2026 09:00:00 +0100"
    m.set_content("Hej, bifogat finns faktura.")
    m.add_attachment(make_pdf(lines), maintype="application", subtype="pdf", filename="faktura.pdf")
    return m.as_bytes()


def setup_db():
    d = tempfile.mkdtemp()
    db = server.connect(os.path.join(d, "t.db"))
    server.Handler.db = db
    server.Handler.clock = 0
    server.store(db, {
        "customers": [{"id": "c1", "name": "Andersson", "updatedAt": 1}],
        "jobs": [{"id": "j1", "number": 1042, "customerId": "c1", "title": "Fuse box", "updatedAt": 1}],
    })
    return db, d


def materials(db):
    return [dict(r) for r in db.execute("SELECT * FROM materials WHERE deleted = 0 ORDER BY name")]


def test_import_matches_job():
    db, d = setup_db()
    store = lambda rows: server.store(db, rows)
    n = mailimport.process_message(db, store, make_mail(), invoice_dir=os.path.join(d, "inv"))
    assert n == 3, n
    rows = materials(db)
    assert len(rows) == 3
    assert all(r["jobId"] == "j1" and r["customerId"] == "c1" and r["source"] == "invoice" for r in rows)
    assert all(r["markupPercent"] == -1.0 for r in rows)  # imported lines follow the job's markup
    assert all(r["invoiceRef"] == "8812345" and r["supplier"] == "Ahlsell" and r["date"] == "2026-11-20" for r in rows)
    cable = [r for r in rows if r["name"].startswith("Kabel")][0]
    assert cable["quantity"] == 2 and abs(cable["unitPrice"] - 1110.55) < 0.01
    assert len(os.listdir(os.path.join(d, "inv"))) == 1  # PDF kept

    # importing the same mail again changes nothing
    mailimport.process_message(db, store, make_mail())
    assert len(materials(db)) == 3

    # a later edit in the app is not overwritten by a re-import
    server.store(db, {"materials": [dict(cable, deleted=False, updatedAt=int(9e12), name="Edited")]})
    mailimport.process_message(db, store, make_mail())
    assert any(r["name"] == "Edited" for r in materials(db))


def test_unknown_job_and_unreadable_pdf():
    db, d = setup_db()
    store = lambda rows: server.store(db, rows)
    no_job = [l.replace("Jobb 1042", "Jobb 7777") for l in INVOICE_LINES]
    mailimport.process_message(db, store, make_mail(msg_id="<m2@x>", lines=no_job))
    assert all(r["jobId"] == "" for r in materials(db)) and len(materials(db)) == 3

    mailimport.process_message(db, store, make_mail(msg_id="<m3@x>", lines=["Scanned image only"]))
    unread = [r for r in materials(db) if r["name"].startswith("UNREAD INVOICE")]
    assert len(unread) == 1 and unread[0]["source"] == "invoice"


def test_sender_filter_and_no_pdf():
    db, d = setup_db()
    store = lambda rows: server.store(db, rows)
    assert mailimport.process_message(db, store, make_mail(sender="Spam <x@spam.com>"), allowed_senders=["ahlsell.se"]) == 0
    m = EmailMessage(); m["From"] = "a@b.se"; m["Subject"] = "hi"; m.set_content("no attachment")
    assert mailimport.process_message(db, store, m.as_bytes()) == 0
    assert materials(db) == []


class FakeImap:
    def __init__(self, mails):
        self.mails = dict(enumerate(mails, start=1))
        self.seen = set()
    def search(self, _charset, _crit):
        return "OK", [b" ".join(str(k).encode() for k in self.mails if k not in self.seen)]
    def fetch(self, num, _what):
        return "OK", [(b"1 (BODY[] {1})", self.mails[int(num)]), b")"]
    def store(self, num, _op, _flag):
        self.seen.add(int(num))


def test_poll_marks_seen():
    db, d = setup_db()
    store = lambda rows: server.store(db, rows)
    imap = FakeImap([make_mail(), make_mail(msg_id="<m9@x>", subject="Faktura #1042")])
    assert mailimport.poll_once(imap, db, store) == 6
    assert imap.seen == {1, 2}
    assert mailimport.poll_once(imap, db, store) == 0  # nothing unread


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
    print("all invoice tests passed")
