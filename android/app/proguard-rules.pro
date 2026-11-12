# Libraries ship their own consumer rules (Anthropic SDK, Media3, ML Kit). Only optional JVM-only references remain.
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn java.lang.management.**
-keepattributes SourceFile,LineNumberTable
