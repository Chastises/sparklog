# SparkLog

Android app for an electrician's daily record keeping: calendar, driving (manual and GPS), materials, and a reminder to log the day's work. Data lives on the phone (Room/SQLite) and syncs to your own server.

## What's in it

- **Customers**: name, contact person, phone, email, address, org/ID number, property designation (for ROT), notes. Ongoing/Finished tabs; finishing a customer hides it from the dropdowns. Shows total hours per customer.
- **Work hours**: per day, per customer (dropdown, with "+ New customer" inline). Trips and materials can be tied to a customer the same way.
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

## Known gaps / next steps

- No export (CSV/PDF for invoicing or tax) yet.
- GPS trips are lost if Android kills the service mid-trip; no route or from/to place names are recorded.
- Reminder time is set by typing hour and minute; no time picker.
- Reminder uses WorkManager, so it can fire a few minutes late under battery optimization.
- No app icon.
