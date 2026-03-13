-keepattributes *Annotation*

# kotlinx.serialization core
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }

# Keep all data classes and their serializers
-keep class com.remoteparadox.app.data.** { *; }
-keepclassmembers class com.remoteparadox.app.data.** {
    *** Companion;
    *** serializer(...);
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep generated serializers
-keep,includedescriptorclasses class com.remoteparadox.app.data.**$$serializer { *; }

# Retrofit + OkHttp
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
