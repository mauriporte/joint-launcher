<#
  Signs Joint Launcher in to YouTube (Google) or Spotify, once, from this PC.

  Opens the sign-in page in your browser, catches the redirect on http://127.0.0.1:8888/callback,
  swaps the code for a refresh token and copies it into Joint Launcher's private storage over adb.
  The token goes straight from the PC to the TV and is never shown on screen.

  Usage:
    .\connect.ps1 -Service spotify
    .\connect.ps1 -Service youtube
    .\connect.ps1 -GrantNowPlaying     # lets Joint Launcher see what's playing (notification access)

  Needs tools\connect.config.json (copy connect.config.example.json) and the TV connected via adb.
#>
param(
    [ValidateSet('youtube', 'spotify', 'jellyseerr')] [string] $Service,
    [switch] $GrantNowPlaying,
    [string] $Adb = 'C:\adb\adb.exe'
)
$ErrorActionPreference = 'Stop'
$Pkg = 'com.mau.hub'
$Redirect = 'http://127.0.0.1:8888/callback'

if ($GrantNowPlaying) {
    & $Adb shell cmd notification allow_listener "$Pkg/$Pkg.NowPlayingListener"
    Write-Host 'Notification access granted to Joint Launcher (Now Playing tile).'
    if (-not $Service) { & $Adb shell am start -S -n "$Pkg/.MainActivity" | Out-Null; return }
}
if (-not $Service) { Write-Host 'Pass -Service youtube|spotify|jellyseerr and/or -GrantNowPlaying'; return }

$cfgPath = Join-Path $PSScriptRoot 'connect.config.json'
if (-not (Test-Path $cfgPath)) { throw "Missing $cfgPath - copy connect.config.example.json and fill it in." }
$cfg = Get-Content $cfgPath -Raw | ConvertFrom-Json

# Copies a small JSON file into Joint Launcher's private storage (debug build), which imports and deletes it on start.
function Send-ToTv([string] $name, [hashtable] $payload) {
    $tmp = [IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllText($tmp, ($payload | ConvertTo-Json -Compress))
        & $Adb push $tmp "/data/local/tmp/hub_$name.json" | Out-Null
        & $Adb shell "run-as $Pkg sh -c 'mkdir -p files && cp /data/local/tmp/hub_$name.json files/$name.json'"
        & $Adb shell rm "/data/local/tmp/hub_$name.json"
    } finally { Remove-Item $tmp -Force }
    & $Adb shell am start -S -n "$Pkg/.MainActivity" | Out-Null
}

if ($Service -eq 'jellyseerr') {
    # Address + API key (Jellyseerr > Settings > General > API Key). Asked here if they aren't in the config file.
    $url = if ($cfg.jellyseerrUrl) { $cfg.jellyseerrUrl } else { Read-Host 'Jellyseerr address (e.g. https://jellyseerr.example.com)' }
    $url = $url.TrimEnd('/')
    $key = if ($cfg.jellyseerrApiKey) { $cfg.jellyseerrApiKey } else { Read-Host 'Jellyseerr API key (leave empty to sign in with your Jellyfin login instead)' }
    if (-not $key) {
        # Some builds don't show the key in Settings > General; the admin can still read it from the API after signing in.
        $user = Read-Host 'Jellyfin username (a Jellyseerr admin)'
        $secure = Read-Host 'Jellyfin password' -AsSecureString
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
        try {
            # same body Jellyseerr's own login page sends (it repeats the username as email; without it the server errors)
            $body = @{ username = $user; password = $plain; email = $user } | ConvertTo-Json
            Invoke-RestMethod -Method Post -Uri "$url/api/v1/auth/jellyfin" -Body $body -ContentType 'application/json' -SessionVariable seerr | Out-Null
            $key = (Invoke-RestMethod -Uri "$url/api/v1/settings/main" -WebSession $seerr).apiKey
            Invoke-RestMethod -Method Post -Uri "$url/api/v1/auth/logout" -WebSession $seerr | Out-Null
        } finally { $plain = $null }
        if (-not $key) { throw 'Signed in, but Jellyseerr returned no API key (is this user an admin?)' }
    }
    $me = Invoke-RestMethod -Uri "$url/api/v1/auth/me" -Headers @{ 'X-Api-Key' = $key } # fails loudly if address or key are wrong
    Send-ToTv 'jellyseerr' @{ url = $url; api_key = $key }
    Write-Host "Jellyseerr connected (as $($me.displayName)). Joint Launcher restarted on the TV."
    return
}

function B64Url([byte[]] $b) { [Convert]::ToBase64String($b).TrimEnd('=').Replace('+', '-').Replace('/', '_') }
$verifierBytes = New-Object byte[] 48
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($verifierBytes)
$verifier = B64Url $verifierBytes
$challenge = B64Url ([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier)))
$state = B64Url ([guid]::NewGuid().ToByteArray())
$enc = { param($s) [Uri]::EscapeDataString($s) }

if ($Service -eq 'spotify') {
    $clientId = $cfg.spotifyClientId; $secret = $null
    $scope = 'user-follow-read user-library-read playlist-read-private user-top-read'
    $authUrl = "https://accounts.spotify.com/authorize?response_type=code&client_id=$(& $enc $clientId)&scope=$(& $enc $scope)" +
        "&redirect_uri=$(& $enc $Redirect)&code_challenge_method=S256&code_challenge=$challenge&state=$state"
    $tokenUrl = 'https://accounts.spotify.com/api/token'
} else {
    $clientId = $cfg.googleClientId; $secret = $cfg.googleClientSecret
    $scope = 'https://www.googleapis.com/auth/youtube.readonly'
    $authUrl = "https://accounts.google.com/o/oauth2/v2/auth?response_type=code&client_id=$(& $enc $clientId)&scope=$(& $enc $scope)" +
        "&redirect_uri=$(& $enc $Redirect)&code_challenge_method=S256&code_challenge=$challenge&state=$state&access_type=offline&prompt=consent"
    $tokenUrl = 'https://oauth2.googleapis.com/token'
}
if (-not $clientId) { throw "Client ID for $Service is empty in connect.config.json" }

# Raw TCP listener: HttpListener would need a URL ACL / admin rights
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 8888)
$listener.Start()
try {
    Write-Host "Opening the $Service sign-in page in your browser..."
    Start-Process $authUrl
    $client = $listener.AcceptTcpClient()
    $stream = $client.GetStream()
    $reader = [IO.StreamReader]::new($stream)
    $requestLine = $reader.ReadLine()   # GET /callback?code=...&state=... HTTP/1.1
    $query = ($requestLine -split ' ')[1] -replace '^[^?]*\?', ''
    $params = @{}
    foreach ($kv in $query -split '&') { $k, $v = $kv -split '=', 2; $params[$k] = [Uri]::UnescapeDataString($v) }
    $ok = $params.code -and $params.state -eq $state
    $html = if ($ok) { 'Joint Launcher is connected. You can close this tab.' } else { 'Sign-in failed or was cancelled. Check the terminal.' }
    $bytes = [Text.Encoding]::UTF8.GetBytes("HTTP/1.1 200 OK`r`nContent-Type: text/html; charset=utf-8`r`nConnection: close`r`n`r`n<h2>$html</h2>")
    $stream.Write($bytes, 0, $bytes.Length); $stream.Flush(); $client.Close()
    if (-not $ok) { throw "No authorization code (error: $($params.error))" }
} finally { $listener.Stop() }

$form = @{ grant_type = 'authorization_code'; code = $params.code; redirect_uri = $Redirect; client_id = $clientId; code_verifier = $verifier }
if ($secret) { $form.client_secret = $secret }
$tok = Invoke-RestMethod -Method Post -Uri $tokenUrl -Body $form -ContentType 'application/x-www-form-urlencoded'
if (-not $tok.refresh_token) { throw 'No refresh token returned. For Google, remove Joint Launcher at myaccount.google.com/permissions and retry.' }

$svc = if ($Service -eq 'youtube') { 'google' } else { 'spotify' }
$payload = @{ client_id = $clientId; refresh_token = $tok.refresh_token }
if ($secret) { $payload.client_secret = $secret }
$tmp = [IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllText($tmp, ($payload | ConvertTo-Json -Compress))
    & $Adb push $tmp "/data/local/tmp/hub_$svc.json" | Out-Null
    # run-as works because Joint Launcher is a debug build; the app imports and deletes the file on next start
    & $Adb shell "run-as $Pkg sh -c 'mkdir -p files && cp /data/local/tmp/hub_$svc.json files/$svc.json'"
    & $Adb shell rm "/data/local/tmp/hub_$svc.json"
} finally { Remove-Item $tmp -Force }

& $Adb shell am start -S -n "$Pkg/.MainActivity" | Out-Null
Write-Host "$Service connected. Joint Launcher restarted on the TV."
