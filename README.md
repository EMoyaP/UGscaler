# UGscaler

Aplicación Android para restaurar, recortar y reescalar fotografías mediante IA completamente local, sin cuentas, cuotas, suscripciones ni costes por imagen.

## UGscaler 1.6.6

La interfaz está diseñada para pantallas de smartphone y uso con una mano:

1. Pulsa **Subir foto**.
2. Opcionalmente, pulsa **Recortar**. Mientras ajustas el marco se bloquea el desplazamiento vertical y el botón cambia a **Aceptar**.
3. Pulsa **Mejorar con IA**.
4. UGscaler muestra el porcentaje, guarda automáticamente el resultado como PNG y abre una vista con **Comparar**, **Compartir** y **Cerrar**.

**Nuevo proyecto** libera la imagen actual y permite comenzar otra edición sin reiniciar la aplicación.

## IA local y privada

- **BSRGAN/NCNN** recibe la foto o el recorte sin preenfoque, reconstruye texturas degradadas por desenfoque, ruido o JPEG y aplica un escalado automático ×2 o ×4.
- El modelo BSRGAN oficial se convirtió a NCNN y se verificó numéricamente frente a PyTorch antes de integrarlo en Android.
- Una protección final compara la reconstrucción con el original a resolución completa. En imágenes recuperables transfiere el 90 % del resultado neuronal; si detecta una desviación anómala o barrido severo, reduce automáticamente la intensidad para evitar deformaciones.
- La referencia conserva su resolución completa durante la protección y la salida nunca tiene menos píxeles que la foto o el recorte de entrada.
- En Android 10–16, el ejecutable NCNN se instala como componente nativo de solo lectura; no se ejecuta código desde la carpeta escribible de la app. Esto evita el cierre de HyperOS al comenzar la inferencia.
- RT-Focuser y ONNX Runtime se retiraron: en las pruebas con fotografías reales añadían doble contorno y aumentaban el tamaño y el consumo de memoria sin mejorar el resultado.
- Si existe un recorte, UGscaler utiliza ese recorte como entrada y conserva la región original a resolución completa como referencia de fidelidad.
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
- Procesamiento cancelable y liberación explícita de bitmaps y procesos NCNN.
- El original nunca se sobrescribe.

## Compilar y verificar

Requisitos: JDK 17 y Android SDK 36.

```powershell
$env:ANDROID_HOME='C:\Users\uge\Android\Sdk'
.\gradlew.bat clean lintRelease testDebugUnitTest assembleRelease
```

La salida se genera en `app/build/outputs/apk/release/UGscaler-v1.6.6.apk` y la versión distribuible se copia a la raíz como `UGscaler-v1.6.6.apk`, sin `debug` en el nombre.

## Límites reales

La IA local mejora contornos, compresión y texturas recuperables, pero no puede reconstruir con certeza una cara o un objeto cuya información desapareció por un barrido extremo. En esos casos UGscaler prioriza no empeorar el original frente a inventar una identidad o geometría falsa.

## Licencia

El código propio se distribuye bajo [UGscaler Non-Commercial License 1.0](LICENSE). Los modelos, runtimes, servicios y librerías mantienen sus condiciones originales; consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
