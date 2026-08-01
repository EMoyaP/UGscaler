# UGscaler

Aplicación Android para restaurar fotografías desenfocadas y reescalar recortes
mediante inteligencia artificial local. Las imágenes nunca abandonan el teléfono.

## UGscaler 1.5.0

La interfaz está diseñada para utilizarse cómodamente con una mano:

1. Pulsa **Subir foto**.
2. Opcionalmente, pulsa **Recortar**. Durante el ajuste se bloquea el desplazamiento
   vertical; el botón cambia a **Aceptar**.
3. Pulsa **Mejorar con IA**.
4. UGscaler muestra el porcentaje, guarda el resultado automáticamente como PNG y
   abre una vista con las acciones **Comparar**, **Compartir** y **Cerrar**.

El botón **Nuevo proyecto** libera la imagen actual y permite empezar de nuevo sin
cerrar la aplicación.

## Restauración local

- **RT-Focuser INT8/FP32** corrige desenfoque de movimiento mediante ventanas de
  256 × 256 px solapadas. Este método mantiene acotado el consumo de memoria y
  evita líneas visibles entre bloques.
- **Real-ESRGAN/NCNN** reconstruye detalle y aplica un escalado automático ×2 o ×4.
- Para un recorte, UGscaler conserva un margen de la foto original durante el
  deblurring y extrae después la región exacta. Así, los bordes del recorte no se
  procesan sin contexto.
- La escala máxima se decide con la resolución de entrada, el límite ×4 del modelo
  y la memoria disponible del dispositivo.

La salida siempre se guarda sin pérdida en `Imágenes/UGscaler` con extensión
`.png`. Ningún modelo requiere conexión ni descarga posterior.

## Compatibilidad y estabilidad

- Android 8.0 o posterior (`minSdk 26`).
- Dispositivos ARM64.
- Diseño adaptable a la altura y densidad de la pantalla.
- Límites de decodificación, deblurring y salida calculados según el heap del
  teléfono.
- Procesamiento cancelable y liberación explícita de bitmaps, sesiones ONNX y
  procesos NCNN.
- El original nunca se sobrescribe.

## Compilar

Requisitos: JDK 17 y Android SDK 35.

```powershell
$env:ANDROID_HOME='C:\Users\uge\Android\Sdk'
.\gradlew.bat clean lintDebug testDebugUnitTest assembleDebug
```

La salida se genera como
`app/build/outputs/apk/debug/UGscaler-v1.5.0.apk`. La APK distribuible se copia
también a la raíz como `UGscaler-v1.5.0.apk`, sin la palabra `debug` en el nombre.

## Límites reales

La IA puede recuperar contornos y texturas plausibles, pero no puede garantizar
detalles que el sensor nunca capturó. En desenfoques extremos, UGscaler prioriza
preservar la estructura original frente a inventar rasgos personales.

## Licencia

El código propio se distribuye bajo
[UGscaler Non-Commercial License 1.0](LICENSE). Los modelos, runtimes y librerías
mantienen sus licencias originales; consulta
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
