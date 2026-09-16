# MovieBox Downloader — ProGuard / R8 rules
# Release minification is enabled; keep these or the app will break.

# --- kotlinx.serialization (data classes, JSON) ---
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.moviebox.downloader.**$$serializer { *; }

# --- TFLite + our image/text analyzers ---
-keep class org.tensorflow.lite.** { *; }
-keep class com.moviebox.downloader.util.NsfwDetector { *; }
-keep class com.moviebox.downloader.util.ImageNsfwAnalyzer { *; }
-keep class com.moviebox.downloader.util.ContentAnalyzer { *; }
-keep class com.moviebox.downloader.util.ContentFilter { *; }
-keepclassmembers class com.moviebox.downloader.util.NsfwDetector { *; }

# --- Compose (Material 3) ---
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# --- ViewModel / Lifecycle ---
-keep class androidx.lifecycle.** { *; }
-keep class com.moviebox.downloader.MainViewModel { *; }

# --- Coil (image loading) ---
-keep class coil.** { *; }
-dontwarn coil.**

# --- OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# --- Keep our domain data classes (serialization + StateFlow) ---
-keep class com.moviebox.downloader.api.** { *; }
-keep class com.moviebox.downloader.data.** { *; }
