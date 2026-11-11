# Release builds of apps strip the debug-only self-check and preview paths: R8 treats the gate as off.
-assumevalues class dev.pingfront.internal.debug.DebugGate {
    static boolean isEnabled() return false;
}
# Modules registered through the merged manifest are loaded by name.
-keepclassmembers class * extends dev.pingfront.PingFrontModule {
    public static final ** INSTANCE;
}
-keep class dev.pingfront.internal.android.PingFrontInitializer { <init>(); }
-keep class dev.pingfront.internal.android.OutboxRetryWorker { <init>(...); }
