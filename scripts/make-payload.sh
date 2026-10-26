#!/usr/bin/env bash
# 构建 APK 内嵌 payload：ubuntu 24.04 (arm64) + JRE21 + libcurl + MongoDB + grasscutter.jar
# 用 docker + binfmt 构建 arm64 rootfs（比 chroot+qemu 稳）
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$ROOT/build-payload
ROOTFS=$WORK/rootfs
rm -rf "$WORK"
mkdir -p "$WORK" "$ROOT/app/src/main/assets"

fail() { echo "::error::$*"; exit 1; }

echo "== enable arm64 binfmt =="
docker run --privileged --rm tonistiigi/binfmt --install arm64 || fail "binfmt 安装失败"

echo "== build arm64 rootfs =="
CID=$(docker run -d --platform linux/arm64 ubuntu:24.04 sleep 3600) || fail "启动 arm64 容器失败"
docker exec "$CID" bash -c "export DEBIAN_FRONTEND=noninteractive; apt-get update -qq; apt-get install -y -qq --no-install-recommends openjdk-21-jre-headless libcurl4t64 libssl3t64 ca-certificates; apt-get clean; rm -rf /var/lib/apt/lists/*" || {
  echo "::error::容器内 apt 安装失败"
  docker rm -f "$CID" >/dev/null 2>&1 || true
  exit 1
}
docker export "$CID" -o "$WORK/rootfs.tar" || fail "docker export 失败"
docker rm -f "$CID" >/dev/null 2>&1 || true

mkdir -p "$ROOTFS"
tar -xf "$WORK/rootfs.tar" -C "$ROOTFS"
test -x "$ROOTFS/usr/lib/jvm/java-21-openjdk-arm64/bin/java" || fail "rootfs 内没有 java"
"$ROOTFS/usr/lib/jvm/java-21-openjdk-arm64/bin/java" -version 2>/dev/null || true

echo "== MongoDB 7.0 =="
curl -L --retry 3 -o "$WORK/mongo.tgz" \
  https://fastdl.mongodb.org/linux/mongodb-linux-aarch64-ubuntu2204-7.0.14.tgz || fail "下载 MongoDB 失败"
mkdir -p "$ROOTFS/opt/mongodb"
tar -xzf "$WORK/mongo.tgz" -C "$ROOTFS/opt/mongodb" --strip-components=1
test -x "$ROOTFS/opt/mongodb/bin/mongod" || fail "MongoDB 解压异常"

echo "== grasscutter.jar =="
mkdir -p "$ROOTFS/opt/astaps/data/db" "$ROOTFS/opt/astaps/resources" "$ROOTFS/opt/astaps/logs"
curl -L --retry 3 -o "$WORK/grasscutter.jar" \
  https://github.com/MeChen618/AstaPS/releases/latest/download/grasscutter.jar || fail "下载 grasscutter.jar 失败"
test -s "$WORK/grasscutter.jar" || fail "grasscutter.jar 为空"
cp "$WORK/grasscutter.jar" "$ROOTFS/opt/astaps/grasscutter.jar"
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOTFS/etc/resolv.conf"

echo "== payload =="
PAYLOAD=$WORK/payload
mkdir -p "$PAYLOAD"
cp "$ROOT/payload/proot" "$PAYLOAD/proot"
cp "$ROOT/payload/libtalloc.so.2" "$PAYLOAD/libtalloc.so.2"
chmod 755 "$PAYLOAD/proot"
cp -a "$ROOTFS" "$PAYLOAD/rootfs"
tar -czf "$ROOT/app/src/main/assets/payload.tar.gz" -C "$PAYLOAD" .
cp "$ROOT/app/src/main/assets/payload.tar.gz" "$ROOT/app/src/main/assets/payload.bin"
rm -f "$ROOT/app/src/main/assets/payload.tar.gz"
ls -lh "$ROOT/app/src/main/assets/payload.bin"
