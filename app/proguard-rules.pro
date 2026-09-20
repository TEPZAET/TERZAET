# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# JNI_OnLoad in libsystem.so registers every NativeBridge native method by name
# (including jniclose, which Kotlin never calls); if R8 strips or renames one,
# RegisterNatives fails and the VPN service cannot start.
-keep class io.github.p1neapplexpress.openflux.NativeBridge {
    native <methods>;
}