# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class guru.liquid.embysonic.** {
    *** Companion;
}
-keepclasseswithmembers @kotlinx.serialization.Serializable class guru.liquid.embysonic.** {
    <fields>;
}

# Hilt / Dagger generated entry points and aggregating metadata.
-keep class dagger.hilt.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep class *_HiltModules_* { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }
-dontwarn dagger.hilt.**
-dontwarn javax.inject.**

# Media3 / ExoPlayer playback, sessions, and Cast integration.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Google Cast framework uses reflection across Play Services classes.
-keep class com.google.android.gms.cast.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.dynamic.** { *; }
-dontwarn com.google.android.gms.**

# Coil image loading components are referenced by generated Compose/runtime code.
-keep class coil.** { *; }
-dontwarn coil.**
