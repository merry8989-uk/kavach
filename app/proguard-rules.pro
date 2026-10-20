# Keep Room entities
-keep class com.kavach.caller.data.local.entity.** { *; }
# Keep kotlinx.serialization models + generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.kavach.caller.ai.model.** { *; }
-keepclassmembers class com.kavach.caller.ai.** { *; }
-keep,includedescriptorclasses class com.kavach.caller.**$$serializer { *; }
# Retrofit / OkHttp (used by the cloud AI client)
-keepattributes Signature, RuntimeVisibleAnnotations
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
# Some libs reference annotations that are compile-time only
-dontwarn com.google.auto.value.**
-dontwarn javax.annotation.**
