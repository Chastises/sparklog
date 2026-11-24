"""Invoice parsing: PDF -> text -> job number, invoice reference and material lines.

Needs `pdftotext` (package poppler-utils) for PDF input. The parsing functions themselves work
on plain text, so they can be tested and tuned without PDFs:

    python3 invoice.py invoice.pdf      # prints what would be imported

Supplier layouts differ, so line parsing is heuristic: it looks for rows shaped like
`article  description  quantity unit  unit-price  [discount]  amount` (article number optional).
The description is matched greedily, so lengths in it ("Cable 100m") are not mistaken for the quantity.
When a supplier's layout is not recognised, send a sample PDF and tune LINE_PATTERNS.
"""
import re
import subprocess
import sys

UNITS = r"(?:st|stk|pcs|m|mtr|meter|förp|frp|fp|pkt|rle|rul|rl|par|kg|l|krt|ask|set|sats|ea)"
NUM = r"\d{1,3}(?:[  ]\d{3})*(?:[.,]\d+)?|\d+(?:[.,]\d+)?"

LINE_PATTERNS = [
    # with article number: ART  Description  10 st  12,50  [rest...]
    re.compile(
        rf"^\s*(?P<art>(?=[A-Za-z0-9\-._/]*\d)[A-Za-z0-9][A-Za-z0-9\-._/]{{3,}})\s+(?P<name>\S.*)\s+"
        rf"(?P<qty>{NUM})\s*(?P<unit>{UNITS})\b\s+(?P<price>{NUM})(?:\s+(?P<rest>.*))?$",
        re.I,
    ),
    # without article number: Description  10 st  12,50  [rest...]
    re.compile(
        rf"^\s*(?P<name>[A-Za-zÅÄÖåäö][^\n]{{2,}})\s+"
        rf"(?P<qty>{NUM})\s*(?P<unit>{UNITS})\b\s+(?P<price>{NUM})(?:\s+(?P<rest>.*))?$",
        re.I,
    ),
]

JOB_KEYWORD = re.compile(
    r"(?:jobb?(?:nummer|nr|no)?|projekt(?:nummer|nr)?|märkning|er\s+referens|referens|reference|ref\.?|"
    r"kostnadsställe|order\s*märkning)\s*[:\-]?\s*#?\s*(\d{4,6})\b",
    re.I,
)
JOB_HASH = re.compile(r"#\s*(\d{4,6})\b")
# E-number (Swedish electrical product number): labelled "E-nr 1234567", or a bare 7-digit article number
E_LABEL = re.compile(r"\bE[-\s]?(?:nr|nummer|no)\.?\s*[:.]?\s*(\d{6,8})\b", re.I)
SEVEN_DIGITS = re.compile(r"^\d{7}$")
INVOICE_REF = re.compile(
    r"(?:fakturanummer|fakturanr\.?|faktura\s*nr\.?|invoice\s*(?:no|number|nr)?\.?)\s*[:.]?\s*([A-Za-z0-9\-]{4,})",
    re.I,
)
INVOICE_DATE = re.compile(r"(?:fakturadatum|invoice\s*date)\s*[:.]?\s*(\d{4}-\d{2}-\d{2})", re.I)


def pdf_to_text(data: bytes) -> str:
    out = subprocess.run(
        ["pdftotext", "-layout", "-", "-"], input=data, capture_output=True, timeout=60, check=True
    )
    return out.stdout.decode("utf-8", errors="replace")


def parse_number(s: str) -> float:
    return float(s.replace(" ", "").replace(" ", "").replace(",", "."))


def find_job_number(subject: str, text: str, known_numbers) -> "int | None":
    """First job number found next to a keyword ('Jobb 1042', 'Märkning: 1042', '#1042') that
    exists in `known_numbers`. Unknown numbers are ignored, so invoice/order numbers don't match."""
    known = set(known_numbers)
    haystack = subject + "\n" + text
    candidates = [int(m.group(1)) for m in JOB_KEYWORD.finditer(haystack)]
    candidates += [int(m.group(1)) for m in JOB_HASH.finditer(subject)]
    for c in candidates:
        if c in known:
            return c
    return None


def find_invoice_ref(text: str) -> str:
    m = INVOICE_REF.search(text)
    return m.group(1) if m else ""


def find_invoice_date(text: str) -> str:
    m = INVOICE_DATE.search(text)
    return m.group(1) if m else ""


def parse_lines(text: str) -> list:
    """Material lines found in invoice text: dicts with article, e_number, name, quantity, unit,
    unit_price. unit_price is the net price per unit (amount / quantity when a row amount is present).
    The E-number is taken from an "E-nr 1234567" label on the row (or alone on the next line), else
    from the article number when that is exactly 7 digits; "" when none is found."""
    items = []
    for line in text.splitlines():
        if not line.strip():
            continue
        matched = False
        for pattern in LINE_PATTERNS:
            m = pattern.match(line)
            if not m:
                continue
            matched = True
            try:
                qty = parse_number(m.group("qty"))
                price = parse_number(m.group("price"))
            except ValueError:
                continue
            if qty <= 0:
                continue
            amount = None
            rest = m.groupdict().get("rest") or ""
            nums = re.findall(NUM, rest)
            if nums:
                try:
                    amount = parse_number(nums[-1])
                except ValueError:
                    amount = None
            # a row amount that differs from qty*price means a discount: use the net price
            if amount and abs(amount - qty * price) > 0.02 * max(amount, 1):
                price = round(amount / qty, 2)
            art = (m.groupdict().get("art") or "").strip()
            label = E_LABEL.search(line)
            e_number = label.group(1) if label else (art if SEVEN_DIGITS.match(art) else "")
            items.append({
                "article": art,
                "e_number": e_number,
                "_explicit_e": bool(label),
                "name": E_LABEL.sub("", m.group("name")).strip(),
                "quantity": qty,
                "unit": m.group("unit").lower(),
                "unit_price": price,
            })
            break
        if not matched and items and not items[-1]["_explicit_e"]:
            label = E_LABEL.search(line)  # E-number on its own line under the item; beats a guessed one
            if label:
                items[-1]["e_number"] = label.group(1)
                items[-1]["_explicit_e"] = True
    for it in items:
        del it["_explicit_e"]
    return items


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: python3 invoice.py invoice.pdf")
    with open(sys.argv[1], "rb") as f:
        txt = pdf_to_text(f.read())
    print("invoice ref :", find_invoice_ref(txt) or "-")
    print("invoice date:", find_invoice_date(txt) or "-")
    print("job keyword numbers:", [m.group(1) for m in JOB_KEYWORD.finditer(txt)] or "-")
    for it in parse_lines(txt):
        print(it)
    missing = [it["name"] for it in parse_lines(txt) if not it["e_number"]]
    if missing:
        print("lines without E-number:", missing)
