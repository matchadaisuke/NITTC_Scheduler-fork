# WorkManagerはInputMergerをクラス名と引数なしコンストラクターで生成する。
# R8の最適化でコンストラクターが消えると、授業通知を含むOneTimeWorkが実行できない。
-keep class * extends androidx.work.InputMerger {
    public <init>();
}

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

# ダウンロードする固定済みネイティブライブラリのJNI名とコールバック名を維持する。
-keep class org.nehuatl.llamacpp.** { *; }
