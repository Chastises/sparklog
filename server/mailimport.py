"""Imports supplier invoices from a mailbox into materials.

The server polls an IMAP mailbox. For each unread email with PDF attachments it finds the job
number (subject or invoice text), parses the material lines and stores them as materials with
source "invoice". Lines whose job cannot be found are stored without a job; the app flags them
"NEEDS JOB" so you can assign one with a tap. Nothing is dropped silently: an unreadable PDF
becomes a single "UNREAD INVOICE" line.

Settings (environment): SPARKLOG_IMAP_HOST, SPARKLOG_IMAP_USER, SPARKLOG_IMAP_PASSWORD (required),
SPARKLOG_IMAP_PORT (993), SPARKLOG_IMAP_FOLDER (INBOX), SPARKLOG_IMAP_INTERVAL seconds (300),
SPARKLOG_ALLOWED_SENDERS (comma separated; only From addresses containing one of them are read),
SPARKLOG_INVOICE_DIR (invoices/, where PDFs are kept).
Use a dedicated mailbox and an app password.
"""
import email
import email.utils
import hashlib
import imaplib
import os
import sqlite3
import threading
import time
import uuid
from email import policy

import invoice


def log(msg):
    print(f"[mail] {msg}", flush=True)


def known_jobs(db):
    return {r["number"]: r for r in db.execute("SELECT id, number, customerId FROM jobs WHERE deleted = 0")}


def sender_allowed(from_header, allowed):
    if not allowed:
        return True
    addr = (email.utils.parseaddr(from_header)[1] or from_header).lower()
    return any(a.strip().lower() in addr for a in allowed if a.strip())


def supplier_name(from_header):
    name, addr = email.utils.parseaddr(from_header)
    if name:
        return name
    domain = addr.split("@")[-1]
    return domain.split(".")[0].capitalize() if domain else ""


def process_message(db, store, raw, allowed_senders=(), invoice_dir=None):
    """Parse one raw email. Returns the number of material rows stored (0 if nothing to import)."""
    msg = email.message_from_bytes(raw, policy=policy.default)
    if not sender_allowed(msg.get("From", ""), allowed_senders):
        log(f"skipping mail from {msg.get('From')} (sender not allowed)")
        return 0

    msg_id = (msg.get("Message-ID") or hashlib.sha1(raw).hexdigest()).strip()
    subject = str(msg.get("Subject", ""))
    supplier = supplier_name(msg.get("From", ""))
    try:
        sent = email.utils.parsedate_to_datetime(msg.get("Date"))
        sent_ms = int(sent.timestamp() * 1000)
        sent_day = sent.date().isoformat()
    except Exception:
        sent_ms = int(time.time() * 1000)
        sent_day = time.strftime("%Y-%m-%d")

    pdfs = [
        (part.get_filename() or "invoice.pdf", part.get_content())
        for part in msg.iter_attachments()
        if (part.get_content_type() == "application/pdf" or (part.get_filename() or "").lower().endswith(".pdf"))
    ]
    if not pdfs:
        log(f"no PDF in '{subject}'")
        return 0

    body_part = msg.get_body(preferencelist=("plain",))
    body = body_part.get_content() if body_part else ""
    jobs = known_jobs(db)
    rows = []
    for a_idx, (filename, data) in enumerate(pdfs):
        if invoice_dir:
            os.makedirs(invoice_dir, exist_ok=True)
            with open(os.path.join(invoice_dir, hashlib.sha1(data).hexdigest() + ".pdf"), "wb") as f:
                f.write(data)
        try:
            text = invoice.pdf_to_text(data)
        except Exception as e:  # pdftotext missing or broken PDF
            log(f"could not read {filename}: {e}")
            text = ""

        number = invoice.find_job_number(subject, body + "\n" + text, jobs.keys())
        job = jobs.get(number)
        ref = invoice.find_invoice_ref(text) or filename
        day = invoice.find_invoice_date(text) or sent_day
        items = invoice.parse_lines(text)
        base = {
            "date": day,
            "jobId": job["id"] if job else "",
            "customerId": job["customerId"] if job else "",
            "supplier": supplier,
            "invoiceRef": ref,
            "source": "invoice",
            "updatedAt": sent_ms,  # fixed per mail, so re-importing never overwrites later edits
            "deleted": False,
        }
        if not items:
            items = [{"article": "", "e_number": "", "name": f"UNREAD INVOICE: {filename}", "quantity": 1,
                      "unit": "pcs", "unit_price": 0.0}]
        for l_idx, it in enumerate(items):
            name = it["name"] + (f" ({it['article']})" if it["article"] else "")
            rows.append(dict(
                base,
                id=str(uuid.uuid5(uuid.NAMESPACE_URL, f"sparklog:{msg_id}:{a_idx}:{l_idx}")),
                eNumber=it.get("e_number", ""),
                name=name, quantity=it["quantity"], unit=it["unit"], unitPrice=it["unit_price"],
            ))
        log(f"'{subject}' {filename}: {len(items)} lines, job {number if job else 'not found'}")

    store({"materials": rows})
    return len(rows)


def poll_once(imap, db, store, allowed_senders=(), invoice_dir=None, failures=None):
    """Process all unread mails on an already logged-in, folder-selected IMAP connection."""
    failures = failures if failures is not None else {}
    status, data = imap.search(None, "UNSEEN")
    if status != "OK":
        return 0
    total = 0
    for num in data[0].split():
        status, parts = imap.fetch(num, "(BODY.PEEK[])")
        if status != "OK" or not parts or not isinstance(parts[0], tuple):
            continue
        raw = parts[0][1]
        try:
            total += process_message(db, store, raw, allowed_senders, invoice_dir)
        except Exception as e:
            failures[num] = failures.get(num, 0) + 1
            log(f"failed on mail {num!r} (attempt {failures[num]}): {e}")
            if failures[num] < 3:
                continue  # leave unread, retry next poll
        imap.store(num, "+FLAGS", "\\Seen")
    return total


def run_forever(db_path, store):
    host = os.environ["SPARKLOG_IMAP_HOST"]
    user = os.environ["SPARKLOG_IMAP_USER"]
    password = os.environ["SPARKLOG_IMAP_PASSWORD"]
    port = int(os.environ.get("SPARKLOG_IMAP_PORT", "993"))
    folder = os.environ.get("SPARKLOG_IMAP_FOLDER", "INBOX")
    interval = int(os.environ.get("SPARKLOG_IMAP_INTERVAL", "300"))
    allowed = os.environ.get("SPARKLOG_ALLOWED_SENDERS", "").split(",")
    invoice_dir = os.environ.get("SPARKLOG_INVOICE_DIR", "invoices")
    failures = {}
    while True:
        try:
            db = sqlite3.connect(db_path)  # own connection for reads; writes go through store()
            db.row_factory = sqlite3.Row
            imap = imaplib.IMAP4_SSL(host, port)
            try:
                imap.login(user, password)
                imap.select(folder)
                poll_once(imap, db, store, allowed, invoice_dir, failures)
            finally:
                db.close()
                try:
                    imap.logout()
                except Exception:
                    pass
        except Exception as e:
            log(f"poll failed: {e}")
        time.sleep(interval)


def start_background(db_path, store):
    t = threading.Thread(target=run_forever, args=(db_path, store), daemon=True, name="mail-import")
    t.start()
    return t
