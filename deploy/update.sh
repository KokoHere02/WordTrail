#!/usr/bin/env bash
set -Eeuo pipefail

# Update an existing installation; deploy.sh remains responsible for the build
# and database connection checks. Neither repository's local changes are reset.
DEPLOY_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
BACKEND_DIR=$(cd -- "$DEPLOY_DIR/.." && pwd -P)
WEB_DIR=${WORDTRAIL_WEB_DIR:-"$BACKEND_DIR/../wordtrail-web"}

die() { printf '\n错误：%s\n' "$*" >&2; exit 1; }

command -v git >/dev/null 2>&1 || die '缺少 Git，请先安装 git。'
[[ -f "$DEPLOY_DIR/.env" ]] || die '找不到 deploy/.env；请先完成首次部署，更新脚本不会初始化数据库。'
[[ -d "$WEB_DIR" ]] || die "找不到前端目录：$WEB_DIR；可通过 WORDTRAIL_WEB_DIR 指定。"
WEB_DIR=$(cd -- "$WEB_DIR" && pwd -P)
[[ "$BACKEND_DIR" != "$WEB_DIR" ]] || die '前后端目录不能相同。'

check_repo() {
  local path=$1 label=$2 root branch status
  root=$(git -C "$path" rev-parse --show-toplevel 2>/dev/null) || die "$label不是 Git 仓库：$path"
  root=$(cd -- "$root" && pwd -P) || die "无法访问$label仓库根目录。"
  [[ "$root" == "$path" ]] || die "$label目录不是仓库根目录：$path"
  branch=$(git -C "$path" symbolic-ref --quiet --short HEAD) || die "$label处于游离 HEAD 状态，请先切换到 main。"
  [[ "$branch" == main ]] || die "$label当前分支为 $branch，请先切换到 main。"
  git -C "$path" remote get-url origin >/dev/null 2>&1 || die "$label没有配置 origin 远程仓库。"
  status=$(git -C "$path" status --porcelain --untracked-files=no) || die "无法检查$label工作区状态。"
  if [[ -n "$status" ]]; then
    printf '\n%s存在已跟踪文件的未提交修改：\n%s\n' "$label" "$status" >&2
    die '请先自行处理这些修改，脚本不会覆盖或自动暂存。'
  fi
}

# Check both before changing either checkout. Untracked files are left intact;
# Git itself will refuse the pull if an untracked file would be overwritten.
check_repo "$BACKEND_DIR" '后端仓库'
check_repo "$WEB_DIR" '前端仓库'

printf '\n更新后端 main ...\n'
git -C "$BACKEND_DIR" -c http.version=HTTP/1.1 pull --ff-only origin main \
  || die '后端拉取失败，已停止部署；请检查网络或分支状态。'
printf '\n更新前端 main ...\n'
git -C "$WEB_DIR" -c http.version=HTTP/1.1 pull --ff-only origin main \
  || die '前端拉取失败，已停止部署；后端可能已更新，处理后可重新运行本脚本。'

printf '\n后端版本：'; git -C "$BACKEND_DIR" log -1 --oneline
printf '前端版本：'; git -C "$WEB_DIR" log -1 --oneline
[[ -f "$BACKEND_DIR/deploy/deploy.sh" ]] || die '更新后的后端缺少 deploy/deploy.sh。'
export WORDTRAIL_WEB_DIR="$WEB_DIR"
printf '\n开始构建并启动服务；如提示请输入现有 MySQL 专用账号密码。\n'
exec bash "$BACKEND_DIR/deploy/deploy.sh"
