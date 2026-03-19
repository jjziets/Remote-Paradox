-keepattributes *Annotation*
-keep class com.remoteparadox.watch.data.** { *; }
-keep class retrofit2.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
