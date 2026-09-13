-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,RuntimeVisibleAnnotations,AnnotationDefault,JavascriptInterface
-verbose
-dontwarn okio.**,okhttp3.**,okhttp3.internal.**,com.topjohnwu.superuser.**,org.osmdroid.**,com.bumptech.glide.**,com.github.luben.**,jcifs.**,com.github.seancfoley.**,com.opencsv.**,org.apache.commons.**,org.tukaani.**,org.jsoup.**,com.google.zxing.**,com.google.android.flexbox.**,androidx.**,kotlinx.**,kotlin.**,sun.misc.**,dalvik.**,org.conscrypt.**,org.bouncycastle.**,org.openjsse.**
-dontnote okio.**,okhttp3.**,kotlinx.serialization.**,com.bumptech.glide.**,org.osmdroid.**,androidx.work.**,androidx.navigation.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclassmembers class ** {
    @kotlin.jvm.JvmStatic <methods>;
    @kotlin.jvm.JvmOverloads <methods>;
    @kotlin.jvm.JvmField <fields>;
}
-keepclassmembers class ** {
    public static ** Companion;
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepclassmembers class kotlinx.serialization.protobuf.** { *; }
-if @kotlinx.serialization.Serializable class **
-keep class <1> { *; }
-keepclasseswithmembernames class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class **$$serializer { *; }
-keep class **$$serializerKt { *; }
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
    @kotlinx.serialization.protobuf.ProtoNumber <fields>;
}
-keep,allowobfuscation class * implements kotlinx.serialization.KSerializer { *; }
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <init>(...);
}
-keep class * extends kotlinx.serialization.json.JsonTransformingSerializer { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    <init>(...);
    <methods>;
}
-keep public class com.lsd.wififrankenstein.WifiApplication { *; }
-keep public class com.lsd.wififrankenstein.MainActivity { *; }
-keep public class com.lsd.wififrankenstein.WelcomeActivity { *; }
-keep public class com.lsd.wififrankenstein.WpsGeneratorActivity { *; }
-keep public class com.lsd.wififrankenstein.WpsGeneratorActivity$MyJavascriptInterface {
    *;
}
-keepclasseswithmembers class com.lsd.wififrankenstein.WpsGeneratorActivity$MyJavascriptInterface {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keep public class com.lsd.wififrankenstein.service.DownloadService { *; }
-keep public class com.lsd.wififrankenstein.service.DatabaseDownloadService { *; }
-keep public class com.lsd.wififrankenstein.service.ChrootInstallService { *; }
-keep public class com.lsd.wififrankenstein.service.ForegroundAttackService { *; }
-keep public class com.lsd.wififrankenstein.service.BettercapDaemonService { *; }
-keep public class com.lsd.wififrankenstein.service.WpaCrackService { *; }
-keep public class com.lsd.wififrankenstein.service.ChrootAttackService { *; }
-keep public class com.lsd.wififrankenstein.service.HandshakeCaptureService { *; }
-keep public class com.lsd.wififrankenstein.service.NetProtectionService { *; }
-keep public class com.lsd.wififrankenstein.service.WiFiMapScanningService { *; }
-keep class androidx.core.content.FileProvider { *; }
-keep public class * extends androidx.fragment.app.Fragment {
    public <init>(...);
    public static ** newInstance(...);
}
-keep class * extends androidx.navigation.Navigator { *; }
-keep class * extends androidx.navigation.NavDirections { *; }
-keep class androidx.navigation.fragment.NavHostFragment { *; }
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keep class com.lsd.wififrankenstein.**Args { *; }
-keep class com.lsd.wififrankenstein.**Directions { *; }
-keep class com.lsd.wififrankenstein.**Action* { *; }
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** bind(...);
    public static *** inflate(...);
}
-keep public class com.lsd.wififrankenstein.util.AnimatedLoadingBar {
    public <init>(...);
}
-keep public class com.lsd.wififrankenstein.ui.wifianalysis.WiFiSpectrumView {
    public <init>(...);
}
-keep public class com.lsd.wififrankenstein.ipranges.CustomMapView {
    public <init>(...);
}
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keep public class * extends android.view.ViewGroup {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keep class * extends androidx.recyclerview.widget.RecyclerView$ViewHolder {
    public <init>(...);
}
-keep class * extends androidx.recyclerview.widget.RecyclerView$Adapter { *; }
-keep class * extends androidx.recyclerview.widget.ListAdapter { *; }
-keep public class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}
-keep public class * extends androidx.lifecycle.AndroidViewModel {
    public <init>(...);
}
-keep class * implements androidx.lifecycle.ViewModelProvider$Factory { *; }
-keep class * extends androidx.paging.PagingSource { *; }
-keep class * extends androidx.paging.PagingDataAdapter { *; }
-keep public class com.lsd.wififrankenstein.workers.NotificationWorker { *; }
-keep public class * extends androidx.work.Worker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}
-keep public class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.WorkerFactory { *; }
-keep public class com.lsd.wififrankenstein.util.NativeCracker {
    native <methods>;
    *;
}
-keep class android.net.wifi.ScanResult { *; }
-keepclassmembers class android.net.wifi.ScanResult {
    <fields>;
}
-keep class android.net.wifi.SupplicantState { *; }
-keep class android.net.wifi.WifiConfiguration* { *; }
-keep class android.net.wifi.WifiManager* { *; }
-dontwarn java.lang.reflect.**
-keepclassmembers,allowshrinking,allowobfuscation class * extends java.lang.Enum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum com.lsd.wififrankenstein.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
    public void writeToParcel(android.os.Parcel, int);
    public int describeContents();
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keep public class * extends android.database.sqlite.SQLiteOpenHelper {
    public <init>(...);
    public void onCreate(android.database.sqlite.SQLiteDatabase);
    public void onUpgrade(android.database.sqlite.SQLiteDatabase, int, int);
}
-keep class androidx.localbroadcastmanager.content.LocalBroadcastManager { *; }
-keep public class * extends android.content.BroadcastReceiver {
    public <init>(...);
    public void onReceive(android.content.Context, android.content.Intent);
}
-keep public class com.lsd.wififrankenstein.shell.ShellInitializer { *; }
-keep public class * extends com.topjohnwu.superuser.Shell$Initializer {
    public <init>(...);
    public boolean onInit(...);
}
-keep class com.topjohnwu.superuser.Shell {
    *;
}
-keep class com.topjohnwu.superuser.Shell$Builder { *; }
-keep class com.topjohnwu.superuser.Shell$Job { *; }
-keep class com.topjohnwu.superuser.Shell$Result { *; }
-keep public class org.osmdroid.views.MapView {
    public <init>(...);
    *;
}
-keep public class org.osmdroid.config.Configuration { *; }
-keep public class org.osmdroid.tileprovider.** { *; }
-keep public class org.osmdroid.views.overlay.** { *; }
-keep public class org.osmdroid.util.GeoPoint { *; }
-keep public class org.osmdroid.api.IMapController { *; }
-keep public class org.osmdroid.events.** { *; }
-keep public class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep public class * extends com.bumptech.glide.module.LibraryGlideModule { *; }
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }
-keep class com.bumptech.glide.GlideApp { *; }
-keep class com.bumptech.glide.RequestManager { *; }
-keep class * implements com.bumptech.glide.module.GlideModule { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$ImageType {
    **[] $VALUES;
    *;
}
-keep class okhttp3.EventListener { *; }
-keep class okhttp3.EventListener$Factory { *; }
-keep public class com.lsd.wififrankenstein.ui.internetblocking.scanner.DpiTraceEventListener { *; }
-keep public class com.lsd.wififrankenstein.ui.internetblocking.scanner.DpiTraceState { *; }
-keep class com.lsd.wififrankenstein.network.bettercap.BettercapClient { *; }
-keep class com.lsd.wififrankenstein.util.SslHelper { *; }
-keep class * implements javax.net.ssl.X509TrustManager { *; }
-keep class * implements javax.net.ssl.HostnameVerifier { *; }
-keep class com.lsd.wififrankenstein.util.QrCodeHelper { *; }
-keep class com.lsd.wififrankenstein.util.QrNavigationHelper { *; }
-keep class com.google.android.gms.location.** { *; }
-keep class jcifs.** { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn com.android.tools.r8.**
-dontwarn org.objectweb.asm.**
-dontwarn com.google.android.gms.internal.location.**
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
