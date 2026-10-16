# kotlinx.serialization: keep generated serializers for the sync/backup models.
-keepclassmembers class com.tremendoushand.repeatodo.domain.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.tremendoushand.repeatodo.domain.**$$serializer { *; }
