# 启动测试，com.example.androidinstrumentedtest/是你应用的测试包名和测试运行器
adb shell am instrument -w com.example.androidinstrumentedtest.test/androidx.test.runner.AndroidJUnitRunner