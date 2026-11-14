# Shizuku UserService, AIDL and API
-keep class org.privacyguard.android.shizuku.ShizukuShellService { *; }
-keep class org.privacyguard.android.service.IShellService* { *; }
-keep class org.privacyguard.android.service.ShellResult { *; }
-keep class * implements rikka.shizuku.Shizuku$* { *; }
-keep class rikka.shizuku.** { *; }

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

# Native & JNI methods (for future Tier 1B scanner)
-keepclasseswithmembernames class * {
    native <methods>;
}
