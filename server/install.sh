#!/bin/bash
# One-command installer for the SparkLog server on Debian/Ubuntu (run as root, e.g. in a Proxmox container).
# Safe to run again: it updates the server files and keeps your token.
set -e
BASE="https://raw.githubusercontent.com/Chastises/sparklog/main/server"
DIR=/opt/sparklog
ENVFILE=/etc/sparklog.env

[ "$(id -u)" = 0 ] || { echo "Run this as root."; exit 1; }

apt-get update -qq
apt-get install -y -qq python3 poppler-utils curl openssl >/dev/null

mkdir -p "$DIR"
for f in server.py catalog.py invoice.py mailimport.py; do
  curl -fsSL "$BASE/$f" -o "$DIR/$f"
done

if [ ! -f "$ENVFILE" ]; then
  echo "SPARKLOG_TOKEN=$(openssl rand -hex 24)" > "$ENVFILE"
  echo "SPARKLOG_DB=$DIR/sparklog.db" >> "$ENVFILE"
  chmod 600 "$ENVFILE"
fi

cat > /etc/systemd/system/sparklog.service <<UNIT
[Unit]
Description=SparkLog server
After=network.target
[Service]
EnvironmentFile=$ENVFILE
WorkingDirectory=$DIR
ExecStart=/usr/bin/python3 $DIR/server.py
Restart=always
[Install]
WantedBy=multi-user.target
UNIT

cat > /usr/local/bin/sparklog-import <<WRAP
#!/bin/bash
# Usage: sparklog-import price-list.xlsx "Retailer name"
cd $DIR && python3 catalog.py import "\$1" --supplier "\${2:-}" --db $DIR/catalog.db
WRAP
chmod +x /usr/local/bin/sparklog-import

systemctl daemon-reload
systemctl enable sparklog >/dev/null 2>&1
systemctl restart sparklog
sleep 1
systemctl is-active --quiet sparklog || { echo "The server did not start. See: journalctl -u sparklog"; exit 1; }

IP=$(hostname -I | awk '{print $1}')
echo
echo "SparkLog server is running."
echo "In the app, Settings:"
echo "  Server URL:  http://$IP:8787"
echo "  Token:       $(grep '^SPARKLOG_TOKEN=' "$ENVFILE" | cut -d= -f2)"
echo
echo "Price list later:  sparklog-import /root/yourfile.xlsx \"Retailer name\""
