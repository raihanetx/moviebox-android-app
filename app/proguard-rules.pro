# MovieBox Downloader proguard rules
# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.moviebox.downloader.**$$serializer { *; }
-keepclassmembers class com.moviebox.downloader.** { *** Companion; }
-keepclasseswithmembers class com.moviebox.downloader.** { kotlinx.serialization.KSerializer serializer(...); }
