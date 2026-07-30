# Avisos y atribuciones de terceros

UGscaler incorpora o utiliza los siguientes proyectos y componentes:

- [RealSR-NCNN-Android](https://github.com/tumuyan/RealSR-NCNN-Android), incluido como backend Android y distribuido bajo su licencia MIT.
- [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN), utilizado como modelo de super-resolucion para fotografias reales.
- [NCNN](https://github.com/Tencent/ncnn), utilizado como runtime de inferencia en dispositivos moviles.
- [NAFNet](https://github.com/megvii-research/NAFNet), modelo MIT de motion deblurring convertido a ONNX y ejecutado localmente.
- [ONNX Runtime](https://github.com/microsoft/onnxruntime), runtime Android utilizado para ejecutar NAFNet.
- [MediaMetadataRetriever](https://developer.android.com/reference/android/media/MediaMetadataRetriever), API del sistema usada para extraer fotogramas y analizar video.
- [AndroidX Core](https://developer.android.com/jetpack/androidx) y [AndroidX ExifInterface](https://developer.android.com/jetpack/androidx/releases/exifinterface), distribuidos bajo sus respectivas licencias Apache 2.0.

El backend nativo incluido en `app/src/main/assets/realesrgan/` contiene el ejecutable ARM64, las librerias NCNN y el modelo convertido `x4.bin`/`x4.param`. El modelo NAFNet convertido se encuentra en `app/src/main/assets/nafnet/`.

UGscaler no modifica ni sustituye las licencias de los componentes de terceros. Para redistribuir una version que incluya estos componentes, deben conservarse estos avisos y consultarse las licencias upstream.

## Proyectos evaluados, no incluidos en el APK

Se han revisado [MISCFilter](https://github.com/ChengxuLiu/MISCFilter), [Restormer](https://github.com/swz30/Restormer), [RVRT](https://github.com/JingyunLiang/RVRT), [VRT](https://github.com/JingyunLiang/VRT) y [BasicVSR++](https://github.com/ckkelvinchan/BasicVSR_PlusPlus). Son referencias de deblurring y restauracion temporal; sus pesos, conversiones Android y licencias propias deben incorporarse por separado si se añaden en futuras versiones.
