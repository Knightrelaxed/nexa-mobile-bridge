# N.E.X.A Mobile Bridge - R8 / ProGuard Release Rules
# Keeps reflection-used models for kotlinx.serialization, Room, Hilt, OkHttp, and AndroidX Biometric.

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.nexa.mobilebridge.**$$serializer { *; }
-keepclassmembers class com.nexa.mobilebridge.** {
    *** Companion;
}
-keepclasseswithmembers class com.nexa.mobilebridge.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room entities and DAOs
-keep class com.nexa.mobilebridge.data.local.** { *; }

# Keep custom Android entry points
-keep class com.nexa.mobilebridge.NexaApplication { *; }
-keep class com.nexa.mobilebridge.MainActivity { *; }
-keep class com.nexa.mobilebridge.NexaBridgeService { *; }
-keep class com.nexa.mobilebridge.service.** { *; }
-keep class com.nexa.mobilebridge.ui.call.FakeCallActivity { *; }
-keep class com.nexa.mobilebridge.core.receiver.** { *; }

# OkHttp optional platform classes
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
