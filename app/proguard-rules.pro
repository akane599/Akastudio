# Project-specific R8 rules. Add keep rules for reflection/serialization here.

# --- MediaPipe Tasks (tasks-core / tasks-vision 1.1.1 ship no consumer rules) ---
# libmediapipe_tasks_jni.so looks up the framework bridge by name (Packet, PacketCreator, PacketListCallback,
# MediaPipeException, ProtoUtil$SerializedMessage, TextureReleaseCallback) and calls their constructors, fields and
# methods through JNI, which R8 cannot see. Native method names are kept by the default rules.
-keep class com.google.mediapipe.framework.** { *; }
# Task and calculator options cross into the native graph as protobuf-lite messages, which read their fields
# reflectively (protobuf-javalite ships no consumer rules; this is its documented keep rule).
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
# Referenced by framework APIs the Tasks runtime never calls (GraphProfiler, Graph.loadBinaryGraphTemplate) and by a
# compile-only AutoValue annotation; none of them ship in the tasks-core AAR.
-dontwarn com.google.auto.value.extension.memoized.Memoized
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate

# --- LiteRT (com.google.ai.edge.litert) ---
# liblitert_jni.so constructs LiteRtException, TensorBufferRequirements and TensorType (with its ElementType and
# Layout enums) by name; the vendor rules only cover @UsedByReflection, so keep the small Kotlin API whole.
-keep class com.google.ai.edge.litert.** { *; }

# --- Room (transitive: work-runtime 2.9.1 -> room-runtime 2.5.0) ---
# Room creates generated *_Impl databases through their no-arg constructor by reflection. room-runtime 2.5.0's consumer
# rule keeps the class but not <init>(), which R8 full mode (the AGP default) then removes, so keep it explicitly.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
