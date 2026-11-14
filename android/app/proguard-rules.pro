# Strip verbose, debug, info, and warning logs in release builds per audit rules
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# Shizuku UserService, AIDL and API
-keep class org.privacyguard.android.shizuku.ShizukuShellService { *; }
-keep class org.privacyguard.android.service.IShellService* { *; }
-keep class org.privacyguard.android.service.ShellResult { *; }
-keep class * implements rikka.shizuku.Shizuku$* { *; }
-keep class rikka.shizuku.** { *; }

# Milestone 1B & 2 Scanner AIDL, Service, Parcelables and JNI
-keep class org.privacyguard.android.scan.IScanService* { *; }
-keep class org.privacyguard.android.scan.NativeScanResult { *; }
-keepclassmembers class org.privacyguard.android.scan.ScanService {
    native <methods>;
}
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# Kotlinx Serialization (for OllamaClient schema models)
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class * implements kotlinx.serialization.KSerializer { *; }
-keep class org.privacyguard.android.osint.** { *; }

# Hidden API Bypass & Refine
-keep class org.lsposed.hiddenapibypass.** { *; }
-keep class dev.rikka.tools.refine.** { *; }
-keep class dev.rikka.hidden.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# WorkManager
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
