#!/usr/bin/env bash
# ============================================================
# AgentChat-v2 运维脚本（Linux）
#
# 用法：./agentchat.sh <命令> [参数]
#   start | stop | restart | status | health | verify | logs [n] | tail | env | help
#
# 依赖：bash 4+、coreutils、curl（health/verify 需要）；ss 或 netstat 任一即可。
# 配置：同目录 env.conf（可用 ENV_FILE=/path/env.conf 覆盖）。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$SCRIPT_DIR/env.conf}"

if [ -t 1 ]; then
    C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_RST=$'\033[0m'
else
    C_RED=''; C_GRN=''; C_YEL=''; C_RST=''
fi

log()  { printf '%s\n' "$*"; }
info() { printf '%s %s\n' "${C_GRN}[INFO]${C_RST}" "$*"; }
warn() { printf '%s %s\n' "${C_YEL}[WARN]${C_RST}" "$*"; }
err()  { printf '%s %s\n' "${C_RED}[FAIL]${C_RST}" "$*" >&2; }
die()  { err "$*"; exit 1; }

# ------------------------------------------------------------
# 配置加载
# ------------------------------------------------------------
load_env() {
    [ -f "$ENV_FILE" ] || die "找不到配置文件：$ENV_FILE（先执行 cp env.conf.example env.conf）"
    # shellcheck disable=SC1090
    set -a; . "$ENV_FILE"; set +a

    : "${JAVA_BIN:=java}"
    : "${JAVA_OPTS:=}"
    : "${JAR:=target/agentchat-v2-2.1.0.jar}"
    : "${SERVER_PORT:=8092}"
    : "${SPRING_PROFILES:=}"
    : "${LOG_DIR:=logs}"
    : "${CONSOLE_LOG:=console.log}"
    : "${PID_FILE:=run/agentchat.pid}"
    : "${START_TIMEOUT:=120}"
    : "${STOP_TIMEOUT:=30}"
    : "${APP_HOME:=..}"

    case "$APP_HOME" in /*) ;; *) APP_HOME="$SCRIPT_DIR/$APP_HOME" ;; esac
    APP_HOME="$(cd "$APP_HOME" 2>/dev/null && pwd)" || die "APP_HOME 不存在：$APP_HOME"

    case "$JAR" in /*) JAR_PATH="$JAR" ;; *) JAR_PATH="$APP_HOME/$JAR" ;; esac
    case "$LOG_DIR" in /*) LOG_PATH="$LOG_DIR" ;; *) LOG_PATH="$APP_HOME/$LOG_DIR" ;; esac
    case "$PID_FILE" in /*) PID_PATH="$PID_FILE" ;; *) PID_PATH="$APP_HOME/$PID_FILE" ;; esac
    CONSOLE_PATH="$LOG_PATH/$CONSOLE_LOG"
    BASE_URL="${AI_BASE_URL:-}"
}

need_jar() {
    [ -f "$JAR_PATH" ] || die "找不到 jar：$JAR_PATH（先执行 mvn -DskipTests package，或检查 env.conf 的 JAR）"
}

# ------------------------------------------------------------
# 进程 / 端口 / 健康
# ------------------------------------------------------------
read_pid() {
    [ -f "$PID_PATH" ] || return 1
    local p
    p="$(tr -dc '0-9' < "$PID_PATH" || true)"
    [ -n "$p" ] || return 1
    printf '%s' "$p"
}

pid_alive() {
    local p="${1:-}"
    [ -n "$p" ] && kill -0 "$p" 2>/dev/null
}

pid_is_ours() {
    local p="$1" base
    base="$(basename "$JAR_PATH")"
    ps -p "$p" -o args= 2>/dev/null | grep -Fq -- "$base"
}

port_open() {
    local p="$1"
    if command -v ss >/dev/null 2>&1; then
        ss -ltn 2>/dev/null | awk 'NR>1 {print $4}' | grep -qE "[:.]${p}\$"
    elif command -v netstat >/dev/null 2>&1; then
        netstat -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${p}\$"
    else
        ( exec 3<>"/dev/tcp/127.0.0.1/$p" ) 2>/dev/null
    fi
}

health_body() {
    curl -fsS -m 5 "http://127.0.0.1:${SERVER_PORT}/api/health" 2>/dev/null
}

json_field() {
    local key="$1" body="$2"
    printf '%s' "$body" | tr -d '\r\n' \
        | grep -o "\"${key}\":\"[^\"]*\"\|\"${key}\":[^,}]*" | head -n1 \
        | sed 's/^"[^"]*"://; s/^"//; s/"$//' || true
}

uptime_of() {
    local p="$1"
    ps -p "$p" -o etime= 2>/dev/null | tr -d ' ' || true
}

jar_agentscope_version() {
    [ -f "$JAR_PATH" ] || return 1
    local raw=""
    if command -v unzip >/dev/null 2>&1; then
        raw="$(unzip -l "$JAR_PATH" 2>/dev/null | grep -o 'agentscope-core-[0-9][0-9.]*\.jar' | head -n1 || true)"
    elif command -v jar >/dev/null 2>&1; then
        raw="$(jar tf "$JAR_PATH" 2>/dev/null | grep -o 'agentscope-core-[0-9][0-9.]*\.jar' | head -n1 || true)"
    fi
    [ -n "$raw" ] || return 1
    printf '%s' "${raw#agentscope-core-}" | sed 's/\.jar$//'
}

# $1 >= $2 ?
ver_ge() {
    [ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n1)" = "$2" ]
}

# ------------------------------------------------------------
# 子命令
# ------------------------------------------------------------
cmd_start() {
    need_jar
    local pid
    pid="$(read_pid || true)"

    if [ -n "${pid:-}" ] && pid_alive "$pid"; then
        info "服务已在运行（PID $pid），无需重复启动"
        return 0
    fi
    if [ -n "${pid:-}" ]; then
        warn "清理残留 PID 文件（进程 $pid 已不存在）"
    fi
    rm -f "$PID_PATH"

    if port_open "$SERVER_PORT"; then
        die "端口 $SERVER_PORT 已被占用，请先排查：ss -ltnp | grep :$SERVER_PORT"
    fi
    command -v "$JAVA_BIN" >/dev/null 2>&1 || die "找不到 java 可执行文件：$JAVA_BIN"

    mkdir -p "$LOG_PATH" "$(dirname "$PID_PATH")"

    local spring_args=""
    if [ -n "${SPRING_PROFILES:-}" ]; then
        spring_args="--spring.profiles.active=$SPRING_PROFILES"
    fi

    info "启动：$JAVA_BIN $JAVA_OPTS -jar $JAR_PATH ${spring_args:-}"
    (
        cd "$APP_HOME" || exit 1
        # JAVA_OPTS / spring_args 需要按空格拆分，故意不加引号
        # shellcheck disable=SC2086
        nohup "$JAVA_BIN" $JAVA_OPTS -jar "$JAR_PATH" ${spring_args:-} \
            >> "$CONSOLE_PATH" 2>&1 &
        echo $! > "$PID_PATH"
    )

    local waited=0 cur
    while [ "$waited" -lt "$START_TIMEOUT" ]; do
        cur="$(read_pid || true)"
        if [ -z "${cur:-}" ] || ! pid_alive "$cur"; then
            err "进程已退出，启动失败。最近日志（$CONSOLE_PATH）："
            tail -n 40 "$CONSOLE_PATH" 2>/dev/null || true
            rm -f "$PID_PATH"
            return 1
        fi
        if health_body >/dev/null 2>&1; then
            info "启动成功：PID $cur，端口 $SERVER_PORT，耗时 ${waited}s"
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done

    err "启动超时：${START_TIMEOUT}s 内 /api/health 未通过。最近日志："
    tail -n 40 "$CONSOLE_PATH" 2>/dev/null || true
    return 1
}

cmd_stop() {
    local pid
    pid="$(read_pid || true)"

    if [ -z "${pid:-}" ] || ! pid_alive "$pid"; then
        info "服务未运行"
        rm -f "$PID_PATH"
        return 0
    fi
    if ! pid_is_ours "$pid"; then
        die "PID $pid 不是本服务进程（PID 可能被复用），已中止。请人工确认后再执行 kill $pid"
    fi

    info "发送 SIGTERM 到 PID $pid，最多等待 ${STOP_TIMEOUT}s"
    kill -TERM "$pid" 2>/dev/null || true

    local waited=0
    while pid_alive "$pid" && [ "$waited" -lt "$STOP_TIMEOUT" ]; do
        sleep 1
        waited=$((waited + 1))
    done

    if pid_alive "$pid"; then
        warn "优雅停机超时，执行 SIGKILL"
        kill -KILL "$pid" 2>/dev/null || true
        sleep 2
    fi
    if pid_alive "$pid"; then
        die "无法停止 PID $pid"
    fi

    rm -f "$PID_PATH"
    info "已停止（PID $pid）"
}

cmd_restart() {
    cmd_stop
    sleep 2
    cmd_start
}

cmd_status() {
    local pid running=1
    pid="$(read_pid || true)"

    if [ -n "${pid:-}" ] && pid_alive "$pid"; then
        running=0
        log "运行状态 : 运行中（PID $pid，已运行 $(uptime_of "$pid")）"
    else
        log "运行状态 : 未运行"
        [ -f "$PID_PATH" ] && log "           （存在残留 PID 文件：$PID_PATH）"
    fi

    if port_open "$SERVER_PORT"; then
        log "端口     : $SERVER_PORT 监听中"
    else
        log "端口     : $SERVER_PORT 未监听"
    fi

    if [ "$running" -eq 0 ]; then
        local body st
        if body="$(health_body)"; then
            st="$(json_field status "$body")"
            log "健康检查 : status=${st:-unknown} redis=$(json_field redis "$body") milvus=$(json_field milvus "$body") vectorStore=$(json_field vectorStore "$body") model=$(json_field model "$body")"
            if [ "${st:-}" = "up" ]; then
                return 0
            fi
            return 2
        fi
        log "健康检查 : 不可达（服务进程在，但 /api/health 无响应）"
        return 1
    fi

    return 1
}

cmd_health() {
    local body st
    if ! body="$(health_body)"; then
        err "健康检查不可达：http://127.0.0.1:${SERVER_PORT}/api/health"
        return 1
    fi
    printf '%s\n' "$body"
    st="$(json_field status "$body")"
    if [ "${st:-}" = "up" ]; then
        info "status=up"
        return 0
    fi
    warn "status=${st:-unknown}（服务在跑，但依赖降级；看 redis / milvus / model 字段）"
    return 2
}

cmd_verify() {
    local fail=0
    log "== 1. 配置 =="
    log "  jar        : $JAR_PATH"
    log "  base-url   : ${BASE_URL:-<未配置>}"
    log "  端口       : $SERVER_PORT"
    log "  profile    : ${SPRING_PROFILES:-<默认>}"

    local ver=""
    ver="$(jar_agentscope_version || true)"
    log "  agentscope : ${ver:-未识别（jar 未构建或缺少 unzip/jar 命令）}"

    case "$BASE_URL" in
        http://*)  log "  scheme     : http（明文）" ;;
        https://*) log "  scheme     : https（ALPN 协商 HTTP/2，不受 h2c 缺陷影响）" ;;
        *)         warn "  AI_BASE_URL 未配置或格式异常"; fail=1 ;;
    esac

    if [ -n "$ver" ] && [ "${BASE_URL#http://}" != "$BASE_URL" ] && ! ver_ge "$ver" "2.0.4"; then
        warn "  明文 http + AgentScope $ver：命中 h2c 丢请求体缺陷（需 ≥ 2.0.4）"
        fail=1
    fi

    log ""
    log "== 2. 网关探针 =="
    local url="${BASE_URL%/}"
    case "$url" in
        */v1) url="$url/chat/completions" ;;
        "")   url="" ;;
        *)    url="$url/v1/chat/completions" ;;
    esac
    if [ -n "$url" ]; then
        local code body_file
        body_file="$(mktemp "${TMPDIR:-/tmp}/agentchat-probe.XXXXXX")"
        code="$(curl -sS -m 8 -o "$body_file" -w '%{http_code}' -X POST "$url" \
            -H 'Content-Type: application/json' \
            -H "Authorization: Bearer ${AI_API_KEY:-}" \
            --data-binary "$(printf '{"model":"%s","messages":[{"role":"user","content":"ping"}],"max_tokens":1,"stream":false}' "${AI_CHAT_MODEL:-deepseek-v4-pro}")" \
            2>/dev/null || echo 000)"
        log "  POST $url -> HTTP $code"
        case "$code" in
            200|201) info "  链路与鉴权正常" ;;
            401|403) warn "  鉴权失败：检查 AI_API_KEY"; fail=1 ;;
            400)     warn "  400：网关返回空体/参数校验失败（典型 h2c 丢 body 症状），响应体："
                     head -c 500 "$body_file" 2>/dev/null | sed 's/^/    /' || true
                     fail=1 ;;
            000)     warn "  连接失败：检查网络、地址、端口"; fail=1 ;;
            *)       warn "  非预期状态码 $code，响应体："
                     head -c 500 "$body_file" 2>/dev/null | sed 's/^/    /' || true
                     fail=1 ;;
        esac
        rm -f "$body_file"
    fi

    log ""
    log "== 3. 应用日志 =="
    if [ -f "$CONSOLE_PATH" ]; then
        local cnt
        cnt="$(tail -n 200 "$CONSOLE_PATH" | grep -c 'HTTP transport error during streaming' || true)"
        log "  近 200 行 'HTTP transport error during streaming' 次数：${cnt:-0}"
        if [ "${cnt:-0}" -gt 0 ]; then
            warn "  最近一条："
            tail -n 200 "$CONSOLE_PATH" | grep 'HTTP transport error during streaming' | tail -n1 | cut -c1-300 | sed 's/^/    /' || true
            fail=1
        fi
    else
        warn "  日志文件不存在：$CONSOLE_PATH（服务是否启动过？）"
    fi

    log ""
    log "== 4. 本地健康 =="
    local body st
    if body="$(health_body)"; then
        st="$(json_field status "$body")"
        log "  $body"
        [ "${st:-}" = "up" ] || { warn "  status=${st:-unknown}，依赖降级"; fail=1; }
    else
        warn "  /api/health 不可达"
        fail=1
    fi

    log ""
    if [ "$fail" -eq 0 ]; then
        info "检查通过"
        return 0
    fi
    err "检查未通过，见上面标记的条目"
    return 1
}

cmd_logs() {
    local n="${1:-200}"
    [ -f "$CONSOLE_PATH" ] || die "日志文件不存在：$CONSOLE_PATH"
    tail -n "$n" "$CONSOLE_PATH"
}

cmd_tail() {
    [ -f "$CONSOLE_PATH" ] || die "日志文件不存在：$CONSOLE_PATH"
    info "跟随 $CONSOLE_PATH（Ctrl+C 退出）"
    tail -f "$CONSOLE_PATH"
}

mask_key() {
    local k="${1:-}"
    if [ -z "$k" ]; then printf '<未配置>'; return; fi
    if [ "${#k}" -le 8 ]; then printf '****'; else printf '%s****%s' "${k:0:3}" "${k: -4}"; fi
}

cmd_env() {
    log "配置文件 : $ENV_FILE"
    log "APP_HOME : $APP_HOME"
    log "jar      : $JAR_PATH"
    log "java     : $JAVA_BIN"
    log "JAVA_OPTS: $JAVA_OPTS"
    log "端口     : $SERVER_PORT"
    log "profile  : ${SPRING_PROFILES:-<默认>}"
    log "日志     : $CONSOLE_PATH"
    log "PID 文件 : $PID_PATH"
    log "AI_BASE_URL : ${AI_BASE_URL:-<未配置>}"
    log "AI_API_KEY  : $(mask_key "${AI_API_KEY:-}")"
    log "AI_CHAT_MODEL : ${AI_CHAT_MODEL:-<未配置>}"
    log "REDIS    : ${REDIS_HOST:-127.0.0.1}:${REDIS_PORT:-6379} db=${REDIS_DB:-0}"
    log "NACOS    : ${NACOS_ENABLED:-true}"
}

cmd_help() {
    cat <<'EOF'
AgentChat-v2 运维脚本

用法：./agentchat.sh <命令> [参数]

命令：
  start           后台启动服务，并等待 /api/health 就绪
  stop            SIGTERM 优雅停止，超时后 SIGKILL
  restart         停止后启动
  status          运行状态 / PID / 端口 / 健康摘要
  health          打印 /api/health 原始 JSON 并判定
  verify          排障：配置、网关探针、日志错误、健康检查
  logs [n]        查看控制台日志最后 n 行（默认 200）
  tail            实时跟随控制台日志
  env             打印生效配置（密钥脱敏）
  help            显示本帮助

退出码：
  0  成功 / 运行且健康
  1  失败（启动失败、停止失败、检查不通过、未运行）
  2  服务在跑但依赖降级（health=degraded）

配置：同目录 env.conf（模板见 env.conf.example；可用 ENV_FILE 覆盖路径）
EOF
}

# ------------------------------------------------------------
# 入口
# ------------------------------------------------------------
main() {
    local cmd="${1:-help}"
    [ $# -gt 0 ] && shift || true

    case "$cmd" in
        help|-h|--help) cmd_help ;;
        start|stop|restart|status|health|verify|logs|tail|env)
            load_env
            case "$cmd" in
                start)   cmd_start ;;
                stop)    cmd_stop ;;
                restart) cmd_restart ;;
                status)  cmd_status ;;
                health)  cmd_health ;;
                verify)  cmd_verify ;;
                logs)    cmd_logs "${1:-200}" ;;
                tail)    cmd_tail ;;
                env)     cmd_env ;;
            esac
            ;;
        *) err "未知命令：$cmd"; cmd_help; exit 2 ;;
    esac
}

main "$@"
