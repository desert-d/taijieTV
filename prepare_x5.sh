#!/bin/sh
# 把 X5 内核包放进 assets，构建出的 APK 才自带内核（不用联网下载 36MB）
# 用法：sh prepare_x5.sh
set -e
cd "$(dirname "$0")"
mkdir -p app/src/main/assets
if [ -f x5/assets/045738_x5.tbs.apk ]; then
    cp x5/assets/045738_x5.tbs.apk app/src/main/assets/
    echo "已内置 X5 内核（32 位 045738，适配 RK3229）"
else
    echo "缺少 x5/assets/045738_x5.tbs.apk"
    echo "请从原仓库根目录的 X5.zip 解压，或下载后放到该路径"
    exit 1
fi
ls -lh app/src/main/assets
