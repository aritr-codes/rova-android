# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Attributes required by Kotlin reflection and Compose stability inference
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations

# Kotlin metadata (required for Kotlin reflection and Compose @Stable inference)
-keep class kotlin.Metadata { *; }

# CameraX uses reflection internally (existing rule, retained)
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Compose: warning suppression + keep any project-defined Saver implementations
# (used by rememberSaveable). Compose runtime ships its own consumer rules for
# its internal Savers — this rule covers ours.
-dontwarn androidx.compose.**
-keep,includedescriptorclasses class * implements androidx.compose.runtime.saveable.Saver {
    public protected *;
}

# Accompanist (existing rule, retained)
-dontwarn com.google.accompanist.**

# Android Service Binder subclasses (e.g. RovaRecordingService.LocalBinder) are
# accessed via ServiceConnection / IPC and not as direct Kotlin references.
# Manifest-declared Service/Activity classes are auto-kept by AGP, but nested
# Binder subclasses are not.
-keep public class * extends android.os.Binder

# FileProvider authority is referenced by string in AndroidManifest.xml. AGP
# keeps the class via the manifest, but a defensive keep is cheap and survives
# any future build-config drift.
-keep class androidx.core.content.FileProvider

# kotlinx.coroutines, AndroidX Lifecycle, AndroidX Navigation, and the Compose
# runtime ship their own consumer-rules.pro — do NOT duplicate their keeps
# here; they version with the library.

# MediaMuxer / MediaExtractor / MediaCodec are framework classes; the app does
# not subclass them, so no app-side keep rules are required.
