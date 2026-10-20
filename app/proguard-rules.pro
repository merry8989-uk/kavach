# Keep Room entities
-keep class com.kavach.caller.data.local.entity.** { *; }
# Keep kotlinx.serialization models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.kavach.caller.ai.model.** { *; }
# Retrofit
-keepattributes Signature, RuntimeVisibleAnnotations
-dontwarn okhttp3.**
-dontwarn retrofit2.**
