#!/bin/bash
# 快速重新安装脚本 - 解决 NoSuchMethodError


echo ""
echo "========================================"
echo "安装新版本..."
echo "========================================"
adb install app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

echo ""
echo "========================================"
echo "✅ 重新安装完成！"
echo "========================================"
echo ""
echo "现在可以运行测试了："
echo "  adb shell am instrument -w com.example.androidinstrumentedtest.test/androidx.test.runner.AndroidJUnitRunner"

