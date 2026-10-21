#!/usr/bin/env bash
# A native isx on macOS trusts a CA an administrator trusts for SSL in the System keychain, as
# a TLS-inspection agent installs it, and counts one an administrator denied (#1467): `isx
# doctor` reports both. That a denied CA is refused is checked by KeychainTrustMacTest, with a
# handshake. Changes the machine's trust settings: run it only on a disposable CI runner.
# Usage: macos-keychain-smoke.sh <isx binary>
set -euo pipefail

isx=$1
keychain=/Library/Keychains/System.keychain
work=$(mktemp -d)

# macOS has no timeout(1): a hang fails the step with what it was doing, not at the job's limit
limit() { local s=$1; shift; perl -e 'alarm shift; exec @ARGV' "$s" "$@"; }

cat > "$work/ca.cnf" <<'CNF'
[req]
distinguished_name = dn
prompt = no
[dn]
CN = placeholder
[v3_ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
CNF
# One CA per case, never re-used: changing or removing a certificate's admin trust settings on a
# runner can wait for an authorization nobody gives (#1515), and nothing here needs to.
new_ca() {
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 -keyout "$work/$1.key" -out "$work/$1.pem" \
    -subj "/CN=isx keychain smoke $1 ${GITHUB_RUN_ID:-local}-$$" -config "$work/ca.cnf" -extensions v3_ca 2>/dev/null
}
fingerprint() { openssl x509 -in "$work/$1.pem" -noout -fingerprint "-$2" | cut -d= -f2 | tr -d :; }
new_ca trusted
new_ca denied
trusted=$(fingerprint trusted sha256 | tr 'A-F' 'a-f')

cleanup() {
  for ca in trusted denied; do
    limit 30 sudo -n security delete-certificate -Z "$(fingerprint "$ca" sha1)" "$keychain" >/dev/null 2>&1 || true
  done
  rm -rf "$work"
}
trap cleanup EXIT

# doctor exits non-zero here (no isx VM on a runner); only its findings matter
doctor() {
  local out rc=0
  out=$(limit 120 "$isx" doctor --format=json 2>"$work/doctor.err") || rc=$?
  # 142 is SIGALRM: the time limit, not one of doctor's own exit codes
  if [ "$rc" = 142 ]; then echo "::error::isx doctor did not finish in 120s" >&2; cat "$work/doctor.err" >&2; exit 1; fi
  echo "$out"
}

echo "isx doctor before either CA is in the keychain"
before=$(doctor)
if echo "$before" | grep -q "$trusted"; then
  echo "::error::isx trusted the smoke CA before it was added to the keychain"
  exit 1
fi

echo "Trusting one CA and denying the other in the System keychain"
limit 60 sudo -n security add-trusted-cert -d -r trustRoot -k "$keychain" "$work/trusted.pem"
limit 60 sudo -n security add-trusted-cert -d -r deny -k "$keychain" "$work/denied.pem"

start=$(date +%s)
after=$(doctor)
echo "doctor took $(( $(date +%s) - start ))s"
echo "$after" | grep -A2 '"Keychain CAs trusted for TLS"' || true
if ! echo "$after" | grep -q "$trusted"; then
  echo "::error::isx doctor does not list the CA an administrator trusts in the System keychain"
  echo "$after"
  exit 1
fi
if ! echo "$after" | grep -qF "1 denied (security dump-trust-settings"; then
  echo "::error::isx doctor does not count the CA an administrator denied"
  echo "$after"
  exit 1
fi
echo "Keychain trust smoke passed"
