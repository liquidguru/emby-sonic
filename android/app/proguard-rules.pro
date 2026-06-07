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
