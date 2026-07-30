# Avisos y atribuciones de terceros

UGscaler incorpora o utiliza los siguientes proyectos:

- [CodeFormer](https://github.com/sczhou/CodeFormer), checkpoint oficial y
  arquitectura de restauración facial. Licencia NTU S-Lab License 1.0:
  redistribución y uso no comercial. El texto íntegro se incluye en
  `app/src/main/assets/licenses/CODEFORMER-LICENSE.txt`.
- [RealSR-NCNN-Android](https://github.com/tumuyan/RealSR-NCNN-Android),
  backend Android bajo licencia MIT.
- [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN), modelo de
  superresolución para fotografías reales.
- [NCNN](https://github.com/Tencent/ncnn), runtime móvil bajo licencia BSD 3-Clause.
- [OpenCV](https://github.com/opencv/opencv), utilizado para ORB, matching,
  homografía RANSAC y warp perspective; licencia Apache 2.0.
- [ONNX Runtime](https://github.com/microsoft/onnxruntime), runtime Android
  utilizado para ejecutar CodeFormer; licencia MIT.
- [ML Kit Face Detection](https://developers.google.com/ml-kit/vision/face-detection/android),
  utilizado localmente para detectar y alinear rostros.
- AndroidX Core y AndroidX ExifInterface, bajo licencia Apache 2.0.

El backend nativo de `app/src/main/assets/realesrgan/` contiene el ejecutable
ARM64, las bibliotecas NCNN y el modelo convertido `x4.bin`/`x4.param`.
CodeFormer no se empaqueta dentro de la APK: se descarga desde la publicación
1.4.0, se valida mediante SHA-256 y se guarda en el almacenamiento privado.

UGscaler no modifica ni sustituye las licencias de terceros. Toda
redistribución debe conservar estos avisos. El uso comercial de CodeFormer
requiere contactar con sus contribuidores según la NTU S-Lab License 1.0.

## Proyectos evaluados, no incluidos

MISCFilter, Restormer, RVRT, VRT, BasicVSR++ y NAFNet se estudiaron durante las
pruebas. NAFNet se retiró de 1.4.0 porque el modelo GoPro suavizaba el fotograma
real de referencia y no aportaba información temporal.
