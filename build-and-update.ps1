# ========================================================
#   OfflineChat - Automated Build & In-App OTA Publisher
# ========================================================

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "  OfflineChat - Automated Build & OTA Publisher (Tarika 2) " -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host ""

# 1. Compile APK
Write-Host "[1/3] Compiling latest APK with Gradle..." -ForegroundColor Yellow
$gradleResult = Start-Process -FilePath ".\gradlew.bat" -ArgumentList "assembleDebug" -NoNewWindow -Wait -PassThru

if ($gradleResult.ExitCode -ne 0) {
    Write-Host "[ERROR] Gradle build failed with exit code $($gradleResult.ExitCode)!" -ForegroundColor Red
    exit $gradleResult.ExitCode
}

# 2. Update update.json metadata
Write-Host ""
Write-Host "[2/3] Updating update.json metadata..." -ForegroundColor Yellow

$apkPath = "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apkPath) {
    $file = Get-Item $apkPath
    $size = $file.Length
    
    # Get current Wi-Fi IP address
    $wifiIp = (Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias "Wi-Fi" -ErrorAction SilentlyContinue).IPAddress
    if (-not $wifiIp) {
        $wifiIp = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object {$_.InterfaceAlias -notmatch "Loopback|vEthernet|WSL"} | Select-Object -First 1).IPAddress
    }
    if (-not $wifiIp) {
        $wifiIp = "10.33.160.61"
    }

    $timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    
    $updateData = [ordered]@{
        versionCode   = 3
        versionName   = "1.0.2"
        apkUrl        = "http://${wifiIp}:8080/app-debug.apk"
        fileSizeBytes = $size
        changelog     = "Auto-updated from laptop at $timestamp"
        updatedAt     = $timestamp
    }

    $jsonContent = $updateData | ConvertTo-Json -Depth 3
    $destPath = "$PSScriptRoot\app\build\outputs\apk\debug\update.json"
    [System.IO.File]::WriteAllText($destPath, $jsonContent, [System.Text.UTF8Encoding]::new($false))
    
    Write-Host "[SUCCESS] update.json updated successfully!" -ForegroundColor Green
    Write-Host "          APK Size: $([math]::Round($size / 1MB, 2)) MB" -ForegroundColor Gray
    Write-Host "          OTA URL:  http://${wifiIp}:8080/update.json" -ForegroundColor Gray
} else {
    Write-Host "[WARNING] APK file not found at $apkPath" -ForegroundColor Red
}

# 3. Ensure local HTTP server is running
Write-Host ""
Write-Host "[3/3] Checking local download server on port 8080..." -ForegroundColor Yellow
$activeConn = Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue

if (-not $activeConn) {
    Write-Host "[INFO] Starting background HTTP server on port 8080..." -ForegroundColor Cyan
    Start-Process python -ArgumentList "-m http.server 8080 --directory `"app\build\outputs\apk\debug`"" -WindowStyle Hidden
    Start-Sleep -Seconds 2
} else {
    Write-Host "[OK] HTTP server is actively running on port 8080" -ForegroundColor Green
}

Write-Host ""
Write-Host "========================================================" -ForegroundColor Green
Write-Host "  Done! Ab mobile me OfflineChat kholiye:               " -ForegroundColor Green
Write-Host "  App me automatic popup aayega:                        " -ForegroundColor Green
Write-Host "  'New Update Available - Update Now'                   " -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Green
