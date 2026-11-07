# kotlinx.serialization ships its own consumer rules; keep our DTOs' serializers explicitly too.
-keepclassmembers class dev.dt.kinopub.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.dt.kinopub.data.**$$serializer { *; }
-dontwarn org.slf4j.**
# Self-update DTOs (GitHub release JSON) live in system/Updater.kt.
-keepclassmembers class dev.dt.kinopub.system.Updater$* {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.dt.kinopub.system.Updater$*$$serializer { *; }
