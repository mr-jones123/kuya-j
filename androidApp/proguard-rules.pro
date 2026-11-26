# LiteRT-LM calls into Kotlin classes from JNI and reads them with Gson.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
