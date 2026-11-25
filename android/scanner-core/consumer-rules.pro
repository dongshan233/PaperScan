# Scanner core consumer rules: keep JNI entry points for the native runtimes
# shipped by this module (OpenCV + ONNX Runtime). Only needed when the host app
# enables minification; harmless otherwise.

-keep class org.opencv.** { *; }
-keep class ai.onnxruntime.** { *; }
-dontwarn org.opencv.**
-dontwarn ai.onnxruntime.**
