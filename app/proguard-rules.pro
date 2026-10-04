# JNI-мосты: не обрезать методы, вызываемые из нативного кода.
-keep class com.plox.llmtranslate.domain.runtime.NativeBridge { *; }

# kotlinx.serialization: сгенерированные сериализаторы.
-keepclassmembers class com.plox.llmtranslate.data.catalog.** {
    *** Companion;
}
-keepclasseswithmembers class com.plox.llmtranslate.data.catalog.** {
    kotlinx.serialization.KSerializer serializer(...);
}
