# ARC-DNA Home release rules: R8 shrinks, optimises and renames our own code so the shipped
# APK/AAB is hard to read, while keeping the parts the libraries reach by reflection or JNI.

# A little obfuscation dictionary noise and no line tables (no source insight from a stack trace).
-repackageclasses 'a'
-allowaccessmodification
-renamesourcefileattribute SourceFile

# Keep native-method names and anything tagged @Keep (so JNI and keep-annotated code still binds).
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Our Activities/Services/Receivers are kept by the manifest; our views are built in code only.

# Google ML Kit (face, object, labeling) loads detectors and models by reflection.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# MediaPipe Tasks (gaze landmarker) uses protobuf + reflection + native handles.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# CameraX binds use-cases and the camera2 pipeline through generated/reflected classes.
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Guava ListenableFuture (used by CameraX / ML Kit task bridges).
-dontwarn com.google.common.**

# Keep generic signatures and annotations ML Kit / protobuf inspect at runtime.
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod
