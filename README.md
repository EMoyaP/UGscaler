# UGscaler

Aplicación Android para restaurar, recortar y reescalar fotografías con dos flujos de trabajo: IA completamente local y restauración generativa opcional mediante Gemini.

## UGscaler 1.6.3

La interfaz está diseñada para pantallas de smartphone y uso con una mano:

1. Pulsa **Subir foto**.
2. Opcionalmente, pulsa **Recortar**. Mientras ajustas el marco se bloquea el desplazamiento vertical y el botón cambia a **Aceptar**.
3. Elige **IA local** o **IA generativa**.
4. Pulsa **Mejorar con IA**.
5. UGscaler muestra el porcentaje, guarda automáticamente el resultado como PNG y abre una vista con **Comparar**, **Compartir** y **Cerrar**.

**Nuevo proyecto** libera la imagen actual y permite comenzar otra edición sin reiniciar la aplicación.

## IA local y privada

- Un análisis de enfoque decide si la foto necesita **RT-Focuser**; las imágenes ya nítidas evitan esa pasada para no perder microdetalle.
- Cuando existe desenfoque real, **RT-Focuser** lo corrige mediante ventanas solapadas de 256 × 256 px para mantener acotado el uso de memoria.
- **Real-ESRGAN/NCNN** recibe la fuente sin preenfoque, reconstruye detalle y aplica un escalado automático ×2 o ×4.
- Una protección final transfiere principalmente detalle de luminancia, limita cambios de color y evita recortar nuevas luces o sombras. El original actúa como referencia de calidad también después de un recorte.
- En Android 10–16, el ejecutable NCNN se instala como componente nativo de solo lectura; no se ejecuta código desde la carpeta escribible de la app. Esto corrige el cierre de HyperOS al comenzar Real-ESRGAN.
- Las clases JNI de ONNX Runtime quedan excluidas de la ofuscación R8, evitando el aborto nativo `GetMethodID` al obtener la salida de RT-Focuser en Android 16.
- Firebase App Check usa Play Integrity con la huella SHA-256 del APK para validar las solicitudes de IA generativa, también en instalaciones distribuidas fuera de Google Play.
- Si existe un recorte, UGscaler conserva contexto de la foto original durante el deblurring y extrae después la región solicitada.
- La escala máxima se decide a partir de la entrada, el límite del modelo y la memoria disponible.
- No requiere conexión: la fotografía no abandona el dispositivo.

## IA generativa opcional

La pestaña **IA generativa** utiliza Firebase AI Logic con `gemini-3.1-flash-image` (Nano Banana 2). Envía el recorte y una copia reducida del original como contexto para reconstruir detalles manteniendo encuadre, pose e identidad todo lo posible.

- Cada usuario se identifica con su propia cuenta mediante el selector oficial de Google para Android.
- Firebase Authentication crea un identificador de usuario independiente; la app no solicita ni almacena contraseñas ni claves Gemini.
- Firebase AI Logic está configurado para rechazar solicitudes sin credenciales válidas de Firebase Authentication.
- La cuota de Gemini pertenece al proyecto Firebase de UGscaler y es compartida por todos los usuarios; una cuenta Google no aporta cuota ilimitada.
- Los modelos Gemini de generación de imágenes exigen asociar el proyecto al plan Blaze. La integración está preparada, pero no se activa facturación automáticamente.
- No existe un servidor propio intermedio de UGscaler.
- La restauración generativa puede producir detalles plausibles que no estaban presentes en el archivo original. La aplicación lo indica antes de usarla y en el resultado.

Consulta [PRIVACY.md](PRIVACY.md) antes de usar este modo.

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

La salida se genera en `app/build/outputs/apk/release/UGscaler-v1.6.3.apk` y la versión distribuible se copia a la raíz como `UGscaler-v1.6.3.apk`, sin `debug` en el nombre.

## Límites reales

La IA local mejora contornos y texturas recuperables, pero no puede recrear con certeza información que el sensor nunca capturó. La IA generativa puede reconstruirla de forma visualmente convincente, aunque esos detalles son estimaciones y no evidencia fiel de la escena original.

La autenticación reduce el abuso, pero las cuotas y costes cloud siguen siendo globales para el proyecto. Para una distribución pública se recomienda publicar mediante Google Play, habilitar Play Integrity/App Check y definir límites operativos antes de activar Blaze.

## Licencia

El código propio se distribuye bajo [UGscaler Non-Commercial License 1.0](LICENSE). Los modelos, runtimes, servicios y librerías mantienen sus condiciones originales; consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
