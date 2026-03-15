# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# CameraX uses reflection internally
-keep class androidx.camera.** { *; }

# Keep Compose-related metadata
-dontwarn androidx.compose.**

# Keep Accompanist permissions
-dontwarn com.google.accompanist.**
