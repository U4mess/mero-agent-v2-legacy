# Mero Agent release rules

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JNI: llama.cpp native methods and the callback class are resolved by name.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.u4mess.ume.brain.NativeLlama { *; }
-keep class com.u4mess.ume.brain.TokenSink { *; }
-keepclassmembers class com.u4mess.ume.brain.TokenSink {
    boolean emit(java.lang.String);
}

# ObjectBox entities and generated cursor/meta classes.
-keep class io.objectbox.** { *; }
-keep @io.objectbox.annotation.Entity class * { *; }
-keep class **_ { *; }
-keep class **.*_ { *; }
-keepclassmembers class * {
    @io.objectbox.annotation.* <fields>;
}
-dontwarn io.objectbox.**

# Chat persistence, attachment, backup and JSON model classes.
-keep class com.u4mess.ume.brain.ChatSession { *; }
-keep class com.u4mess.ume.brain.ChatController$Message { *; }
-keep class com.u4mess.ume.brain.ChatAttachment { *; }
-keep enum com.u4mess.ume.brain.ChatAttachmentKind { *; }
-keep class com.u4mess.ume.brain.UiAgentResult { *; }
-keep class com.u4mess.ume.brain.AgentStep { *; }
-keep class com.u4mess.ume.net.WebResult { *; }
-keep class com.u4mess.ume.memory.MemoryEntity { *; }
-keep class com.u4mess.ume.memory.MemoryBackupManager { *; }
-keep class com.u4mess.ume.memory.MemoryRepository { *; }

# PDFBox-Android and optional logging/XML backends.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**
-dontwarn org.apache.pdfbox.**
-dontwarn org.apache.commons.logging.**
-dontwarn org.slf4j.**
-dontwarn javax.**

# ML Kit text recognition.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# OkHttp (consumer rules normally cover this; keep release builds tolerant).
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
