-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-keepattributes AnnotationDefault,RuntimeVisibleAnnotations
-keepclassmembers class com.patbaumgartner.roomwatchdog.** {
    *** Companion;
}
-keepclasseswithmembers class com.patbaumgartner.roomwatchdog.** {
    kotlinx.serialization.KSerializer serializer(...);
}
