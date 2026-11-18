
# MEGA SDK Java/SWIG bindings are accessed through reflection.
-keep class nz.mega.sdk.** { *; }

# App Widgets (Glance): receivers/widgets are instantiated by the framework and Glance via reflection.
-keep class jp.linkserver.nittcsc.widget.** { *; }
-keep class androidx.glance.** { *; }
-dontwarn androidx.glance.**

# WorkManager: R8 完全モードで InputMerger / Worker のコンストラクタが削除されるのを防ぐ。
-keep class * extends androidx.work.InputMerger { public <init>(); }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
