# kotlinx.serialization ships its own consumer rules; keep our DTOs' serializers explicitly too.
-keepclassmembers class dev.dt.kinopub.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.dt.kinopub.data.**$$serializer { *; }
-dontwarn org.slf4j.**
