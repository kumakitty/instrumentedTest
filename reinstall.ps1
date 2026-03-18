# Quick reinstall script - Fix NoSuchMethodError
Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "Installing new version..." -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
# Check if APK files exist
$mainApk = "app/build/outputs/apk/debug/app-debug.apk"
$testApk = "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if (-not (Test-Path $mainApk)) {
    Write-Host "ERROR: Main APK not found at $mainApk" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $testApk)) {
    Write-Host "ERROR: Test APK not found at $testApk" -ForegroundColor Red
    exit 1
}
# Install main APK
Write-Host "Installing main APK..." -ForegroundColor Cyan
& adb install $mainApk
if ($LASTEXITCODE -ne 0) {
    Write-Host "Failed to install main APK" -ForegroundColor Red
    exit 1
}
# Install test APK
Write-Host "Installing test APK..." -ForegroundColor Cyan
& adb install $testApk
if ($LASTEXITCODE -ne 0) {
    Write-Host "Failed to install test APK" -ForegroundColor Red
    exit 1
}
Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "✅ Reinstall completed!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "Now you can run tests:" -ForegroundColor Yellow
Write-Host "  adb shell am instrument -w com.example.androidinstrumentedtest.test/androidx.test.runner.AndroidJUnitRunner" -ForegroundColor Cyan
Write-Host ""
exit 0
