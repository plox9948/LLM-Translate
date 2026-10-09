# kotlinx.serialization: сгенерированные сериализаторы.
-keepclassmembers class com.plox.llmtranslate.data.catalog.** {
    *** Companion;
}
-keepclasseswithmembers class com.plox.llmtranslate.data.catalog.** {
    kotlinx.serialization.KSerializer serializer(...);
}
