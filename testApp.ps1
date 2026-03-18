# Run Android instrumented tests via adb
# Usage: .\testApp.ps1 [-TestClass "com.example.Test.method"] [-UseTesseract "true|false"]

param(
    [string]$TestClass,
    [string]$UseTesseract = "auto"
)

$packageName = "com.example.androidinstrumentedtest.test"
$testRunner = "androidx.test.runner.AndroidJUnitRunner"

Write-Host "========================================" -ForegroundColor Green
Write-Host "Run Android Instrumented Tests" -ForegroundColor Green
Write-Host "Package: $packageName" -ForegroundColor Cyan
Write-Host "Runner : $testRunner" -ForegroundColor Cyan
Write-Host "OCR    : use_tesseract=$UseTesseract" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Green
Write-Host ""

# Clear logcat
& adb logcat -c
Start-Sleep -Milliseconds 500

Write-Host "Starting tests..." -ForegroundColor Yellow
Write-Host ""

# Build the adb command
$adbArgs = @("shell", "am", "instrument", "-w")

if ($UseTesseract -ne "auto") {
    $adbArgs += @("-e", "use_tesseract", $UseTesseract)
}

if ($TestClass) {
    $adbArgs += @("-e", "class", $TestClass)
}

$adbArgs += "$packageName/$testRunner"

# Run the test
$command = "adb " + ($adbArgs -join " ")
Write-Host "Running: $command" -ForegroundColor Yellow
$result = & adb @adbArgs 2>&1

# Display results
Write-Host "========================================" -ForegroundColor Green
Write-Host $result
Write-Host "========================================" -ForegroundColor Green
Write-Host ""

# Check results
if ($result -match "OK\s*\(\d+\s+test[s]?\)") {
    Write-Host "[PASS] Test succeeded" -ForegroundColor Green
    exit 0
} elseif ($result -match "FAILURE") {
    Write-Host "[FAIL] Test failed" -ForegroundColor Red
    exit 1
} else {
    Write-Host "[WARN] Test result unknown" -ForegroundColor Yellow
    exit 2
}


