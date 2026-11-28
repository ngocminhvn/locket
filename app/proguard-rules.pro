# Preserve exact JNI names used by exported Rust entry points.
-keep class com.locket.app.rust.** { *; }
# Gson serializes model field names into existing cached JSON.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
-keep class com.locket.app.model.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
# Preserve database and converters for existing Room data.
-keep class com.locket.app.storage.db.** { *; }
-keep class com.locket.app.widget.** { *; }

# Full protobuf extensions resolve this overload reflectively at runtime.
# R8 cannot see that call and otherwise removes it from generated enums,
# which crashes Firestore Listen during startup in minified Release builds.
-keepclassmembers enum com.google.** {
    public static ** valueOf(com.google.protobuf.Descriptors$EnumValueDescriptor);
    public com.google.protobuf.Descriptors$EnumValueDescriptor getValueDescriptor();
}

-dontwarn com.google.errorprone.annotations.**
