#!/bin/bash
set -euo pipefail

# Boot a VM base image with a given kernel as the ESP's only loader, and time it.
#
# The kernel built by `kernel/build-kernel.sh vm` is an EFI application with its
# command line built in (#1255): the firmware starts it from the removable-media
# path, with no boot loader, no initrd and no NVRAM entry. This boots a copy of a
# raw GPT disk that way under QEMU (OVMF on x86_64, AAVMF on aarch64, Secure Boot
# off, 8 vCPUs, 4 GiB, the root on virtio-blk and a virtio NIC, since the
# image waits for the network to finish booting), asserts that the guest reaches
# basic.target with every virtio interrupt delivered by MSI, and prints where
# the time went.
#
# Usage:  ./test-vm-kernel.sh <vmlinuz> <disk.raw> [arch]
#
#   <disk.raw>  a GPT disk, raw or qcow2, with an ESP (type EF00) and an ext4
#               root (the first 8300/8304/8305 partition): the stock VM image's
#               rootfs.img until the from-scratch image exists. It is never
#               written: the test
#               boots a sparse copy, prepared without loop devices or root (so it
#               runs in a container and on GitHub runners):
#                 - the ESP is reformatted to hold only EFI/BOOT/BOOTX64.EFI
#                   (BOOTAA64.EFI), the kernel under test;
#                 - the root partition is named isx-root, the PARTLABEL the
#                   kernel's built-in command line names;
#                 - debugfs drops /boot/efi from fstab (the kernel has no VFAT),
#                   makes basic.target the default target, masks
#                   systemd-networkd-wait-online (no .network matches the test
#                   NIC, so the boot would never finish; it is not part of
#                   basic.target), and adds a unit that reports systemd-analyze
#                   on the console and powers off.
#
#   RUNS=n           boots to time (default 1); each starts from the same copy
#                    (QEMU snapshot), and with several the median is printed too
#   TIMEOUT=s        per boot (default 120 under KVM, 900 under TCG)
#   FIRMWARE_CODE    firmware code image (default: the distribution's
#   FIRMWARE_VARS    non-Secure-Boot OVMF/AAVMF); a trimmed EDK2 build is
#                    measured by pointing these at it
#   PLOT_DIR=dir     also save each boot's `systemd-analyze plot` there
#   CONSOLE_DIR=dir  keep each boot's console log, host-timestamped
#
# Times, all from one host clock (QEMU's launch) and the guest's monotonic one:
#   firmware   QEMU launch to the kernel's first instruction: QEMU's own start,
#              then the firmware
#   kernel     the kernel to PID 1
#   userspace  PID 1 to basic.target
#   ready      QEMU launch to basic.target
#
# Exits non-zero if any boot did not reach basic.target, or had a virtio
# device on a legacy interrupt line.

KERNEL="${1:?usage: test-vm-kernel.sh <vmlinuz> <disk.raw> [arch]}"
DISK="${2:?usage: test-vm-kernel.sh <vmlinuz> <disk.raw> [arch]}"
ARCH="${3:-$(uname -m)}"
RUNS="${RUNS:-1}"
# $EPOCHREALTIME and awk must agree on the decimal point.
export LC_ALL=C

for f in "$KERNEL" "$DISK"; do
    [ -f "$f" ] || { echo "ERROR: not found: $f" >&2; exit 1; }
done
for tool in sgdisk debugfs mformat mmd mcopy qemu-img; do
    command -v "$tool" >/dev/null || { echo "ERROR: $tool not found (gdisk, e2fsprogs, mtools, qemu-img)" >&2; exit 1; }
done

case "$ARCH" in
    x86_64)
        QEMU=qemu-system-x86_64
        MACHINE=(-machine q35)
        LOADER=BOOTX64.EFI
        FIRMWARE_PAIRS=(
            /usr/share/OVMF/OVMF_CODE_4M.fd:/usr/share/OVMF/OVMF_VARS_4M.fd
            /usr/share/edk2/ovmf/OVMF_CODE.fd:/usr/share/edk2/ovmf/OVMF_VARS.fd
            /usr/share/OVMF/OVMF_CODE.fd:/usr/share/OVMF/OVMF_VARS.fd
        )
        ;;
    aarch64)
        QEMU=qemu-system-aarch64
        MACHINE=(-machine virt,gic-version=max)
        LOADER=BOOTAA64.EFI
        FIRMWARE_PAIRS=(
            /usr/share/AAVMF/AAVMF_CODE.fd:/usr/share/AAVMF/AAVMF_VARS.fd
            /usr/share/edk2/aarch64/QEMU_EFI-pflash.raw:/usr/share/edk2/aarch64/vars-template-pflash.raw
        )
        ;;
    *) echo "ERROR: unsupported arch: $ARCH" >&2; exit 1 ;;
esac
command -v "$QEMU" >/dev/null || { echo "ERROR: $QEMU not found" >&2; exit 1; }

if [ -z "${FIRMWARE_CODE:-}" ]; then
    for pair in "${FIRMWARE_PAIRS[@]}"; do
        if [ -f "${pair%%:*}" ] && [ -f "${pair##*:}" ]; then
            FIRMWARE_CODE="${pair%%:*}"
            FIRMWARE_VARS="${FIRMWARE_VARS:-${pair##*:}}"
            break
        fi
    done
fi
[ -n "${FIRMWARE_CODE:-}" ] && [ -f "$FIRMWARE_CODE" ] \
    || { echo "ERROR: no firmware for $ARCH; set FIRMWARE_CODE and FIRMWARE_VARS" >&2; exit 1; }
[ -n "${FIRMWARE_VARS:-}" ] && [ -f "$FIRMWARE_VARS" ] \
    || { echo "ERROR: FIRMWARE_VARS not set or not found" >&2; exit 1; }

if [ "$ARCH" = "$(uname -m)" ] && [ -r /dev/kvm ] && [ -w /dev/kvm ]; then
    ACCEL=(-accel kvm -cpu host)
    TIMEOUT="${TIMEOUT:-120}"
else
    ACCEL=(-accel tcg -cpu max)
    TIMEOUT="${TIMEOUT:-900}"
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "Booting $(basename "$KERNEL") on a copy of $(basename "$DISK") ($ARCH, ${ACCEL[1]})"
echo "  Firmware: $FIRMWARE_CODE"

# ---- Prepare the copy ----

IMG="$WORK/disk.raw"
# The Incus image tarball's rootfs.img is qcow2; debugfs and mtools want raw.
if [ "$(head -c 4 "$DISK")" = $'QFI\xfb' ]; then
    qemu-img convert -O raw "$DISK" "$IMG"
else
    cp --sparse=always --reflink=auto "$DISK" "$IMG"
fi

TABLE=$(sgdisk -p "$IMG")
SECTOR=$(sed -n 's/^Sector size (logical): \([0-9]*\) bytes.*/\1/p' <<<"$TABLE")
# Number, start, end of the first partition whose type code matches.
partition() { awk -v codes="$1" '$1 ~ /^[0-9]+$/ && index(" " codes " ", " " $6 " ") { print $1, $2, $3; exit }' <<<"$TABLE"; }
read -r ESP_NUM ESP_START ESP_END < <(partition EF00) || true
read -r ROOT_NUM ROOT_START _ < <(partition "8300 8304 8305") || true
[ -n "${ESP_NUM:-}" ] || { echo "ERROR: no ESP (EF00) on $DISK" >&2; echo "$TABLE" >&2; exit 1; }
[ -n "${ROOT_NUM:-}" ] || { echo "ERROR: no Linux root partition on $DISK" >&2; echo "$TABLE" >&2; exit 1; }

# Not -q: sgdisk 1.0.10 then reports success and leaves the name unset.
sgdisk -c "$ROOT_NUM:isx-root" "$IMG" >/dev/null
sgdisk -i "$ROOT_NUM" "$IMG" | grep -q "^Partition name: 'isx-root'" \
    || { echo "ERROR: could not name partition $ROOT_NUM isx-root" >&2; exit 1; }

# mtools address a filesystem inside an image with @@offset.
ESP="$IMG@@$((ESP_START * SECTOR))"
mformat -i "$ESP" -T $((ESP_END - ESP_START + 1)) -h 64 -s 32 -v ISXESP ::
mmd -i "$ESP" ::/EFI ::/EFI/BOOT
mcopy -i "$ESP" "$KERNEL" "::/EFI/BOOT/$LOADER"

ROOT="$IMG?offset=$((ROOT_START * SECTOR))"
debugfs -R "cat /etc/fstab" "$ROOT" 2>/dev/null | grep -v '[[:space:]]/boot/efi[[:space:]]' > "$WORK/fstab" || true

PLOT=""
[ -n "${PLOT_DIR:-}" ] && PLOT='echo ISX-VM-PLOT-BEGIN; systemd-analyze plot | gzip -c | base64; echo ISX-VM-PLOT-END'
cat > "$WORK/report.sh" <<EOF
#!/bin/sh
# Written by appliance/test-vm-kernel.sh into a test copy of the root.
exec >/dev/console 2>&1
read -r up _ </proc/uptime
echo "ISX-VM-BASIC uptime=\$up kernel_us=\$(systemctl show -P UserspaceTimestampMonotonic) basic_us=\$(systemctl show -P ActiveEnterTimestampMonotonic basic.target)"
# Interrupt lines of virtio devices not delivered by MSI: a kernel without
# PCI_MSI puts every virtio device on one shared legacy line.
echo "ISX-VM-IRQ virtio=\$(grep -c virtio /proc/interrupts) legacy_virtio=\$(grep virtio /proc/interrupts | grep -vc MSI)"
grep virtio /proc/interrupts | sed 's/^/ISX-VM-IRQ-LINE /'
# critical-chain and plot need the boot finished; this unit is Type=simple, so
# its own start does not hold that up.
until systemd-analyze time >/dev/null 2>&1; do sleep 0.1; done
echo ISX-VM-ANALYZE-BEGIN
systemd-analyze time
systemd-analyze critical-chain basic.target
systemd-analyze blame | head -n 25
echo ISX-VM-ANALYZE-END
$PLOT
systemctl poweroff
EOF
cat > "$WORK/report.service" <<'EOF'
[Unit]
Description=Report boot timing for test-vm-kernel.sh
After=basic.target

[Service]
Type=simple
ExecStart=/etc/isx-vm-boot-report.sh
EOF
chmod 644 "$WORK/fstab" "$WORK/report.service"
chmod 755 "$WORK/report.sh"

# debugfs takes the host file's owner, so each file is handed to root. A
# command that fails (an rm of an absent file) is reported and the rest run.
debugfs -w -f - "$ROOT" >"$WORK/debugfs.log" 2>&1 <<EOF
rm /etc/fstab
write $WORK/fstab /etc/fstab
sif /etc/fstab uid 0
sif /etc/fstab gid 0
write $WORK/report.sh /etc/isx-vm-boot-report.sh
sif /etc/isx-vm-boot-report.sh uid 0
sif /etc/isx-vm-boot-report.sh gid 0
write $WORK/report.service /etc/systemd/system/isx-vm-boot-report.service
sif /etc/systemd/system/isx-vm-boot-report.service uid 0
sif /etc/systemd/system/isx-vm-boot-report.service gid 0
mkdir /etc/systemd/system/basic.target.wants
symlink /etc/systemd/system/basic.target.wants/isx-vm-boot-report.service /etc/systemd/system/isx-vm-boot-report.service
rm /etc/systemd/system/default.target
symlink /etc/systemd/system/default.target /usr/lib/systemd/system/basic.target
rm /etc/systemd/system/systemd-networkd-wait-online.service
symlink /etc/systemd/system/systemd-networkd-wait-online.service /dev/null
EOF
debugfs -R "stat /etc/isx-vm-boot-report.sh" "$ROOT" >/dev/null 2>&1 \
    || { echo "ERROR: could not write into the root" >&2; cat "$WORK/debugfs.log" >&2; exit 1; }

# ---- Boot ----

[ -n "${PLOT_DIR:-}" ] && mkdir -p "$PLOT_DIR"
[ -n "${CONSOLE_DIR:-}" ] && mkdir -p "$CONSOLE_DIR"
RESULTS="$WORK/results"
FAILED=0

for run in $(seq 1 "$RUNS"); do
    LOG="$WORK/console-$run.log"
    cp "$FIRMWARE_VARS" "$WORK/vars.fd"
    T0=$EPOCHREALTIME
    set +e
    timeout "$TIMEOUT" "$QEMU" "${MACHINE[@]}" "${ACCEL[@]}" -smp 8 -m 4096 \
        -nodefaults -display none -no-reboot -serial stdio \
        -drive if=pflash,format=raw,unit=0,readonly=on,file="$FIRMWARE_CODE" \
        -drive if=pflash,format=raw,unit=1,file="$WORK/vars.fd" \
        -drive if=none,id=root,format=raw,snapshot=on,file="$IMG" \
        -device virtio-blk-pci,drive=root \
        -netdev user,id=net -device virtio-net-pci,netdev=net \
        </dev/null 2>&1 \
        | while IFS= read -r line; do printf '%s %s\n' "$EPOCHREALTIME" "${line%$'\r'}"; done > "$LOG"
    QEMU_RC=${PIPESTATUS[0]}
    set -e
    [ -n "${CONSOLE_DIR:-}" ] && cp "$LOG" "$CONSOLE_DIR/console-$run.log"

    # The host clock at the report line, less the guest's uptime then, is when
    # the guest's monotonic clock started: the kernel's first instruction.
    TIMES=$(awk -v t0="$T0" '
        / ISX-VM-BASIC / {
            for (i = 3; i <= NF; i++) { split($i, kv, "="); v[kv[1]] = kv[2] }
            fw = $1 - t0 - v["uptime"]
            k = v["kernel_us"] / 1e6
            u = (v["basic_us"] - v["kernel_us"]) / 1e6
            printf "%.3f %.3f %.3f %.3f\n", fw, k, u, fw + v["basic_us"] / 1e6
            exit
        }' "$LOG")
    if [ -z "$TIMES" ]; then
        echo "FAIL: boot $run did not reach basic.target (QEMU exit $QEMU_RC, timeout ${TIMEOUT}s); console tail:"
        tail -n 40 "$LOG" | cut -d' ' -f2- | sed 's/^/    /'
        FAILED=1
        continue
    fi
    # Fail closed: a boot that did not report its interrupts, or reported no
    # virtio line at all, counts as legacy.
    read -r VIRTIO LEGACY < <(sed -n 's/.* ISX-VM-IRQ virtio=\([0-9]*\) legacy_virtio=\([0-9]*\).*/\1 \2/p' "$LOG" | head -n 1) || true
    if [ "${VIRTIO:-0}" = 0 ] || [ "${LEGACY:-1}" != 0 ]; then
        echo "FAIL: boot $run has virtio devices on legacy interrupts, not MSI (${VIRTIO:-no} virtio lines, ${LEGACY:-no} legacy); the guest's /proc/interrupts lines:"
        sed -n 's/.* ISX-VM-IRQ-LINE /    /p' "$LOG"
        FAILED=1
    fi
    echo "$TIMES" >> "$RESULTS"
    read -r FW K U READY <<<"$TIMES"
    printf 'boot %d: firmware %.2fs  kernel %.3fs  userspace %.2fs  ready %.2fs\n' "$run" "$FW" "$K" "$U" "$READY"
    if [ "$run" = 1 ]; then
        sed -n '/ISX-VM-ANALYZE-BEGIN/,/ISX-VM-ANALYZE-END/p' "$LOG" | cut -d' ' -f2- \
            | grep -v '^ISX-VM-ANALYZE-' | sed 's/^/    /'
    fi
    if [ -n "${PLOT_DIR:-}" ]; then
        sed -n '/ISX-VM-PLOT-BEGIN/,/ISX-VM-PLOT-END/p' "$LOG" | cut -d' ' -f2- \
            | grep -v '^ISX-VM-PLOT-' | base64 -d | gunzip -c > "$PLOT_DIR/boot-$run.svg" \
            || echo "  (no plot from boot $run)"
    fi
done

if [ "$RUNS" -gt 1 ] && [ -s "$RESULTS" ]; then
    median() { cut -d' ' -f"$1" "$RESULTS" | sort -n | awk '{ a[NR] = $1 } END { print (NR % 2) ? a[(NR + 1) / 2] : (a[NR / 2] + a[NR / 2 + 1]) / 2 }'; }
    printf 'median of %d: firmware %.2fs  kernel %.3fs  userspace %.2fs  ready %.2fs\n' \
        "$(wc -l < "$RESULTS")" "$(median 1)" "$(median 2)" "$(median 3)" "$(median 4)"
fi

if [ "$FAILED" = 0 ]; then echo "PASS: reached basic.target"; else echo "FAIL"; exit 1; fi
