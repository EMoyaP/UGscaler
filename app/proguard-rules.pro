# Keep the application entry point because UGscaler builds its interface
# programmatically.
-keep class com.mejorarfotos.app.MainActivity { *; }

# JNA resolves the stable-diffusion.cpp ABI through reflection.
-keep class com.sun.jna.** { *; }
-keep interface com.mejorarfotos.app.LocalDiffusionEngine$SdApi { *; }
-keep interface com.mejorarfotos.app.LocalDiffusionEngine$ProgressCallback { *; }
-keep class com.mejorarfotos.app.LocalDiffusionEngine$SdImage { *; }
-keep class com.mejorarfotos.app.LocalDiffusionEngine$SdImage$ByValue { *; }

# JNA is multiplatform and contains optional desktop-only AWT helpers. They are
# unreachable on Android but R8 still sees their type references.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
