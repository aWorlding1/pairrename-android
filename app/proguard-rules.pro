# 本项目 release 构建不启用代码压缩（build.gradle.kts 中 isMinifyEnabled = false），
# 因此下面的规则默认不会生效；保留它们是为了：万一后续开启 minify，
# 也能直接构建成功而不用临时补规则。
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# DataStore / kotlinx 序列化相关
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn androidx.datastore.preferences.protobuf.**

# Coil 通过反射查找组件时保留必要的构造器信息
-keepclassmembers class coil.** { *; }
-dontwarn coil.**

# SAF / DocumentsContract 全部走系统 API，无需保留任何模型类
-keep class com.yuanbao.pairrename.model.** { *; }
