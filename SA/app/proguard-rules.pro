# Kotlinx Serialization needs its generated serializer classes kept.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclasseswithmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sa.assistant.**$$serializer { *; }
-keepclassmembers class com.sa.assistant.** {
    *** Companion;
}
-keepclasseswithmembers class com.sa.assistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Hilt / Dagger generated code
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp

# Room
-keep class androidx.room.RoomDatabase
