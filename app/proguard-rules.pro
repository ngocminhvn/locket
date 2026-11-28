# Preserve exact JNI names used by exported Rust entry points.
-keep class com.locket.app.rust.** { *; }
# Gson serializes model field names into existing cached JSON.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
-keep class com.locket.app.model.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
# Preserve database and converters for existing Room data.
-keep class com.locket.app.storage.db.** { *; }
-keep class com.locket.app.widget.** { *; }

# Full Protobuf generated accessors, enum descriptors and builders are
# looked up by name at runtime. Keep all members of reachable generated
# messages/builders/enums, rather than chasing individual missing methods.
# Unused message classes can still be removed; R8 remains active elsewhere.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessage { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageV3 { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessage$Builder { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageV3$Builder { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers enum * implements com.google.protobuf.ProtocolMessageEnum { *; }
-keepclassmembers enum * implements com.google.protobuf.Internal$EnumLite { *; }
# Protect reachable runtime descriptor/schema helpers and their reflected fields.
-keepclassmembers class com.google.protobuf.** { *; }
# Providers may be discovered by ServiceLoader or reflective construction.
-keep class * extends io.grpc.ManagedChannelProvider { public <init>(); }
-keep class * extends io.grpc.NameResolverProvider { public <init>(); }
-keep class * extends io.grpc.LoadBalancerProvider { public <init>(); }

-dontwarn com.google.errorprone.annotations.**
