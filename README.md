# SparkLog

Android app for an electrician's daily record keeping: calendar, driving (manual and GPS), materials, and a reminder to log the day's work. Data lives on the phone (Room/SQLite) and syncs to your own server.

## What's in it

- **Home**: first screen. Today's hours, trips and materials with a prompt if today isn't logged, open and overdue to-dos, month totals, a warning for invoice lines needing a job, and a pinned notes card (saved on the phone only). Settings opens from the gear.
- **To-do**: list with checkboxes, optional job, and an optional reminder date and time that triggers a notification (scheduled with WorkManager, so it can be a few minutes late in battery saving mode). Done items are tucked away. To-dos sync to the server.
- **Jobs tab**: one place for everything that belongs to a job, with sections Jobs, Customers, Drive and Materials. The bottom bar is Home, Calendar, Jobs, To-do.
- **Materials**: one row per job with its total material cost, plus a red "No job assigned" row for imported invoice lines that need a job. Tap a job to see its material lines grouped by date, with a daily total; tap a line to edit it or change its job.
- **Export**: the share icon on a job page makes a PDF summary (customer, totals, materials by date with day totals, work hours, driving) or a CSV of the material lines, and opens Android's share sheet so you can email it or save it. Prices are as entered or imported (ex VAT).
- **Job overview**: tap a job to see its customer details (call or email in one tap), totals for hours, km and material cost, and every work entry, trip, material line and to-do on the job. Add new entries straight to the job, mark it finished or reopen it, or edit it with the pencil.
- **Jobs**: every job has a unique number (plain counter, starting at 1001) and belongs to a customer. A customer can have many jobs. Jobs have Ongoing/Finished lists with hours and material cost; finished jobs disappear from the job dropdowns. Create jobs from the Jobs tab or with "+ New job" in any dropdown. If two phones create the same number, the server renumbers the later one.
- **Customers** (own tab, Ongoing/Finished): name, contact person, phone, email, address, org/ID number, property designation (for ROT), notes. Ongoing/Finished tabs; finishing a customer hides it from the dropdowns. Shows total hours per customer.
- **Work hours**: per day, per job (dropdown, with "+ New job" inline). Trips and materials are tied to a job the same way.
- **Calendar**: month view, dots for work/driving/materials per day, red day number for past weekdays with no work logged, month totals. Tap a day to add or edit entries.
- **Drive**: GPS trip recorder (foreground service, sums distance, saves a trip on stop) plus manual trips. Tap a trip to edit.
- **Materials**: log material, quantity, unit and customer per day.
- **Reminder**: weekdays at the time set in Settings (default 16:00), only if no work entry exists for today. Tapping it opens today.
- **Sync**: two-way, last write wins by `updatedAt`, deletes are soft so they propagate. Runs after every change (when online) and every 6 hours.

If you installed an earlier build of the app, uninstall it first (the database layout changed).

## Build the app

Open the `sparklog` folder in Android Studio (Ladybug or newer). It will sync Gradle and offer to create the wrapper. Run on a device or emulator (minSdk 26).

This code was written without an Android SDK available, so it has **not been compiled yet**. Expect to fix a few small compile errors on the first build, and bump dependency versions in `app/build.gradle.kts` if Android Studio suggests it.

## Run the server on Proxmox

Stdlib Python 3.9+, no packages. In a small LXC or VM:

```
mkdir -p /opt/sparklog && cp server.py /opt/sparklog/
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

## Known gaps / next steps

- No export (CSV/PDF for invoicing or tax) yet.
- If an invoice arrives before the job reaches the server (phone offline), its lines are imported without a job and need assigning by hand.
- GPS trips are lost if Android kills the service mid-trip; no route or from/to place names are recorded.
- Reminder time is set by typing hour and minute; no time picker.
- Reminder uses WorkManager, so it can fire a few minutes late under battery optimization.
- No app icon.
