# Keep kotlinx.serialization metadata used by the DGS client's JSON parsing.
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Compile-time annotations Tink (EncryptedSharedPreferences) refers to but does not ship.
-dontwarn com.google.errorprone.annotations.**
