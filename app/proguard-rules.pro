# ONNX Runtime uses JNI + reflection
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Our JNI entry points are resolved by name from native code
-keepclasseswithmembernames class ai.hindidub.engine.WhisperBridge { native <methods>; }
-keepclasseswithmembernames class ai.hindidub.engine.SentencePieceTokenizer { native <methods>; }
-keep class ai.hindidub.engine.WhisperBridge { *; }
-keep class ai.hindidub.engine.SentencePieceTokenizer { *; }
