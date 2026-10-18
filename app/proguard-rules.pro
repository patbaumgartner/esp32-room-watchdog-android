-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-keepattributes AnnotationDefault,RuntimeVisibleAnnotations
-keepclassmembers class com.patbaumgartner.android.roomwatchdog.** {
    *** Companion;
}
-keepclasseswithmembers class com.patbaumgartner.android.roomwatchdog.** {
    kotlinx.serialization.KSerializer serializer(...);
}
