# Libraries ship their own consumer rules (Anthropic SDK, Media3, ML Kit). Only optional JVM-only references remain.
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn java.lang.management.**
-keepattributes SourceFile,LineNumberTable

# In-app yt-dlp (youtubedl-android) ships no shrinker rules and reads JSON into its own classes by reflection
# (updates, video info). Keep it and its helpers whole.
-keep class com.yausername.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
