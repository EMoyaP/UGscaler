# Avisos y atribuciones de terceros

UGscaler incorpora o utiliza los siguientes proyectos y componentes:

- [RealSR-NCNN-Android](https://github.com/tumuyan/RealSR-NCNN-Android), incluido como backend Android y distribuido bajo su licencia MIT.
- [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN), utilizado como modelo de super-resolución para fotografías reales.
- [NCNN](https://github.com/Tencent/ncnn), utilizado como runtime de inferencia en dispositivos móviles.
- [AndroidX Core](https://developer.android.com/jetpack/androidx) y [AndroidX ExifInterface](https://developer.android.com/jetpack/androidx/releases/exifinterface), distribuidos bajo sus respectivas licencias Apache 2.0.

El backend nativo incluido en `app/src/main/assets/realesrgan/` contiene el
ejecutable ARM64, las librerías NCNN y el modelo convertido `x4.bin`/`x4.param`.
El aviso MIT correspondiente también se incluye junto a esos assets.

UGscaler no modifica ni sustituye las licencias de los componentes de terceros.
Para redistribuir una versión que incluya estos componentes, deben conservarse
estos avisos y consultarse las licencias upstream.
