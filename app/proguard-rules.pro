# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Coil
-dontwarn coil.**

# Hilt
-keep class dagger.hilt.** { *; }

# ZXing / ZXingLite（条码解析依赖反射与运行时类）
-keep class com.google.zxing.** { *; }
-keep class com.king.zxing.** { *; }
-keep class com.king.camera.scan.** { *; }
-dontwarn com.google.zxing.**
