#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

DEPLOY_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
BACKEND_DIR=$(cd -- "$DEPLOY_DIR/.." && pwd -P)
WEB_DIR=${WORDTRAIL_WEB_DIR:-"$BACKEND_DIR/../wordtrail-web"}
CONFIG="$DEPLOY_DIR/.env"
WEB_REPO=https://github.com/KokoHere02/wordtrail-web.git

die() { printf '\n错误：%s\n' "$*" >&2; exit 1; }
for command in docker git openssl curl; do
  command -v "$command" >/dev/null 2>&1 || die "缺少命令：$command"
done
if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  die '需要 Docker Compose 插件或 docker-compose。'
fi
docker info >/dev/null 2>&1 || die '无法连接 Docker，请检查服务和当前用户的 Docker 权限。'

ask_default() {
  local answer
  read -r -p "$1 [$2]: " answer
  printf -v "$3" '%s' "${answer:-$2}"
}
valid_name() { [[ $1 =~ ^[a-zA-Z0-9_.-]+$ ]]; }
valid_port() { [[ $1 =~ ^[0-9]{1,5}$ ]] && (( 10#$1 >= 1 && 10#$1 <= 65535 )); }

if [[ ! -f $CONFIG ]]; then
  [[ -t 0 ]] || die '首次运行需要交互终端，用于配置现有 MySQL。'
  printf '首次配置：使用已有的 MySQL 容器、数据库和专用账号。\n'
  ask_default 'MySQL 容器名' 'shop-system-db' mysql_host
  ask_default '共享 Docker 网络' 'wordtrail-db' mysql_network
  ask_default '数据库名' 'word_trail_db' mysql_database
  ask_default '数据库用户名' 'wordtrail' mysql_user
  ask_default '公网访问端口' '8080' web_port
  ask_default '后端本机端口' '8081' app_port
  for value in "$mysql_host" "$mysql_network" "$mysql_database" "$mysql_user"; do
    valid_name "$value" || die '容器、网络、数据库及用户名只能包含字母、数字、点、下划线和连字符。'
  done
  valid_port "$web_port" && valid_port "$app_port" || die '端口必须是 1 到 65535 之间的数字。'
  (( 10#$web_port != 10#$app_port )) || die '公网端口与后端本机端口不能相同。'
  read -r -s -p 'MySQL 专用账号密码（不显示）: ' mysql_password; printf '\n'
  [[ -n $mysql_password && $mysql_password != *$'\r'* ]] || die '密码不能为空或包含换行。'
  # Compose dotenv single quotes protect $, # and spaces; escape quote and backslash.
  escaped=${mysql_password//\\/\\\\}; escaped=${escaped//\'/\\\'}
  jwt_secret=$(openssl rand -base64 32)
  {
    printf 'MYSQL_HOST=%s\nMYSQL_NETWORK=%s\nMYSQL_DATABASE=%s\nMYSQL_USER=%s\n' "$mysql_host" "$mysql_network" "$mysql_database" "$mysql_user"
    printf "MYSQL_PASSWORD='%s'\nJWT_SECRET_BASE64='%s'\n" "$escaped" "$jwt_secret"
    printf 'WEB_PORT=%s\nAPP_PORT=%s\n' "$web_port" "$app_port"
    printf 'CORS_ALLOWED_ORIGINS=http://127.0.0.1:5173,http://localhost:5173\nSENTENCE_EDITOR_USER_IDS=\n'
  } > "$CONFIG"
  unset escaped jwt_secret
  printf '配置已保存到 %s（仅当前用户可读写），后续运行会保留 JWT 密钥。\n' "$CONFIG"
fi

config_value() { awk -v key="$1" 'index($0,key "=")==1 { print substr($0,length(key)+2); exit }' "$CONFIG"; }
mysql_host=$(config_value MYSQL_HOST); mysql_network=$(config_value MYSQL_NETWORK)
mysql_database=$(config_value MYSQL_DATABASE); mysql_user=$(config_value MYSQL_USER)
web_port=$(config_value WEB_PORT); app_port=$(config_value APP_PORT)
for value in "$mysql_host" "$mysql_network" "$mysql_database" "$mysql_user"; do
  valid_name "$value" || die "请检查 $CONFIG 中的 MySQL 容器、网络、数据库和用户名。"
done
valid_port "$web_port" && valid_port "$app_port" || die "请检查 $CONFIG 中的端口。"
(( 10#$web_port != 10#$app_port )) || die '公网端口与后端本机端口不能相同。'
[[ -f $BACKEND_DIR/src/main/resources/db/init.sql ]] || die '缺少后端 init.sql。'
docker inspect --type container "$mysql_host" >/dev/null 2>&1 || die "找不到 MySQL 容器 $mysql_host。"
[[ $(docker inspect -f '{{.State.Running}}' "$mysql_host") == true ]] || die "MySQL 容器 $mysql_host 未运行。"

if [[ ! -e $WEB_DIR ]]; then
  printf '克隆前端仓库到 %s ...\n' "$WEB_DIR"
  git clone "$WEB_REPO" "$WEB_DIR"
fi
[[ -f $WEB_DIR/package-lock.json && -f $WEB_DIR/package.json ]] || die "前端目录无效：$WEB_DIR"
WEB_DIR=$(cd -- "$WEB_DIR" && pwd -P)
export WORDTRAIL_WEB_DIR="$WEB_DIR" WORDTRAIL_DEPLOY_DIR="$DEPLOY_DIR"
# Use the saved deployment configuration consistently even if the caller has
# unrelated variables with the same names in their shell environment.
unset MYSQL_HOST MYSQL_NETWORK MYSQL_DATABASE MYSQL_USER MYSQL_PASSWORD JWT_SECRET_BASE64 APP_PORT WEB_PORT CORS_ALLOWED_ORIGINS SENTENCE_EDITOR_USER_IDS
COMPOSE+=(--project-directory "$BACKEND_DIR" --env-file "$CONFIG" -f "$BACKEND_DIR/docker-compose.yml" -f "$DEPLOY_DIR/docker-compose.web.yml")
"${COMPOSE[@]}" config --quiet

printf '\n检查数据库；只有空库可以导入 init.sql，现有数据不会被覆盖。\n'
if [[ -z ${mysql_password:-} ]]; then
  read -r -s -p "请输入 $mysql_user 的 MySQL 密码（不显示）: " mysql_password; printf '\n'
fi
[[ -n $mysql_password ]] || die '数据库密码不能为空。'
mysql=(docker exec -i -e "MYSQL_PWD=$mysql_password" "$mysql_host" mysql --protocol=tcp --host=127.0.0.1 --user="$mysql_user" --database="$mysql_database" --default-character-set=utf8mb4 --batch --skip-column-names)
if ! table_count=$("${mysql[@]}" --execute='SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()'); then
  unset mysql_password mysql
  die '连接数据库失败，请检查容器、数据库名、账号和权限。'
fi
[[ $table_count =~ ^[0-9]+$ ]] || die '无法确认数据库是否为空，已停止初始化。'
if (( table_count == 0 )); then
  read -r -p "确认向空库 $mysql_database 导入项目初始化结构？输入 YES 继续: " confirm
  if [[ $confirm == YES ]]; then
    if ! "${mysql[@]}" < "$BACKEND_DIR/src/main/resources/db/init.sql"; then
      unset mysql_password mysql
      die '导入失败，数据库可能已部分初始化；请先人工检查，脚本不会覆盖非空库。'
    fi
    table_count=$("${mysql[@]}" --execute='SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()')
    [[ $table_count == 25 ]] || die "初始化后的表数量为 $table_count，预期 25，请检查数据库。"
    printf '数据库初始化完成：25 张表。\n'
  else
    unset mysql_password mysql
    die '未确认初始化，启动已取消。'
  fi
elif (( table_count < 25 )); then
  die "数据库已有 $table_count 个表或视图，可能是其他库或初始化不完整；请人工检查，脚本不会覆盖。"
else
  printf '数据库包含 %s 个表或视图，跳过导入，保留现有数据。\n' "$table_count"
fi
unset mysql_password mysql

if ! docker network inspect "$mysql_network" >/dev/null 2>&1; then
  docker network create "$mysql_network" >/dev/null
fi
if ! docker network inspect -f '{{range .Containers}}{{println .Name}}{{end}}' "$mysql_network" | grep -Fxq -- "$mysql_host"; then
  docker network connect "$mysql_network" "$mysql_host"
fi

printf '\n构建并启动前后端，首次构建需要下载镜像和依赖。\n'
"${COMPOSE[@]}" up -d --build
for ((i=0; i<36; i++)); do
  if curl -fsS --max-time 3 "http://127.0.0.1:$app_port/health/ready" >/dev/null && curl -fsS --max-time 3 "http://127.0.0.1:$web_port/" >/dev/null; then
    printf '\n部署完成：浏览器访问 http://服务器公网IP:%s\n' "$web_port"
    printf '查看服务：'; printf '%q ' "${COMPOSE[@]}"; printf 'ps\n'
    exit 0
  fi
  sleep 5
done
"${COMPOSE[@]}" ps
die '服务尚未就绪，请检查上面的状态及 compose 日志。数据库不会被脚本删除。'
