# 生成大系统 SSO 令牌（联调用）
#
# 用途：在没有真实大系统时，本地验证「大系统登录后免登录跳转」链路。
# 算法与后端 SsoTokenService 完全一致：Base64URL(payload) + "." + Base64URL(HMAC-SHA256(payload, secret))
#
# 示例：
#   $env:SSO_SHARED_SECRET = 'change-me'
#   ./scripts/gen-sso-token.ps1 -UserId erp-1001 -Name 张三 -Roles user,analyst -Domains default,equipment
#   然后浏览器打开：http://127.0.0.1:8092/?sso=<输出的令牌>

param(
    [Parameter(Mandatory = $true)][string]$UserId,
    [string]$Name = '',
    [string[]]$Roles = @(),
    [string[]]$Domains = @(),
    [string]$System = 'demo',
    [int]$Ttl = 3600,
    [string]$Secret = $env:SSO_SHARED_SECRET
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Secret)) {
    Write-Error '缺少共享密钥：设置环境变量 SSO_SHARED_SECRET，或用 -Secret 传入'
    exit 1
}
if ([string]::IsNullOrWhiteSpace($UserId)) {
    Write-Error 'UserId 不能为空'
    exit 1
}

$roleList = @($Roles) | ForEach-Object { $_ -split '[,;\s]+' } | Where-Object { $_ }
$domainList = @($Domains) | ForEach-Object { $_ -split '[,;\s]+' } | Where-Object { $_ }

$exp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() + [Math]::Max(1, $Ttl)
$payload = [ordered]@{
    u   = $UserId.Trim()
    n   = $Name
    r   = @($roleList)
    d   = @($domainList)
    sys = $System
    exp = $exp
}

$json = $payload | ConvertTo-Json -Compress -Depth 5
$body = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json)).TrimEnd('=').Replace('+', '-').Replace('/', '_')

$hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
$signature = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($body))
$sig = [Convert]::ToBase64String($signature).TrimEnd('=').Replace('+', '-').Replace('/', '_')

Write-Output ("{0}.{1}" -f $body, $sig)
