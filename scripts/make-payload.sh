#!/usr/bin/env bash
# 构建 APK 内嵌 payload：ubuntu-base + libcurl4 + JRE21 + MongoDB + grasscutter.jar
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$ROOT/build-payload
ROOTFS=$WORK/rootfs
rm -rf "$WORK"
mkdir -p "$ROOTFS" "$ROOT/app/src/main/assets"

echo "== ubuntu base =="
curl -L --retry 3 -o "$WORK/base.tgz" \
  https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz
sudo tar -xzf "$WORK/base.tgz" -C "$ROOTFS"

echo "== install libs (qemu) =="
sudo cp /usr/bin/qemu-aarch64-static "$ROOTFS/usr/bin/" || true
sudo cp /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
sudo mount -t proc proc "$ROOTFS/proc" || true
sudo chroot "$ROOTFS" /bin/bash -c "export DEBIAN_FRONTEND=noninteractive; apt-get update -qq; apt-get install -y -qq --no-install-recommends libcurl4 libssl3 ca-certificates"
sudo umount "$ROOTFS/proc" || true
sudo rm -f "$ROOTFS/usr/bin/qemu-aarch64-static"
sudo rm -rf "$ROOTFS"/var/lib/apt/lists/* "$ROOTFS"/var/cache/apt/archives/*.deb

echo "== JRE 21 =="
curl -L --retry 3 -o "$WORK/jre.tgz" \
  "https://api.adoptium.net/v3/binary/latest/21/ga/linux/aarch64/jre/hotspot/normal/eclipse"
sudo mkdir -p "$ROOTFS/opt/jre"
sudo tar -xzf "$WORK/jre.tgz" -C "$ROOTFS/opt/jre" --strip-components=1

echo "== MongoDB 7.0 =="
curl -L --retry 3 -o "$WORK/mongo.tgz" \
  https://fastdl.mongodb.org/linux/mongodb-linux-aarch64-ubuntu2204-7.0.14.tgz
sudo mkdir -p "$ROOTFS/opt/mongodb"
sudo tar -xzf "$WORK/mongo.tgz" -C "$ROOTFS/opt/mongodb" --strip-components=1

echo "== grasscutter.jar =="
mkdir -p "$ROOTFS/opt/astaps/data/db" "$ROOTFS/opt/astaps/resources" "$ROOTFS/opt/astaps/logs"
curl -L --retry 3 -o "$WORK/grasscutter.jar" \
  https://github.com/MeChen618/AstaPS/releases/latest/download/grasscutter.jar
sudo cp "$WORK/grasscutter.jar" "$ROOTFS/opt/astaps/grasscutter.jar"
sudo cp "$ROOT/app/config.json" "$ROOTFS/opt/astaps/config.json"

echo "== payload =="
PAYLOAD=$WORK/payload
mkdir -p "$PAYLOAD"
cp "$ROOT/payload/proot" "$PAYLOAD/proot"
cp "$ROOT/payload/libtalloc.so.2" "$PAYLOAD/libtalloc.so.2"
chmod 755 "$PAYLOAD/proot"
sudo cp -a "$ROOTFS" "$PAYLOAD/rootfs"
sudo tar -czf "$ROOT/app/src/main/assets/payload.tar.gz" -C "$PAYLOAD" .
ls -lh "$ROOT/app/src/main/assets/payload.tar.gz"
