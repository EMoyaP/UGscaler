# UGscaler

Aplicación Android para restaurar, recortar y reescalar fotografías mediante IA completamente local, sin cuentas, cuotas, suscripciones ni costes por imagen.

## UGscaler 1.6.5

La interfaz está diseñada para pantallas de smartphone y uso con una mano:

1. Pulsa **Subir foto**.
2. Opcionalmente, pulsa **Recortar**. Mientras ajustas el marco se bloquea el desplazamiento vertical y el botón cambia a **Aceptar**.
3. Pulsa **Mejorar con IA**.
4. UGscaler muestra el porcentaje, guarda automáticamente el resultado como PNG y abre una vista con **Comparar**, **Compartir** y **Cerrar**.

**Nuevo proyecto** libera la imagen actual y permite comenzar otra edición sin reiniciar la aplicación.

## IA local y privada

- Un análisis de enfoque decide si la foto necesita **RT-Focuser**; las imágenes ya nítidas evitan esa pasada para no perder microdetalle.
- Cuando existe desenfoque real, **RT-Focuser** lo corrige mediante ventanas solapadas de 256 × 256 px para mantener acotado el uso de memoria.
- **Real-ESRGAN/NCNN** recibe la fuente sin preenfoque, reconstruye detalle y aplica un escalado automático ×2 o ×4.
- Una protección final transfiere principalmente detalle de luminancia, limita cambios de color y evita recortar nuevas luces o sombras. El original actúa como referencia de calidad también después de un recorte.
- La referencia conserva su resolución completa durante la protección y la salida nunca tiene menos píxeles que la foto o el recorte de entrada.
- En Android 10–16, el ejecutable NCNN se instala como componente nativo de solo lectura; no se ejecuta código desde la carpeta escribible de la app. Esto corrige el cierre de HyperOS al comenzar Real-ESRGAN.
- Las clases JNI de ONNX Runtime quedan excluidas de la ofuscación R8, evitando el aborto nativo `GetMethodID` al obtener la salida de RT-Focuser en Android 16.
- Si existe un recorte, UGscaler conserva contexto de la foto original durante el deblurring y extrae después la región solicitada.
- La escala máxima se decide a partir de la entrada, el límite del modelo y la memoria disponible.
- No requiere conexión: la fotografía no abandona el dispositivo.

## Sin nube y sin costes

- UGscaler no incluye Gemini, Nano Banana, Stable Diffusion por API ni ningún proveedor cloud.
- La APK no solicita permiso de Internet.
- No hay acceso con Google, Firebase, claves API, créditos ni compras integradas.
- Toda fotografía permanece en el teléfono y cada mejora es gratuita.
- Los modelos generativos locales comparables a Nano Banana fueron descartados porque requieren varios GB y no ofrecen una experiencia móvil estable en el hardware objetivo.

Consulta [PRIVACY.md](PRIVACY.md) para conocer el tratamiento local de las imágenes.

## Salida y compatibilidad

- Guardado sin pérdida en `Imágenes/UGscaler` con extensión `.png`.
- Android 8.0 o posterior (`minSdk 26`).
- APK optimizada para ARM64, incluido Redmi Note 13 Pro+ (`23090RA98G`).
- Runtime nativo actualizado y empaquetado alineado para dispositivos Android con páginas de memoria de 16 KB.
- Límites de decodificación y salida adaptados al heap disponible.
- Procesamiento cancelable y liberación explícita de bitmaps, sesiones ONNX y procesos NCNN.
- El original nunca se sobrescribe.

## Compilar y verificar

Requisitos: JDK 17 y Android SDK 36.

```powershell
$env:ANDROID_HOME='C:\Users\uge\Android\Sdk'
.\gradlew.bat clean lintRelease testDebugUnitTest assembleRelease
```

La salida se genera en `app/build/outputs/apk/release/UGscaler-v1.6.5.apk` y la versión distribuible se copia a la raíz como `UGscaler-v1.6.5.apk`, sin `debug` en el nombre.

## Límites reales

La IA local mejora contornos y texturas recuperables, pero no puede recrear con certeza información que el sensor nunca capturó. UGscaler prioriza fidelidad, privacidad y coste cero sobre la invención generativa de detalles.

## Licencia

El código propio se distribuye bajo [UGscaler Non-Commercial License 1.0](LICENSE). Los modelos, runtimes, servicios y librerías mantienen sus condiciones originales; consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
