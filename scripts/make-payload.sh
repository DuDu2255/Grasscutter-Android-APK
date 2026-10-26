#!/usr/bin/env bash
# 构建 APK 内嵌 payload：ubuntu-base + 运行库 + JRE21 + MongoDB + grasscutter.jar
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$ROOT/build-payload
ROOTFS=$WORK/rootfs
rm -rf "$WORK"
mkdir -p "$ROOTFS" "$ROOT/app/src/main/assets" "$WORK"

fail() {
  echo "::error::$*"
  exit 1
}

in_rootfs() {
  local log="$WORK/chroot.log"
  if ! sudo chroot "$ROOTFS" /bin/bash -c "$1" >"$log" 2>&1; then
    echo "::error::rootfs 步骤失败: $2"
    tail -n 12 "$log" | while IFS= read -r line; do echo "::error::$line"; done
    exit 1
  fi
}

echo "== ubuntu base =="
curl -L --retry 3 -o "$WORK/base.tgz" \
  https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz || fail "下载 ubuntu-base 失败"
sudo tar -xzf "$WORK/base.tgz" -C "$ROOTFS"

echo "== install runtime libs (qemu) =="
sudo cp /usr/bin/qemu-aarch64-static "$ROOTFS/usr/bin/" || fail "缺少 qemu-aarch64-static"
sudo rm -f "$ROOTFS/etc/resolv.conf"
sudo cp /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
sudo mount -t proc proc "$ROOTFS/proc" || true
in_rootfs "export DEBIAN_FRONTEND=noninteractive LANG=C; apt-get update -qq -o Acquire::Retries=3" "apt-get update"
if ! in_rootfs "export DEBIAN_FRONTEND=noninteractive LANG=C; apt-get install -y -qq --no-install-recommends libcurl4t64 libssl3t64 ca-certificates" "安装 libcurl4t64"; then
  fail "依赖安装失败"
fi
sudo umount "$ROOTFS/proc" || true
sudo rm -f "$ROOTFS/usr/bin/qemu-aarch64-static"
sudo rm -rf "$ROOTFS"/var/lib/apt/lists/* "$ROOTFS"/var/cache/apt/archives/*.deb

echo "== JRE 21 =="
curl -L --retry 3 -o "$WORK/jre.tgz" \
  "https://api.adoptium.net/v3/binary/latest/21/ga/linux/aarch64/jre/hotspot/normal/eclipse" || fail "下载 JRE 失败"
sudo mkdir -p "$ROOTFS/opt/jre"
sudo tar -xzf "$WORK/jre.tgz" -C "$ROOTFS/opt/jre" --strip-components=1
test -x "$ROOTFS/opt/jre/bin/java" || fail "JRE 解压异常：缺 bin/java"

echo "== MongoDB 7.0 =="
curl -L --retry 3 -o "$WORK/mongo.tgz" \
  https://fastdl.mongodb.org/linux/mongodb-linux-aarch64-ubuntu2204-7.0.14.tgz || fail "下载 MongoDB 失败"
sudo mkdir -p "$ROOTFS/opt/mongodb"
sudo tar -xzf "$WORK/mongo.tgz" -C "$ROOTFS/opt/mongodb" --strip-components=1
test -x "$ROOTFS/opt/mongodb/bin/mongod" || fail "MongoDB 解压异常：缺 bin/mongod"

echo "== grasscutter.jar =="
sudo mkdir -p "$ROOTFS/opt/astaps/data/db" "$ROOTFS/opt/astaps/resources" "$ROOTFS/opt/astaps/logs"
curl -L --retry 3 -o "$WORK/grasscutter.jar" \
  https://github.com/MeChen618/AstaPS/releases/latest/download/grasscutter.jar || fail "下载 grasscutter.jar 失败"
test -s "$WORK/grasscutter.jar" || fail "grasscutter.jar 为空"
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
