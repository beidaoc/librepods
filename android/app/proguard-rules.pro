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

-keep class me.kavishdevar.librepods.utils.KotlinModule { *; }

# Xiaomi Bluetooth Extension / AirCore conflict protection. The module entry and
# resolver names are loaded by LSPosed and reflection, so R8 must retain them.
-keep,allowoptimization class me.kavishdevar.librepods.xiaomifix.XiaomiFixModule {
    <init>();
    public <methods>;
}
-keep class me.kavishdevar.librepods.xiaomifix.resolve.** { *; }
-keep class org.luckypray.dexkit.** { *; }
-keepattributes InnerClasses,EnclosingMethod,Signature,Exceptions
-dontwarn org.luckypray.dexkit.**
