# UGscaler

Aplicación Android para recuperar detalle de fotografías y obtener un
fotograma estático de mayor calidad a partir de un video. Todo el procesado de
las imágenes se realiza en el dispositivo.

## Novedades de la versión 1.4.0

- Fusión temporal real de cinco fotogramas.
- Alineación ORB + homografía RANSAC para compensar el movimiento de cámara.
- Rechazo fotométrico para no mezclar personas u objetos que se han movido.
- Reconstrucción general con Real-ESRGAN/NCNN.
- Restauración facial opcional con CodeFormer y fidelidad `0.9`.
- Detección y alineación facial local mediante el modelo integrado de ML Kit.
- Verificación SHA-256 del modelo CodeFormer descargado.
- Aviso visible de que la restauración facial es generativa y puede reconstruir rasgos.

## Cómo funciona

En modo Video, UGscaler lee índices de fotograma reales, selecciona la base con
mejor detalle útil y alinea sus dos vecinos anteriores y posteriores. Solo
fusiona píxeles consistentes, por lo que esta primera etapa aprovecha
información capturada de verdad por el video.

Después, Real-ESRGAN reconstruye bordes y aumenta la resolución. Si `Rostro IA`
está activado y se detecta un rostro válido, UGscaler lo alinea a 512 × 512,
aplica CodeFormer con fidelidad alta y lo integra de nuevo mediante una máscara
suave. La opción se puede desactivar porque esta última etapa es generativa.

## Privacidad y modelo opcional

Las fotos y los videos no se suben a servidores. CodeFormer pesa unos 359 MiB,
por lo que se descarga una sola vez cuando realmente se detecta un rostro. La
app comprueba su SHA-256 antes de instalarlo y, a partir de ahí, funciona sin
conexión. Consulta [MODEL_MANIFEST.md](MODEL_MANIFEST.md).

## Otras funciones

- Modos independientes Foto y Video.
- Cámara, galería, corrección EXIF y selector temporal.
- Presets Auto, Retrato, Paisaje y Texto.
- Controles de ruido, detalle, enfoque y escala 2×/4×.
- Recorte, comparación antes/después y exportación a `Imágenes/UGscaler`.
- Botones Nuevo y Atrás para editar otro archivo sin cerrar la aplicación.
- Backend ARM64 con Vulkan cuando el dispositivo lo permite y respaldo local.

## Descargar

La versión actual es `1.4.0`. La APK se encuentra en la raíz como
[`UGscaler-v1.4.0.apk`](UGscaler-v1.4.0.apk). El nombre no incluye el sufijo
`debug`, aunque se trata de una compilación de pruebas firmada con la clave de
depuración de Android.

## Compilar

Requisitos: JDK 17, Android SDK 35 y un dispositivo ARM64.

```powershell
.\gradlew.bat clean lintDebug testDebugUnitTest assembleDebug
```

La salida se genera como
`app/build/outputs/apk/debug/UGscaler-v1.4.0.apk`.

La conversión reproducible del checkpoint oficial se documenta en
`tools/export_codeformer_onnx.py`; `tools/validate_codeformer_onnx.py` ejecuta
una comprobación de inferencia sobre un rostro alineado.

## Límites reales

- Ningún algoritmo puede recuperar con certeza datos que el sensor nunca
  capturó. La fusión temporal sí aprovecha detalle de otros fotogramas, pero
  necesita que exista solapamiento suficiente.
- CodeFormer puede reconstruir ojos, piel, pelo u otros rasgos plausibles que
  no sean idénticos a los originales.
- ML Kit necesita aproximadamente 100 × 100 píxeles útiles de rostro para una
  detección fiable.
- El modelo CodeFormer requiere unos 359 MiB adicionales en el almacenamiento
  privado de la aplicación.
- La APK incluida es ARM64 y el modo Video exporta un fotograma JPG, no vuelve
  a renderizar el video completo.

## Licencia

El código propio se distribuye bajo
[UGscaler Non-Commercial License 1.0](LICENSE). Los componentes de terceros
conservan sus licencias; consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
