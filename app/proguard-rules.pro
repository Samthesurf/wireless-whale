# Shizuku instantiates this Binder service reflectively from the APK.
-keep class com.samuel.wirelesswhale.bridge.** { *; }
-keep class com.samuel.wirelesswhale.platform.shizuku.HotspotUserService {
    public <init>(...);
}
-keepnames class com.samuel.wirelesswhale.platform.shizuku.HotspotUserService
