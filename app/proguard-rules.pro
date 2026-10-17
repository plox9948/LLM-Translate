# ONNX Runtime и sherpa-onnx: JNI C++ (FindClass/GetFieldID) обращается к этим
# классам и полям по исходным именам — запрещаем R8 вырезать и переименовывать.
-keep class ai.onnxruntime.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }

# kotlinx.serialization: сгенерированные сериализаторы.
-keepclassmembers class com.plox.llmtranslate.data.catalog.** {
    *** Companion;
}
-keepclasseswithmembers class com.plox.llmtranslate.data.catalog.** {
    kotlinx.serialization.KSerializer serializer(...);
}
