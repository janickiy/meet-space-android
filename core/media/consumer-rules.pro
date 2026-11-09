# Native WebRTC invokes Java callbacks by name.
-keep class org.webrtc.** { *; }
# JNI_OnLoad looks up JNI Zero classes and methods by their original names.
-keep class org.jni_zero.** { *; }
