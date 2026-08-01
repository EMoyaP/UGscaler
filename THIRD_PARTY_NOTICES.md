# Avisos y atribuciones de terceros

UGscaler 1.6.4 incorpora o utiliza:

- [RT-Focuser](https://github.com/ReaganWu/RT-Focuser), modelo de eliminación de desenfoque, licencia MIT. El texto íntegro se incluye en `app/src/main/assets/licenses/RTFOCUSER-LICENSE.txt`.
- [RealSR-NCNN-Android](https://github.com/tumuyan/RealSR-NCNN-Android), backend Android bajo licencia MIT.
- [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN), superresolución para fotografías reales, licencia BSD 3-Clause.
- [NCNN](https://github.com/Tencent/ncnn), runtime móvil, licencia BSD 3-Clause.
- [ONNX Runtime](https://github.com/microsoft/onnxruntime), inferencia Android, licencia MIT.
- AndroidX Core, ExifInterface y Credential Manager, licencia Apache 2.0.
- Firebase Authentication y Firebase AI Logic SDK, sujetos a las licencias y condiciones de Google/Firebase aplicables.
- Google Identity Services (`googleid`), para el acceso mediante cuentas Google.

Los avisos del backend Real-ESRGAN/NCNN se conservan en `app/src/main/assets/realesrgan/THIRD_PARTY_NOTICES.txt`.

El modo generativo se conecta mediante [Firebase AI Logic](https://firebase.google.com/docs/ai-logic) a la [API de Gemini](https://ai.google.dev/gemini-api/docs/image-generation), servicios externos de Google sujetos a sus propias condiciones, políticas de privacidad, disponibilidad, cuotas y precios. UGscaler no redistribuye el modelo generativo ni sus pesos.

UGscaler no modifica ni sustituye licencias o condiciones de terceros. Toda redistribución debe conservar estos avisos.

## Proyectos evaluados, no incluidos

NAFNet, Ghost-DeblurGAN, DeblurGAN-MobileNet, Restormer, MPRNet, MISCFilter, CodeFormer y SAFMN fueron evaluados, pero no forman parte de esta versión por calidad insuficiente en la prueba real, tamaño, consumo de memoria o falta de una ruta Android estable.
