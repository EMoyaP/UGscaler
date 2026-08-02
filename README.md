# UGscaler

Aplicación Android para restaurar, recortar y reescalar fotografías mediante IA completamente local, sin cuentas, cuotas, suscripciones ni costes por imagen.

## UGscaler 1.6.7

La interfaz está diseñada para pantallas de smartphone y uso con una mano:

1. Pulsa **Subir foto**.
2. Opcionalmente, pulsa **Recortar**. Mientras ajustas el marco se bloquea el desplazamiento vertical y el botón cambia a **Aceptar**.
3. Pulsa **Mejorar con IA**.
4. UGscaler muestra el porcentaje, evalúa el resultado, lo guarda como PNG y abre una vista con **Comparar**, **Compartir** y **Cerrar**.

Si la reconstrucción no supera el detalle medible del original o presenta riesgo de artefactos, el proceso se detiene antes de guardar. El usuario puede elegir **Conservar original** o **Guardar igualmente**.

En el resultado, un toque en **Comparar** alterna de forma persistente entre **ORIGINAL** y **MEJORADA**. También puedes mantenerlo pulsado para consultar momentáneamente el original; una etiqueta dentro de la fotografía confirma qué versión estás viendo.

**Nuevo proyecto** libera la imagen actual y permite comenzar otra edición sin reiniciar la aplicación.

## IA local y privada

- **BSRGAN/NCNN** recibe la foto o el recorte con un margen de contexto del original, reconstruye texturas degradadas por desenfoque, ruido o JPEG y aplica un escalado automático ×2 o ×4. El recorte definitivo se realiza después de la inferencia.
- El modelo BSRGAN oficial se convirtió a NCNN y se verificó numéricamente frente a PyTorch antes de integrarlo en Android.
- Una protección final compara la reconstrucción con el original a resolución completa, limita desviaciones de color y aplica hasta tres pasadas acotadas de recuperación de detalle únicamente mientras el resultado sea más suave que la referencia.
- La puerta de calidad impide el guardado automático cuando no existe una mejora mínima o se detectan artefactos; la decisión final queda en manos del usuario.
- La referencia conserva su resolución completa durante la protección y la salida nunca tiene menos píxeles que la foto o el recorte de entrada.
- En Android 10–16, NCNN se ejecuta mediante JNI y procesamiento por bloques. Ya no se lanza un ejecutable auxiliar, eliminando la incompatibilidad que provocaba cierres y avisos de endurecimiento en Android 16.
- RT-Focuser y ONNX Runtime se retiraron: en las pruebas con fotografías reales añadían doble contorno y aumentaban el tamaño y el consumo de memoria sin mejorar el resultado.
- Si existe un recorte, UGscaler conserva un margen del original durante la inferencia y utiliza el recorte original a resolución completa como referencia de fidelidad.
- La escala máxima se decide a partir de la entrada, el límite del modelo y la memoria disponible.
- No requiere conexión: la fotografía no abandona el dispositivo.

## IA generativa opcional, local y gratuita

- La pestaña **Crear** permite generar desde texto o reinterpretar la foto/recorte con `img2img`.
- El motor es **stable-diffusion.cpp** con CPU/Vulkan, Flash Attention y VAE tiling.
- El modelo recomendado es DreamShaper 7 LCM Q4: descarga opcional de 1,51 GiB, 512 × 512, seis pasos LCM y CFG 1,5.
- La descarga se puede pausar y continuar, se verifica por SHA-256 y el botón cambia a **Actualizar** cuando el catálogo publica una versión superior.
- No hay API, créditos, suscripciones ni pagos por imagen. Una vez descargado, funciona sin conexión y sin cuotas de uso.
- El modelo se rige por CreativeML OpenRAIL-M; «sin cuotas» no elimina las restricciones legales de su licencia.

## Sin nube y sin costes por imagen

- UGscaler no incluye Gemini, Nano Banana, Stable Diffusion por API ni ningún proveedor cloud.
- Internet solo se usa para comprobar y descargar modelos opcionales; las fotos y prompts nunca se envían.
- No hay acceso con Google, Firebase, claves API, créditos ni compras integradas.
- Toda fotografía permanece en el teléfono y cada mejora es gratuita.

Consulta [PRIVACY.md](PRIVACY.md) para conocer el tratamiento local de las imágenes.

## Salida y compatibilidad

- Guardado sin pérdida en `Imágenes/UGscaler` con extensión `.png`.
- Android 8.0 o posterior (`minSdk 26`).
- APK optimizada para ARM64, incluido Redmi Note 13 Pro+ (`23090RA98G`).
- Runtime nativo actualizado y empaquetado alineado para dispositivos Android con páginas de memoria de 16 KB.
- Límites de decodificación y salida adaptados al heap disponible.
- Procesamiento cancelable y liberación explícita de bitmaps y tareas nativas.
- El original nunca se sobrescribe.

## Compilar y verificar

Requisitos: JDK 17, Android SDK 36, NDK 29.0.14206865 y CMake 3.31.6. La primera compilación descarga automáticamente el paquete oficial de NCNN y verifica su SHA-256.

```powershell
$env:ANDROID_HOME='C:\Android'
$env:ANDROID_SDK_ROOT='C:\Android'
.\gradlew.bat clean lintRelease testDebugUnitTest assembleRelease
```

La salida se genera en `app/build/outputs/apk/release/UGscaler-v1.6.7.apk` y la versión distribuible se copia a la raíz como `UGscaler-v1.6.7.apk`, sin `debug` en el nombre.

## Límites reales

La IA local mejora contornos, compresión y texturas recuperables, pero no puede reconstruir con certeza una cara o un objeto cuya información desapareció por un barrido extremo. En esos casos UGscaler prioriza no empeorar el original frente a inventar una identidad o geometría falsa.

## Licencia

El código propio se distribuye bajo [UGscaler Non-Commercial License 1.0](LICENSE). Los modelos, runtimes, servicios y librerías mantienen sus condiciones originales; consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
