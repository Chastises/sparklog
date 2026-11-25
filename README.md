# SparkLog

Android app for an electrician's daily record keeping: calendar, driving (manual and GPS), materials, and a reminder to log the day's work. Data lives on the phone (Room/SQLite) and syncs to your own server.

## What's in it

Bottom bar: **Home, Calendar, Jobs, To-do**. Settings opens from the gear on Home.

- **Home**: today's hours, trips and materials with a prompt if today isn't logged, open and overdue to-dos, ongoing jobs with what is left to invoice (ex VAT), a warning for imported invoice lines that need a job, and a pinned notes card (saved on the phone only).
- **Calendar**: month view with dots for work, driving, materials and to-dos, and a red day number for past weekdays with no work logged. Tap a day to add or edit work, trips, materials and to-dos for that day (a to-do added there starts on that day, with a reminder at 08:00 that you can change or switch off).
- **Jobs tab**: Jobs, Customers, Drive and Materials in one place.
  - **Jobs**: each job has a unique number (plain counter from 1001, handed out by the server when one is set up, so two jobs can never get the same number; a new job shows #… until its first sync, and can't be exported until it has a number) and belongs to a customer; finished jobs drop out of the dropdowns. Tap a job for its overview: customer (call or email in one tap), hours, km, materials, profit on materials, the "To invoice" total, and every work entry, trip, material line and to-do on the job. "Mark job as finished" is at the bottom. If two phones create the same number, the server renumbers the later one.
  - **Customers**: name, contact person, phone, email, address, org/ID number, property designation (for ROT), notes. A customer is Active or Inactive; inactive customers are hidden from dropdowns, and a customer with ongoing jobs can't be made inactive.
  - **Drive**: GPS trip recorder (choose the job to record to) plus manual trips.
  - **Materials**: one row per job with its total cost, plus a red "No job assigned" row for imported invoice lines. Tap a job for its lines grouped by date.
- **Rates and prices**: Settings holds the default material markup (30% to start with), hourly rate, driving price per 10 km and call-out fee, all ex VAT. A new job copies them and keeps them, so changing Settings later never changes an old job (jobs that still follow a default are frozen at the old value when you change it). A work entry can override the hourly rate and chooses whether the call-out fee is charged for that visit. Customer prices are shown ex VAT and incl. 25% VAT.
- **Materials detail**: optional E-number, quantity, unit, price, supplier; a line can have its own markup. "Search price list" fills a line from the retailer's price list (see below). Only you see cost, markup and profit.
- **Export**: the share icon on a job makes a PDF summary (customer, materials by date, work, driving, and a summary with totals ex VAT, VAT and incl. VAT) or a CSV of the material lines. Customer-facing output never shows markup, supplier or invoice references.
- **To-do**: checkboxes, optional job, optional reminder date and time (WorkManager, so it can be a few minutes late in battery saving mode).
- **Reminder**: weekdays at the time set in Settings (default 16:00), only if no work is logged for today. Tapping it opens today.
- **Sync**: two-way, last write wins by `updatedAt`, deletes are soft so they propagate. Runs after every change when online and every 6 hours. The token is hidden in Settings (Show/Hide).

## Build and install the app

GitHub Actions builds the APK on every push to `main` and publishes it as a release (`build-N`). Install and update it with Obtainium. To keep updates installable, builds are signed with a fixed key supplied through the `SIGNING_KEYSTORE_B64` repository secret. You can also open the folder in Android Studio and run it on a device (minSdk 26).

## Run the server on Proxmox

Easiest: in a Debian/Ubuntu container run the installer as root. It installs what is needed, sets up a service that starts on boot, makes a token and prints the URL and token for the app. Running it again updates the server and keeps the token.

```
apt update && apt install -y curl && curl -fsSL https://raw.githubusercontent.com/Chastises/sparklog/main/server/install.sh | bash
```

By hand (stdlib Python 3.9+, no packages):

```
mkdir -p /opt/sparklog && cp server/*.py /opt/sparklog/
export SPARKLOG_TOKEN="$(openssl rand -hex 24)"   # keep this, it goes in the app
SPARKLOG_DB=/opt/sparklog/sparklog.db python3 /opt/sparklog/server.py
```

For a permanent setup, a systemd unit with `Environment=SPARKLOG_TOKEN=...` and `ExecStart=/usr/bin/python3 /opt/sparklog/server.py` works. Back up `sparklog.db`.

In the app: Settings, enter `http://<server-ip>:8787` and the token, then "Sync now".

**Security:** the connection is plain HTTP (the manifest allows cleartext). That is fine on your home LAN or over WireGuard/Tailscale. If you expose it to the internet, put it behind a reverse proxy with HTTPS (Caddy, nginx) and remove `usesCleartextTraffic` from the manifest.

Tests: `python3 -I server/test_server.py`

## Importing material invoices from email

The server can read a mailbox, find PDF invoices, and add every material line to the right job.

1. Use a dedicated mailbox (or an alias that forwards to one) with IMAP enabled and an app password.
2. On the server: `apt install poppler-utils` (provides `pdftotext`).
3. Start the server with the mailbox settings:

```
SPARKLOG_IMAP_HOST=imap.example.com SPARKLOG_IMAP_USER=invoices@example.com \
SPARKLOG_IMAP_PASSWORD=app-password SPARKLOG_ALLOWED_SENDERS=ahlsell.se,onninen.se \
SPARKLOG_TOKEN=... python3 server.py
```

4. **When ordering from the supplier, put the job number in the order reference**, for example `Jobb 1042` (the invoice then shows it as "Er referens" or "Märkning"). Writing `#1042` in the email subject also works. Only numbers that match an existing job are accepted.

Lines whose job can't be found are still imported, marked NEEDS JOB in the Materials tab; tap one to assign a job. A PDF that can't be read becomes one "UNREAD INVOICE" line. PDFs are kept in `invoices/` on the server. Importing the same mail twice does nothing.

Supplier invoice layouts differ, and the line parser is heuristic. Test a real invoice with `python3 invoice.py invoice.pdf` and send me samples if lines are missed. Settings are listed at the top of `server/mailimport.py`.

Tests: `python3 server/test_invoice.py`

## Retailer price list

The retailer's price file (Excel) is imported on the server, not into the app (it has 200,000+ articles). On the server, in the `server` folder:

```
python3 catalog.py import Prisfil.xlsx --supplier "Retailer name"
```

This writes `catalog.db` next to the sync database (or set `SPARKLOG_CATALOG`). No restart is needed, and importing a newer file replaces the old list. `python3 catalog.py search "minibrytare s201"` tests it from the shell. From a phone or computer you can also open `http://<server-ip>:8787/upload` in a browser, enter the token, choose the .xlsx and tap Upload; no shell needed. In the app, **Add material → Search price list** searches by name or E-number (needs the server URL and token in Settings) and fills in E-number, name, unit and your net price. The net price is the file's `KN` column, per 1 unit (set by `PRICE_PER` in `catalog.py`; if prices ever look off by a fixed factor, import again with `--price-per`). Cable lines marked with a copper surcharge in the file get a warning, since the surcharge is not in the list price.

## HTTPS

The server itself speaks plain HTTP. For HTTPS, put it behind the reverse proxy you already run (Caddy, nginx, Nginx Proxy Manager, Traefik): point a hostname at `http://<server-ip>:8787`, then use `https://that-hostname` as the Server URL in Admin. If the proxy runs on the same machine, add `SPARKLOG_HOST=127.0.0.1` to `/etc/sparklog.env` and restart the service (`systemctl restart sparklog`) so the plain HTTP port can't be reached directly. If the proxy uses a private certificate authority, install that CA certificate on the phone. Once everything uses HTTPS, `usesCleartextTraffic` can be removed from the manifest. The token is stored encrypted on the phone (Android Keystore).

Notes on Home: any number of notes, each with a title, synced to the server and deletable.

Development reset: Admin has a "Reset all data" button (type RESET to confirm). It makes the server save a backup copy of its database (`sparklog.db.bak-<time>` next to it), empties the server and the phone, and job numbers restart at 1001. Needs a server that has this endpoint.

## Version check

The app asks the server for its sync version before every sync (`API_VERSION` in `server/server.py`, `SYNC_API` in `SyncWorker.kt`). If they differ it does not sync and says which side to update: the server (run the install command again) or the app (Obtainium). Bump both numbers whenever a synced field is added or changed. A server from before this check counts as version 0, so it asks you to update it.

## Known gaps / next steps

- If an invoice arrives before the job reaches the server (phone offline), its lines are imported without a job and need assigning by hand.
- GPS trips are lost if Android kills the service mid-trip; no route or from/to place names are recorded.
- Reminder time is set by typing hour and minute; no time picker.
- No app icon.
- Prices from the price list are only searchable while the server is reachable.
