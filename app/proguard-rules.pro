# Preserve exact JNI names used by exported Rust entry points.
-keep class com.locket.app.rust.** { *; }
# Gson serializes model field names into existing cached JSON.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
-keep class com.locket.app.model.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
# Preserve database and converters for existing Room data.
-keep class com.locket.app.storage.db.** { *; }
-keep class com.locket.app.widget.** { *; }
-dontwarn com.google.errorprone.annotations.**
