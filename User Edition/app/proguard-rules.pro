# Keep reflection on BluetoothDevice methods like removeBond and createBond
-keep class android.bluetooth.BluetoothDevice {
    public boolean removeBond();
    public boolean createBond();
}

# Keep classes and methods that are reflection-accessed in the app
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep LZ4 compression library classes and prevent warning messages
-keep class net.jpountz.** { *; }
-dontwarn net.jpountz.**

# Keep all classes implementing androidx.navigation3.runtime.NavKey
-keep class * implements androidx.navigation3.runtime.NavKey { *; }
-keep class com.example.bluemesh.Setup { *; }
-keep class com.example.bluemesh.Main { *; }
-keep class com.example.bluemesh.Chat { *; }
-keep class com.example.bluemesh.ContactsList { *; }
-keep class com.example.bluemesh.SecuritySettings { *; }
-keep class com.example.bluemesh.Lock { *; }
-keep class com.example.bluemesh.SetupPasscode { *; }

# Keep all data models
-keep class com.example.bluemesh.data.models.** { *; }

# Kotlin Serialization specific keep rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class * implements kotlinx.serialization.KSerializer {*;}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keepclassmembers class * {
    *** Companion;
}
-keepclassmembers class * {
    *** serializer(...);
}

# Keep Bluetooth, GATT, and BroadcastReceiver callbacks called by the Android system
-keep class * extends android.bluetooth.BluetoothGattCallback { *; }
-keep class * extends android.bluetooth.BluetoothGattServerCallback { *; }
-keep class * extends android.bluetooth.le.ScanCallback { *; }
-keep class * extends android.bluetooth.le.AdvertiseCallback { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
