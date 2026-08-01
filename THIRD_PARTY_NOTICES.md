# Avisos y atribuciones de terceros

UGscaler 1.6.6 incorpora o utiliza:

- [RealSR-NCNN-Android](https://github.com/tumuyan/RealSR-NCNN-Android), backend Android bajo licencia MIT.
- [BSRGAN](https://github.com/cszn/BSRGAN), restauración ciega y superresolución para fotografías degradadas, licencia Apache 2.0. El texto íntegro se incluye en `app/src/main/assets/licenses/BSRGAN-LICENSE.txt`.
- [NCNN](https://github.com/Tencent/ncnn), runtime móvil, licencia BSD 3-Clause.
- AndroidX Core y ExifInterface, licencia Apache 2.0.

Los avisos del backend BSRGAN/NCNN se conservan en `app/src/main/assets/realesrgan/THIRD_PARTY_NOTICES.txt`.

UGscaler no modifica ni sustituye licencias o condiciones de terceros. Toda redistribución debe conservar estos avisos.

## Proyectos evaluados, no incluidos

Real-ESRGAN, Nomos8kSC, RT-Focuser, NAFNet, Ghost-DeblurGAN, DeblurGAN-MobileNet, Restormer, MPRNet, MISCFilter, CodeFormer, SAFMN, SPAN y flujos generativos Stable Diffusion/ControlNet fueron evaluados, pero no forman parte de esta versión por calidad inferior en las pruebas reales, tamaño, consumo de memoria o falta de una ruta Android estable. Las API generativas también fueron descartadas para garantizar coste cero y ausencia de cuotas.
