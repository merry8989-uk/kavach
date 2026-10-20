# Keep Room entities
-keep class com.kavach.caller.data.local.entity.** { *; }
# Keep kotlinx.serialization models + generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.kavach.caller.ai.** { *; }
-keep,includedescriptorclasses class com.kavach.caller.**$$serializer { *; }
# OkHttp (cloud AI client)
-keepattributes Signature, RuntimeVisibleAnnotations
-dontwarn okhttp3.**
-dontwarn okio.**
# Compile-time-only annotations referenced by some libraries
-dontwarn com.google.auto.value.**
-dontwarn javax.annotation.**
