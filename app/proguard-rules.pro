# ONNX Runtime's JNI resolves Java classes and methods by their original names.
# Microsoft requires this rule for R8-minified Android builds; without it ART
# aborts in GetMethodID while converting an inference result.
-keep class ai.onnxruntime.** { *; }

# Keep the application entry point because UGscaler builds its interface
# programmatically.
-keep class com.mejorarfotos.app.MainActivity { *; }
