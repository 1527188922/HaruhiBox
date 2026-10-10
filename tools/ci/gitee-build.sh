#!/bin/sh
# Gitee Go 构建脚本：编 debug 包（并可选发布到 Gitee 发行版）。
#
# 为什么放在脚本里而不是流水线 YAML 的 commands 里：
# 1) YAML 的普通标量（plain scalar）里 ": " 是非法的——之前把 `cut -d: -f2`
#    直接写进 commands，冒号后面跟了空格，Gitee 的 YAML 解析器报
#    "mapping values are not allowed here"；
# 2) 长 shell 一行还牵扯引号、超长行的可读性问题；放进版本库的脚本
#    改起来有 diff，也能本地先跑一遍。
#
# 流水线里只需要一行：sh tools/ci/gitee-build.sh
#
# 需要的流水线变量（都在「变量」里配，不配也能跑）：
#   GITEE_TOKEN  私人令牌（勾选 projects 权限）。不配 / 还是占位符 = 跳过发布
#   RELEASE_TAG  指定版本号，默认 v1.0.<构建号>-debug
#
# 想改成正式包：把下面的 :app:assembleDebug 换成 :app:assembleRelease，
# 并按 docs/BUILD.md 在流水线变量里补 KEYSTORE_BASE64 等四个变量（见 README 里的说明）。

set -e

# 让脚本从仓库根目录开始跑（本地 dry-run 也成立）
cd "$(dirname "$0")/../.."

echo "==> 1/4 准备构建环境"
# 构建机是 Linux，没有本机那个固定路径的 JDK：删掉 org.gradle.java.home 让 Gradle 用 JAVA_HOME
# （与 .github/workflows/android-ci.yml 的处理一致）
sed -i '/org.gradle.java.home/d' gradle.properties
# gradlew 在仓库里没有可执行位（Windows 提交的）
chmod +x gradlew

echo "==> 2/4 构建 debug 包"
# debug 由 Gradle 自动生成的 debug keystore 签名，不需要 keystore.properties
./gradlew --no-daemon :app:assembleDebug

echo "==> 3/4 整理产物"
APK=$(ls app/build/outputs/apk/debug/*.apk 2>/dev/null | head -n 1 || true)
if [ -z "$APK" ]; then
  echo "没有找到 debug APK，中止"
  exit 1
fi
TAG="${RELEASE_TAG:-}"
if [ -z "$TAG" ]; then
  TAG="v1.0.${GITEE_PIPELINE_BUILD_NUMBER}-debug"
fi
OUT="HaruhiBox-${TAG}.apk"
cp "$APK" "$OUT"
ls -lh "$OUT"

echo "==> 4/4 发布到 Gitee 发行版（可选）"
TOKEN="${GITEE_TOKEN:-}"
if [ -z "$TOKEN" ] || [ "$TOKEN" = "PASTE_YOUR_GITEE_TOKEN_HERE" ]; then
  echo "未配置 GITEE_TOKEN，跳过发布；APK 见本次构建的制品/归档"
  exit 0
fi

# 仓库的 owner/repo 直接从 git remote 里取，不用另配变量
REMOTE=$(git config --get remote.origin.url)
OWNER=$(echo "$REMOTE" | sed -E 's#.*[:/]([^/]+)/[^/]+(\.git)?$#\1#')
REPO=$(echo "$REMOTE" | sed -E 's#.*/([^/]+)(\.git)?$#\1#')
API="https://gitee.com/api/v5/repos/${OWNER}/${REPO}"

# 同 tag 的发行版已存在就复用，否则按当前提交新建（debug 包标记为预发布）
RELEASE_ID=$(curl -s "${API}/releases/tags/${TAG}?access_token=${TOKEN}" \
  | grep -o '"id":[0-9]*' | head -n 1 | cut -d: -f2)
if [ -z "$RELEASE_ID" ]; then
  RELEASE_ID=$(curl -s -X POST "${API}/releases" \
    --data-urlencode "access_token=${TOKEN}" \
    --data-urlencode "tag_name=${TAG}" \
    --data-urlencode "name=HaruhiBox ${TAG}" \
    --data-urlencode "body=$(git log -1 --pretty=%s)" \
    --data-urlencode "target_commitish=$(git rev-parse HEAD)" \
    --data-urlencode "prerelease=true" \
    | grep -o '"id":[0-9]*' | head -n 1 | cut -d: -f2)
fi
if [ -z "$RELEASE_ID" ]; then
  echo "发行版创建/查找失败：检查 GITEE_TOKEN 是否有 projects 权限"
  exit 1
fi

curl -s -X POST "${API}/releases/${RELEASE_ID}/attach_files" \
  -F "access_token=${TOKEN}" \
  -F "file=@${OUT}"
echo
echo "已发布：${TAG}（附件 ${OUT}）"