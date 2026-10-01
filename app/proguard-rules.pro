# ============================================================
#  SonnyLink 发布版混淆规则
# ============================================================

# ---- kotlinx.serialization ----
# 序列化依赖生成的 $$serializer 类和 Companion，混淆掉会运行时崩溃
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sonnyapp.**$$serializer { *; }
-keepclassmembers class com.sonnyapp.** {
    *** Companion;
}
-keepclasseswithmembers class com.sonnyapp.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- 保留行号，崩溃栈才有意义 ----
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ---- 我们自己协议层的模型类（比较脆，保守保留字段名） ----
-keep class com.sonnyapp.core.protocol.** { *; }
-keep class com.sonnyapp.core.liveview.** { *; }
-keep class com.sonnyapp.core.dlna.** { *; }
