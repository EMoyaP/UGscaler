# UGscaler

Aplicación Android para recortar y mejorar fotografías localmente con Real-ESRGAN + NCNN.

UGscaler está pensada para recuperar detalle después de recortar una foto: procesa el recorte en el propio dispositivo, compara el original con el resultado y permite exportar una copia JPG.

## Funciones

- Mejora IA local sin subir imágenes a ningún servidor.
- Backend nativo Real-ESRGAN/NCNN para ARM64.
- GPU Vulkan cuando está disponible y fallback automático a CPU.
- Motor local de respaldo para dispositivos incompatibles.
- Presets Auto, Retrato, Paisaje y Texto.
- Controles de reducción de ruido, detalle y enfoque.
- Escalado 2× y 4×.
- Visor antes/después con divisor deslizable.
- Cámara, galería, corrección EXIF y guardado en `Imágenes/UGscaler`.

## Descargar

El APK de demostración está en la raíz como [`UGscaler-debug.apk`](UGscaler-debug.apk).

Es una build debug para pruebas. Antes de distribuirla en Google Play habría que generar una build release firmada y completar la revisión de licencias, privacidad y políticas de la tienda.

## Compilar

Requisitos:

- JDK 17.
- Android SDK con Android 35.
- Gradle 8.7 o el wrapper incluido.

```powershell
.\gradlew.bat assembleDebug
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`.

El modelo y el backend ocupan aproximadamente 50 MB dentro del APK. En el primer uso se extraen al almacenamiento privado de la aplicación.

## Limitaciones conocidas

- La mejora generativa no puede reconstruir con certeza información que nunca fue capturada; puede inventar textura en zonas muy degradadas.
- El backend incluido es ARM64. En otros ABIs se utiliza el motor local de respaldo.
- La ejecución real de GPU depende del driver Vulkan del dispositivo.
- El APK actual es debug y no está optimizado para publicación comercial.

## Licencia

El código propio de UGscaler se distribuye bajo la [UGscaler Non-Commercial License 1.0](LICENSE). Permite uso personal, educativo y de investigación, pero no uso comercial sin autorización previa.

Los componentes de terceros mantienen sus propias licencias. Consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) y los avisos incluidos con el backend.
