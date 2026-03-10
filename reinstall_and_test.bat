@echo off
echo ========================================
echo 完全重新安装并运行测试
echo ========================================
echo.


echo.
echo [4/6] 编译主应用和测试应用...
call gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
if %ERRORLEVEL% NEQ 0 (
    echo   编译失败！
    pause
    exit /b 1
)
echo   编译完成

echo.
echo [5/6] 安装到设备...
adb install app\build\outputs\apk\debug\app-debug.apk
if %ERRORLEVEL% NEQ 0 (
    echo   主应用安装失败！
    pause
    exit /b 1
)
echo   主应用安装成功

adb install app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
if %ERRORLEVEL% NEQ 0 (
    echo   测试应用安装失败！
    pause
    exit /b 1
)
echo   测试应用安装成功

echo.
echo [6/6] 运行测试...
adb shell am instrument -w -r -e debug false -e class com.example.androidinstrumentedtest.KeyboardEvaluationTest com.example.androidinstrumentedtest.test/androidx.test.runner.AndroidJUnitRunner

echo.
echo ========================================
echo 测试完成！
echo ========================================
echo.
echo 查看失败测试的调试文件:
echo   adb pull /sdcard/Android/data/com.example.androidinstrumentedtest/files/test_debug ./test_debug
echo.
pause


