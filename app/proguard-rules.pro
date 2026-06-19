# Keep reflection on BluetoothDevice methods like removeBond and createBond
-keep class android.bluetooth.BluetoothDevice {
    public boolean removeBond();
    public boolean createBond();
}

# Keep classes and methods that are reflection-accessed in the app
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep kotlin serialization descriptor and serializer classes
-keepclassmembers class * {
    *** Companion;
}
