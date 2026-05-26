# Project ProGuard rules. AGP applies sensible defaults for Compose, Kotlin
# metadata, kotlinx-serialization and AndroidX; this file adds rules for the
# third-party libs that this app actually pulls in. Minification is OFF by
# default in app/build.gradle.kts; flip isMinifyEnabled=true on release when
# you want a smaller APK and these rules will be in effect.

# --- sshj ---------------------------------------------------------------
# sshj uses reflection to load algorithm factories from
# net.schmizz.sshj.transport.* and resolves bouncycastle providers by name.
-keep class net.schmizz.sshj.** { *; }
-keep interface net.schmizz.sshj.** { *; }
-keep class com.hierynomus.** { *; }
-keep interface com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-keep interface org.bouncycastle.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn com.hierynomus.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**

# --- Room ---------------------------------------------------------------
# Generated *_Impl classes are loaded by name.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}

# --- Kotlin coroutines --------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory
-dontwarn kotlinx.coroutines.**

# --- EncryptedSharedPreferences (Tink) ---------------------------------
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
