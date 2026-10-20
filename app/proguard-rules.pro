# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# ONNX Runtime：JNI 层（convertToTensorInfo / convertOrtValueToONNXValue 等）按类名与
# 方法名反射查找 Java 类，混淆改名后 GetMethodID 拿到 null 会直接 native abort
# （表现为 OrtSession.run 时 "java_class == null in call to GetMethodID"），必须整体保留。
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Coil
-dontwarn coil.**

# Hilt
-keep class dagger.hilt.** { *; }

# ZXing / ZXingLite（条码解析依赖反射与运行时类）
-keep class com.google.zxing.** { *; }
-keep class com.king.zxing.** { *; }
-keep class com.king.camera.scan.** { *; }
-dontwarn com.google.zxing.**

# kotlinx.serialization：R8 不能把生成的 serializer 与 @Serializable 类的字段名改掉，
# 否则 release 包在 decodeFromString 时会找不到字段（debug 包不复现）。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class * { *; }
