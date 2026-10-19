# ONNX Runtime и sherpa-onnx: JNI C++ (FindClass/GetFieldID) обращается к этим
# классам и полям по исходным именам — запрещаем R8 вырезать и переименовывать.
-keep class ai.onnxruntime.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }

# SlimtNative: JNI-обёртка движка slimt — методы связываются из C++
# (app/src/main/cpp/slimt_jni.cpp) по исходным именам класса и членов.
-keep class com.plox.llmtranslate.data.native_engine.SlimtNative { *; }

# kotlinx.serialization: сгенерированные сериализаторы — каталог моделей,
# формат резервных копий (data.backup) и мета импортных моделей (ModelImportMeta).
-keepclassmembers class com.plox.llmtranslate.data.catalog.** {
    *** Companion;
}
-keepclasseswithmembers class com.plox.llmtranslate.data.catalog.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.plox.llmtranslate.data.backup.** {
    *** Companion;
}
-keepclasseswithmembers class com.plox.llmtranslate.data.backup.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.plox.llmtranslate.domain.model.ModelImportMeta {
    *** Companion;
}
-keepclasseswithmembers class com.plox.llmtranslate.domain.model.ModelImportMeta {
    kotlinx.serialization.KSerializer serializer(...);
}
