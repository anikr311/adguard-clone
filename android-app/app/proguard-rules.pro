# The JavaScript bridge is called from the bundled UI by reflection.
-keepclassmembers class com.adlocko.app.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.adlocko.app.MainActivity$AndroidBridge { *; }
