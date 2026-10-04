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

# 枚举名会被持久化：HistoryRepository 从历史 JSON 读 Side.valueOf，
# TemplateRepository 从模板文本读 BatchMode/CaseOp。混淆或裁剪掉常量会让它们
# 在运行时读不到（且是静默走默认值，不会报错 —— 属于最难查的一类坏）。
# model 包已被整包 keep，这里是第二道保险，也覆盖将来移出 model 的枚举。
-keepclassmembers enum com.yuanbao.pairrename.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
