# JNI: нативный код вызывает эти методы по имени.
-keep class com.izrecorder.transcribe.WhisperLib { *; }
-keep interface com.izrecorder.transcribe.WhisperLib$ProgressListener { *; }
-keepclasseswithmembernames class * { native <methods>; }
