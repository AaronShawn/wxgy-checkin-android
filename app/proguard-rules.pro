# OkHttp 可选依赖，缺失时不影响运行
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**

# 保留对外暴露的入口
-keep class com.wxgy.checkin.MainActivity { *; }
-keep class com.wxgy.checkin.LoginActivity { *; }
-keep class com.wxgy.checkin.CameraActivity { *; }
