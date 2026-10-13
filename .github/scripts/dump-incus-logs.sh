#!/usr/bin/env bash
# What Incus itself said, for a job that failed: a refused network update names its cause in
# incusd's log and the bridge's dnsmasq log, not in what isx printed (#1376).
for LOG in /var/log/incus/incusd.log /var/log/incus/dnsmasq.incusbr0.log; do
  echo "=== Last 100 lines of $LOG ==="
  sudo tail -100 "$LOG" || true
done
echo "=== Last 100 lines of the incus journal ==="
sudo journalctl -u incus --no-pager -n 100 || true
