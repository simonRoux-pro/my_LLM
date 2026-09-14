# Rhino reaches for a couple of JVM-only classes on paths Android never takes.
# Without these the release build fails on warnings for classes that are not
# there and never called.
-dontwarn java.beans.**
-dontwarn java.lang.invoke.**
-dontwarn org.mozilla.javascript.tools.**

# Rhino resolves script objects reflectively; renaming them breaks skills at
# runtime in ways that only show up on release builds.
-keep class org.mozilla.javascript.** { *; }

# JNI binds by name. An obfuscated method name means UnsatisfiedLinkError.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class pro.simonroux.myllm.engine.local.LlamaNative { *; }

# kotlinx.serialization generates serializers that are looked up by type.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class pro.simonroux.myllm.**$$serializer { *; }
-keepclassmembers class pro.simonroux.myllm.** {
    *** Companion;
}
-keepclasseswithmembers class pro.simonroux.myllm.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp ships optional hooks for platforms Android does not have.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
