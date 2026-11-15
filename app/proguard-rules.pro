
# MEGA SDK Java/SWIG bindings are accessed through reflection.
-keep class nz.mega.sdk.** { *; }

# App Widgets (Glance): receivers/widgets are instantiated by the framework and Glance via reflection.
-keep class jp.linkserver.nittcsc.widget.** { *; }
-keep class androidx.glance.** { *; }
-dontwarn androidx.glance.**
