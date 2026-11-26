#requires -Version 5.1
<#
.SYNOPSIS
    AgentChat-v2 运维脚本（Windows）

.DESCRIPTION
    用法：.\agentchat.ps1 <命令> [参数]
      start | stop | restart | status | health | verify | logs [n] | tail | env | help

    配置：同目录 env.conf（与 Linux 脚本共用同一份；可用 -EnvFile 覆盖）。
    退出码：0 成功/健康；1 失败；2 服务在跑但依赖降级。
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)][string]$Command = 'help',
    [Parameter(Position = 1)][string]$Arg,
    [string]$EnvFile
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $EnvFile) { $EnvFile = Join-Path $ScriptDir 'env.conf' }
$script:EnvFile = $EnvFile
$script:Conf = @{}

function Write-Info { param([string]$Msg) Write-Host "[INFO] $Msg" -ForegroundColor Green }
function Write-Warn { param([string]$Msg) Write-Host "[WARN] $Msg" -ForegroundColor Yellow }
function Write-Err  { param([string]$Msg) Write-Host "[FAIL] $Msg" -ForegroundColor Red }
function Write-Line { param([string]$Msg) Write-Host $Msg }
function Die { param([string]$Msg) Write-Err $Msg; exit 1 }

function Get-Conf {
    param([string]$Key, [string]$Default = '')
    if ($script:Conf.ContainsKey($Key) -and -not [string]::IsNullOrEmpty($script:Conf[$Key])) {
        return $script:Conf[$Key]
    }
    return $Default
}

function Load-Conf {
    if (-not (Test-Path -LiteralPath $script:EnvFile)) {
        Die "找不到配置文件：$($script:EnvFile)（先执行 Copy-Item env.conf.example env.conf）"
    }
    $script:Conf = @{}
    foreach ($raw in (Get-Content -LiteralPath $script:EnvFile -Encoding UTF8)) {
        $line = $raw.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith('#')) { continue }
        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { continue }
        $k = $line.Substring(0, $idx).Trim()
        $v = $line.Substring($idx + 1).Trim()
        if ($v.Length -ge 2 -and (($v.StartsWith('"') -and $v.EndsWith('"')) -or ($v.StartsWith("'") -and $v.EndsWith("'")))) {
            $v = $v.Substring(1, $v.Length - 2)
        }
        $script:Conf[$k] = $v
    }

    # 导出到当前进程环境，供 java 子进程继承
    foreach ($k in @($script:Conf.Keys)) {
        $v = $script:Conf[$k]
        if (-not [string]::IsNullOrEmpty($v)) { Set-Item -Path "env:$k" -Value $v }
    }

    $appHome = Get-Conf 'APP_HOME' '..'
    if (-not [IO.Path]::IsPathRooted($appHome)) { $appHome = Join-Path $ScriptDir $appHome }
    if (-not (Test-Path -LiteralPath $appHome)) { Die "APP_HOME 不存在：$appHome" }
    $script:AppHome = (Resolve-Path -LiteralPath $appHome).Path

    $jar = Get-Conf 'JAR' 'target/agentchat-v2-2.1.0.jar'
    $script:JarPath = if ([IO.Path]::IsPathRooted($jar)) { $jar } else { Join-Path $script:AppHome $jar }

    $logDir = Get-Conf 'LOG_DIR' 'logs'
    $script:LogPath = if ([IO.Path]::IsPathRooted($logDir)) { $logDir } else { Join-Path $script:AppHome $logDir }

    $pidFile = Get-Conf 'PID_FILE' 'run/agentchat.pid'
    $script:PidPath = if ([IO.Path]::IsPathRooted($pidFile)) { $pidFile } else { Join-Path $script:AppHome $pidFile }

    $script:ConsolePath = Join-Path $script:LogPath (Get-Conf 'CONSOLE_LOG' 'console.log')
    $script:ErrPath = Join-Path $script:LogPath 'console.err.log'
    $script:Port = [int](Get-Conf 'SERVER_PORT' '8092')
    $script:StartTimeout = [int](Get-Conf 'START_TIMEOUT' '120')
    $script:StopTimeout = [int](Get-Conf 'STOP_TIMEOUT' '30')
    $script:BaseUrl = Get-Conf 'AI_BASE_URL' ''
}

# ------------------------------------------------------------
# 进程 / 端口 / 健康
# ------------------------------------------------------------
function Get-PidFromFile {
    if (-not (Test-Path -LiteralPath $script:PidPath)) { return 0 }
    $t = Get-Content -LiteralPath $script:PidPath -Raw -ErrorAction SilentlyContinue
    if (-not $t) { return 0 }
    $digits = ($t -replace '\D', '')
    if (-not $digits) { return 0 }
    return [int]$digits
}

function Test-PidAlive {
    param([int]$SvcPid)
    if ($SvcPid -le 0) { return $false }
    return [bool](Get-Process -Id $SvcPid -ErrorAction SilentlyContinue)
}

function Test-PidIsOurs {
    param([int]$SvcPid)
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$SvcPid" -ErrorAction SilentlyContinue
    if (-not $proc) { return $false }
    $leaf = Split-Path -Leaf $script:JarPath
    return ($proc.CommandLine -like "*$leaf*")
}

function Test-PortOpen {
    param([int]$Port)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(1000)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Get-HealthRaw {
    try {
        $resp = Invoke-WebRequest -Uri "http://127.0.0.1:$($script:Port)/api/health" -TimeoutSec 5 -UseBasicParsing
        return $resp.Content
    } catch {
        return $null
    }
}

function Get-HealthJson {
    $raw = Get-HealthRaw
    if (-not $raw) { return $null }
    try { return $raw | ConvertFrom-Json } catch { return $null }
}

function Get-Prop {
    param($Obj, [string]$Name)
    if ($null -eq $Obj) { return '' }
    $p = $Obj.PSObject.Properties[$Name]
    if ($null -eq $p) { return '' }
    return [string]$p.Value
}

function Get-AgentScopeVersion {
    if (-not (Test-Path -LiteralPath $script:JarPath)) { return '' }
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
        $zip = [IO.Compression.ZipFile]::OpenRead($script:JarPath)
        try {
            $entry = $zip.Entries | Where-Object { $_.FullName -match 'agentscope-core-\d+\.\d+\.\d+\.jar$' } | Select-Object -First 1
            if ($entry) {
                return [regex]::Match($entry.FullName, 'agentscope-core-(\d+\.\d+\.\d+)\.jar').Groups[1].Value
            }
        } finally { $zip.Dispose() }
    } catch {
        return ''
    }
    return ''
}

function Test-VerGe {
    param([string]$Ver, [string]$Min)
    try { return ([version]$Ver -ge [version]$Min) } catch { return $true }
}

# ------------------------------------------------------------
# 子命令
# ------------------------------------------------------------
function Invoke-Start {
    if (-not (Test-Path -LiteralPath $script:JarPath)) {
        Die "找不到 jar：$($script:JarPath)（先执行 mvn -DskipTests package，或检查 env.conf 的 JAR）"
    }
    $svcPid = Get-PidFromFile
    if (Test-PidAlive $svcPid) {
        Write-Info "服务已在运行（PID $svcPid），无需重复启动"
        return 0
    }
    if ($svcPid -gt 0) { Write-Warn "清理残留 PID 文件（进程 $svcPid 已不存在）" }
    Remove-Item -LiteralPath $script:PidPath -Force -ErrorAction SilentlyContinue

    if (Test-PortOpen $script:Port) {
        Die "端口 $($script:Port) 已被占用，请先排查：netstat -ano | findstr :$($script:Port)"
    }

    New-Item -ItemType Directory -Force -Path $script:LogPath | Out-Null
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $script:PidPath) | Out-Null

    $javaOpts = @()
    $optsRaw = Get-Conf 'JAVA_OPTS' ''
    if ($optsRaw) { $javaOpts = @($optsRaw -split '\s+' | Where-Object { $_ }) }

    $springArgs = @()
    $profile = Get-Conf 'SPRING_PROFILES' ''
    if ($profile) { $springArgs += "--spring.profiles.active=$profile" }

    $javaBin = Get-Conf 'JAVA_BIN' 'java'
    $allArgs = @($javaOpts) + @('-jar', $script:JarPath) + $springArgs

    Write-Info "启动：$javaBin $($javaOpts -join ' ') -jar $($script:JarPath) $($springArgs -join ' ')"
    $proc = Start-Process -FilePath $javaBin -ArgumentList $allArgs -WorkingDirectory $script:AppHome `
        -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $script:ConsolePath -RedirectStandardError $script:ErrPath
    Set-Content -LiteralPath $script:PidPath -Value $proc.Id -Encoding ASCII

    $waited = 0
    while ($waited -lt $script:StartTimeout) {
        if (-not (Test-PidAlive $proc.Id)) {
            Write-Err "进程已退出，启动失败。最近日志："
            Show-Tail $script:ConsolePath 40
            Remove-Item -LiteralPath $script:PidPath -Force -ErrorAction SilentlyContinue
            return 1
        }
        if (Get-HealthRaw) {
            Write-Info "启动成功：PID $($proc.Id)，端口 $($script:Port)，耗时 ${waited}s"
            return 0
        }
        Start-Sleep -Seconds 2
        $waited += 2
    }

    Write-Err "启动超时：$($script:StartTimeout)s 内 /api/health 未通过。最近日志："
    Show-Tail $script:ConsolePath 40
    return 1
}

function Show-Tail {
    param([string]$Path, [int]$Lines = 40)
    if (-not (Test-Path -LiteralPath $Path)) { Write-Warn "日志文件不存在：$Path"; return }
    Get-Content -LiteralPath $Path -Tail $Lines -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $_" }
}

function Invoke-Stop {
    $svcPid = Get-PidFromFile
    if (-not (Test-PidAlive $svcPid)) {
        Write-Info "服务未运行"
        Remove-Item -LiteralPath $script:PidPath -Force -ErrorAction SilentlyContinue
        return 0
    }
    if (-not (Test-PidIsOurs $svcPid)) {
        Die "PID $svcPid 不是本服务进程（PID 可能被复用），已中止。请人工确认后再处理"
    }

    Write-Info "请求关闭 PID $svcPid（不带 /F），最多等待 $($script:StopTimeout)s"
    & taskkill /PID $svcPid 2>&1 | Out-Null

    $waited = 0
    while ((Test-PidAlive $svcPid) -and ($waited -lt $script:StopTimeout)) {
        Start-Sleep -Seconds 1
        $waited += 1
    }
    if (Test-PidAlive $svcPid) {
        Write-Warn "优雅停机超时，执行 taskkill /F"
        & taskkill /PID $svcPid /F 2>&1 | Out-Null
        Start-Sleep -Seconds 2
    }
    if (Test-PidAlive $svcPid) { Die "无法停止 PID $svcPid" }

    Remove-Item -LiteralPath $script:PidPath -Force -ErrorAction SilentlyContinue
    Write-Info "已停止（PID $svcPid）"
    return 0
}

function Invoke-Restart {
    Invoke-Stop | Out-Null
    Start-Sleep -Seconds 2
    return (Invoke-Start)
}

function Invoke-Status {
    $svcPid = Get-PidFromFile
    $running = $false
    if (Test-PidAlive $svcPid) {
        $running = $true
        $p = Get-Process -Id $svcPid
        Write-Line "运行状态 : 运行中（PID $svcPid，启动于 $($p.StartTime.ToString('yyyy-MM-dd HH:mm:ss'))）"
    } else {
        Write-Line "运行状态 : 未运行"
        if (Test-Path -LiteralPath $script:PidPath) { Write-Line "           （存在残留 PID 文件：$($script:PidPath)）" }
    }

    if (Test-PortOpen $script:Port) { Write-Line "端口     : $($script:Port) 监听中" }
    else { Write-Line "端口     : $($script:Port) 未监听" }

    if (-not $running) { return 1 }

    $h = Get-HealthJson
    if ($null -eq $h) {
        Write-Line "健康检查 : 不可达（服务进程在，但 /api/health 无响应）"
        return 1
    }
    $status = Get-Prop $h 'status'
    Write-Line "健康检查 : status=$status redis=$(Get-Prop $h 'redis') redisSearch=$(Get-Prop $h 'redisSearch') model=$(Get-Prop $h 'model')"
    if ($status -eq 'up') { return 0 }
    return 2
}

function Invoke-Health {
    $raw = Get-HealthRaw
    if (-not $raw) {
        Write-Err "健康检查不可达：http://127.0.0.1:$($script:Port)/api/health"
        return 1
    }
    Write-Line $raw
    $h = Get-HealthJson
    $status = Get-Prop $h 'status'
    if ($status -eq 'up') { Write-Info 'status=up'; return 0 }
    Write-Warn "status=$status（服务在跑，但依赖降级；看 redis / redisSearch / model 字段）"
    return 2
}

function Invoke-Probe {
    param([string]$Url, [string]$Key, [string]$Model)
    $body = '{"model":"' + $Model + '","messages":[{"role":"user","content":"ping"}],"max_tokens":1,"stream":false}'
    $headers = @{ 'Content-Type' = 'application/json' }
    if ($Key) { $headers['Authorization'] = "Bearer $Key" }
    $code = 0
    $content = ''
    try {
        $resp = Invoke-WebRequest -Uri $Url -Method Post -Body $body -Headers $headers -TimeoutSec 8 -UseBasicParsing
        $code = [int]$resp.StatusCode
        $content = [string]$resp.Content
    } catch {
        $ex = $_.Exception
        $r = $null
        if ($ex.PSObject.Properties['Response']) { $r = $ex.Response }
        if ($r) {
            try { $code = [int]$r.StatusCode } catch { $code = 0 }
            try {
                $stream = $r.GetResponseStream()
                if ($stream) { $reader = New-Object IO.StreamReader($stream); $content = $reader.ReadToEnd(); $reader.Dispose() }
            } catch { }
        } else {
            $content = $ex.Message
        }
    }
    return [pscustomobject]@{ Code = $code; Content = $content }
}

function Invoke-Verify {
    $fail = 0
    Write-Line '== 1. 配置 =='
    Write-Line "  jar        : $($script:JarPath)"
    Write-Line "  base-url   : $(if ($script:BaseUrl) { $script:BaseUrl } else { '<未配置>' })"
    Write-Line "  端口       : $($script:Port)"
    Write-Line "  profile    : $(Get-Conf 'SPRING_PROFILES' '<默认>')"

    $ver = Get-AgentScopeVersion
    Write-Line "  agentscope : $(if ($ver) { $ver } else { '未识别（jar 未构建）' })"

    if ($script:BaseUrl -like 'http://*') {
        Write-Line '  scheme     : http（明文）'
    } elseif ($script:BaseUrl -like 'https://*') {
        Write-Line '  scheme     : https（ALPN 协商 HTTP/2，不受 h2c 缺陷影响）'
    } else {
        Write-Warn '  AI_BASE_URL 未配置或格式异常'
        $fail = 1
    }

    if ($ver -and $script:BaseUrl -like 'http://*' -and -not (Test-VerGe $ver '2.0.4')) {
        Write-Warn "  明文 http + AgentScope $ver：命中 h2c 丢请求体缺陷（需 >= 2.0.4）"
        $fail = 1
    }

    Write-Line ''
    Write-Line '== 2. 网关探针 =='
    if ($script:BaseUrl) {
        $url = $script:BaseUrl.TrimEnd('/')
        if ($url -match '/v1$') { $url = "$url/chat/completions" } else { $url = "$url/v1/chat/completions" }
        $key = Get-Conf 'AI_API_KEY' ''
        $model = Get-Conf 'AI_CHAT_MODEL' 'deepseek-v4-pro'
        $probe = Invoke-Probe -Url $url -Key $key -Model $model
        Write-Line "  POST $url -> HTTP $($probe.Code)"
        switch ($probe.Code) {
            200 { Write-Info '  链路与鉴权正常' }
            201 { Write-Info '  链路与鉴权正常' }
            401 { Write-Warn '  鉴权失败：检查 AI_API_KEY'; $fail = 1 }
            403 { Write-Warn '  鉴权失败：检查 AI_API_KEY'; $fail = 1 }
            400 {
                Write-Warn '  400：网关返回空体/参数校验失败（典型 h2c 丢 body 症状），响应体：'
                Write-Line ("    " + $probe.Content.Substring(0, [Math]::Min(500, $probe.Content.Length)))
                $fail = 1
            }
            0 { Write-Warn "  连接失败：$($probe.Content)"; $fail = 1 }
            default {
                Write-Warn "  非预期状态码 $($probe.Code)，响应体："
                Write-Line ("    " + $probe.Content.Substring(0, [Math]::Min(500, $probe.Content.Length)))
                $fail = 1
            }
        }
    }

    Write-Line ''
    Write-Line '== 3. 应用日志 =='
    if (Test-Path -LiteralPath $script:ConsolePath) {
        $lines = Get-Content -LiteralPath $script:ConsolePath -Tail 200 -ErrorAction SilentlyContinue
        $hits = @($lines | Where-Object { $_ -like '*HTTP transport error during streaming*' })
        Write-Line "  近 200 行 'HTTP transport error during streaming' 次数：$($hits.Count)"
        if ($hits.Count -gt 0) {
            $last = $hits[-1]
            Write-Warn '  最近一条：'
            Write-Line ("    " + $last.Substring(0, [Math]::Min(300, $last.Length)))
            $fail = 1
        }
    } else {
        Write-Warn "  日志文件不存在：$($script:ConsolePath)（服务是否启动过？）"
    }

    Write-Line ''
    Write-Line '== 4. 本地健康 =='
    $raw = Get-HealthRaw
    if ($raw) {
        Write-Line "  $raw"
        $h = Get-HealthJson
        if ((Get-Prop $h 'status') -ne 'up') { Write-Warn '  status 非 up，依赖降级'; $fail = 1 }
    } else {
        Write-Warn '  /api/health 不可达'
        $fail = 1
    }

    Write-Line ''
    if ($fail -eq 0) { Write-Info '检查通过'; return 0 }
    Write-Err '检查未通过，见上面标记的条目'
    return 1
}

function Invoke-Logs {
    param([int]$Lines = 200)
    if (-not (Test-Path -LiteralPath $script:ConsolePath)) { Die "日志文件不存在：$($script:ConsolePath)" }
    Get-Content -LiteralPath $script:ConsolePath -Tail $Lines
}

function Invoke-Tail {
    if (-not (Test-Path -LiteralPath $script:ConsolePath)) { Die "日志文件不存在：$($script:ConsolePath)" }
    Write-Info "跟随 $($script:ConsolePath)（Ctrl+C 退出）"
    Get-Content -LiteralPath $script:ConsolePath -Wait -Tail 20
}

function Get-MaskedKey {
    param([string]$Key)
    if (-not $Key) { return '<未配置>' }
    if ($Key.Length -le 8) { return '****' }
    return $Key.Substring(0, 3) + '****' + $Key.Substring($Key.Length - 4)
}

function Invoke-Env {
    Write-Line "配置文件 : $($script:EnvFile)"
    Write-Line "APP_HOME : $($script:AppHome)"
    Write-Line "jar      : $($script:JarPath)"
    Write-Line "java     : $(Get-Conf 'JAVA_BIN' 'java')"
    Write-Line "JAVA_OPTS: $(Get-Conf 'JAVA_OPTS' '')"
    Write-Line "端口     : $($script:Port)"
    Write-Line "profile  : $(Get-Conf 'SPRING_PROFILES' '<默认>')"
    Write-Line "日志     : $($script:ConsolePath)"
    Write-Line "PID 文件 : $($script:PidPath)"
    Write-Line "AI_BASE_URL   : $(if ($script:BaseUrl) { $script:BaseUrl } else { '<未配置>' })"
    Write-Line "AI_API_KEY    : $(Get-MaskedKey (Get-Conf 'AI_API_KEY' ''))"
    Write-Line "AI_CHAT_MODEL : $(Get-Conf 'AI_CHAT_MODEL' '<未配置>')"
    Write-Line "REDIS    : $(Get-Conf 'REDIS_HOST' '127.0.0.1'):$(Get-Conf 'REDIS_PORT' '6379') db=$(Get-Conf 'REDIS_DB' '0')"
    Write-Line "NACOS    : $(Get-Conf 'NACOS_ENABLED' 'true')"
}

function Show-Help {
    Write-Host @'
AgentChat-v2 运维脚本（Windows）

用法：.\agentchat.ps1 <命令> [参数]

命令：
  start           后台启动服务，并等待 /api/health 就绪
  stop            请求关闭（taskkill 不带 /F），超时后强制结束
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

配置：同目录 env.conf（模板见 env.conf.example；可用 -EnvFile 覆盖路径）
注意：Windows 没有 SIGTERM，stop 的"优雅"程度取决于 JVM 收到 CTRL_CLOSE 的处理；
      会话状态以 Redis 为准，强杀不会丢会话。
'@
}

# ------------------------------------------------------------
# 入口
# ------------------------------------------------------------
try {
    switch ($Command.ToLower()) {
        'help' { Show-Help; exit 0 }
        '-h' { Show-Help; exit 0 }
        '--help' { Show-Help; exit 0 }
        'start' { Load-Conf; exit (Invoke-Start) }
        'stop' { Load-Conf; exit (Invoke-Stop) }
        'restart' { Load-Conf; exit (Invoke-Restart) }
        'status' { Load-Conf; exit (Invoke-Status) }
        'health' { Load-Conf; exit (Invoke-Health) }
        'verify' { Load-Conf; exit (Invoke-Verify) }
        'logs' {
            Load-Conf
            $n = 200
            if ($Arg) { $n = [int]$Arg }
            Invoke-Logs -Lines $n
            exit 0
        }
        'tail' { Load-Conf; Invoke-Tail; exit 0 }
        'env' { Load-Conf; Invoke-Env; exit 0 }
        default {
            Write-Err "未知命令：$Command"
            Write-Line ''
            Show-Help
            exit 2
        }
    }
} catch {
    Write-Err $_.Exception.Message
    exit 1
}
