# Avisos y atribuciones de terceros

UGscaler 1.6.7 incorpora o utiliza:

- [RealSR-NCNN-Android](https://github.com/tumuyan/RealSR-NCNN-Android), backend Android bajo licencia MIT.
- [BSRGAN](https://github.com/cszn/BSRGAN), restauración ciega y superresolución para fotografías degradadas, licencia Apache 2.0. El texto íntegro se incluye en `app/src/main/assets/licenses/BSRGAN-LICENSE.txt`.
- [NCNN](https://github.com/Tencent/ncnn), runtime móvil, licencia BSD 3-Clause.
- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp), runtime generativo local, licencia MIT.
- [Local Diffusion](https://github.com/rmatif/Local-Diffusion), referencia de integración Android y bibliotecas nativas, licencia Apache 2.0.
- [JNA](https://github.com/java-native-access/jna), puente Java/nativo, licencias Apache 2.0 y LGPL 2.1 o posterior.
- AndroidX Core y ExifInterface, licencia Apache 2.0.

El modelo generativo opcional DreamShaper 7 LCM Q4 no se incluye en la APK. Si el usuario decide descargarlo, mantiene la licencia CreativeML OpenRAIL-M y sus restricciones de uso originales.

Los avisos del backend BSRGAN/NCNN se conservan en `app/src/main/assets/realesrgan/THIRD_PARTY_NOTICES.txt`.

UGscaler no modifica ni sustituye licencias o condiciones de terceros. Toda redistribución debe conservar estos avisos.

## Proyectos evaluados, no incluidos

Real-ESRGAN, Nomos8kSC, RT-Focuser, NAFNet, Ghost-DeblurGAN, DeblurGAN-MobileNet, Restormer, MPRNet, MISCFilter, CodeFormer, SAFMN y SPAN fueron evaluados, pero no forman parte del flujo predeterminado por calidad inferior en las pruebas reales, tamaño, consumo de memoria o falta de una ruta Android estable. Las API generativas se descartaron para garantizar coste cero y ausencia de cuotas.
