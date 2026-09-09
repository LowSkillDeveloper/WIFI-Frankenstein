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

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively

-optimizations method/inlining/short

-optimizations !code/allocation/variable
-optimizations method/inlining/*,code/removal/exception

# Kotlin Serialization
-keepattributes *Annotation*, EnclosingMethod, Signature
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}
-keep,allowobfuscation class * implements kotlinx.serialization.KSerializer
-keepclassmembers class * {
    *** Companion;
}
-keep class **$$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <init>(...);
}
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable void write$Self(...);
}

# libsu
-keep class com.lsd.wififrankenstein.shell.ShellInitializer { *; }
-keep class com.topjohnwu.superuser.** { *; }

# Osmdroid
-keep class org.osmdroid.** { *; }

# Glide
-keep public class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep public class * extends com.bumptech.glide.module.LibraryGlideModule { *; }
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }
