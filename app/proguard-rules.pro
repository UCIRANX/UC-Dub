# Keep the app's own classes intact (the app uses reflection-like callbacks and the crash logger).
-keep class com.uciran.ucdub.** { *; }

# Stack traces saved by UcDubApp stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Libraries that reference optional classes.
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
