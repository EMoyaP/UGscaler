# UGscaler

Aplicacion Android para recuperar detalle de fotografias y fotogramas de video localmente.

UGscaler esta pensada para recuperar detalle despues de recortar una foto o seleccionar un fotograma borroso de un video. Procesa el contenido en el propio dispositivo, compara el original con el resultado y permite exportar una copia JPG.

## Funciones

- Mejora IA local sin subir imagenes ni videos a ningun servidor.
- Ventanas independientes para Foto y Video.
- Selector temporal de video con analisis de 7 fotogramas vecinos y eleccion automatica del mas nitido.
- Backend nativo Real-ESRGAN/NCNN para ARM64.
- GPU Vulkan cuando esta disponible y fallback automatico a CPU.
- Motor local de respaldo para dispositivos incompatibles.
- Presets Auto, Retrato, Paisaje y Texto.
- Controles de reduccion de ruido, detalle y enfoque.
- Escalado 2x y 4x.
- Visor antes/despues con divisor deslizable.
- Camara, galeria, videos, correccion EXIF y guardado en `Imagenes/UGscaler`.
- Boton Nuevo y boton Atras para volver a elegir otro archivo sin cerrar la aplicacion.

## Pipeline de restauracion

Para fotos se ejecuta un pre-procesado de deblur adaptativo y despues Real-ESRGAN para reconstruccion y escalado. Para video se extraen siete fotogramas alrededor del punto seleccionado, se puntua su nitidez local y se procesa el mejor candidato. Esta estrategia es ligera y funciona sin red, pero no sustituye a un modelo temporal completo.

Los proyectos de investigacion revisados para futuras variantes son [MISCFilter](https://github.com/ChengxuLiu/MISCFilter), [Restormer](https://github.com/swz30/Restormer), [NAFNet](https://github.com/megvii-research/NAFNet), [RVRT](https://github.com/JingyunLiang/RVRT), [VRT](https://github.com/JingyunLiang/VRT) y [BasicVSR++](https://github.com/ckkelvinchan/BasicVSR_PlusPlus). Sus pesos y runtimes no se incluyen en este APK porque requieren conversion, cuantizacion, memoria y licencias independientes. No se presentan como motores activos hasta superar esa integracion y QA en dispositivo.

## Descargar

La version actual es `1.1.0` y el APK de demostracion esta en la raiz como [`UGscaler-v1.1.0-debug.apk`](UGscaler-v1.1.0-debug.apk).

Es una build debug para pruebas. Antes de distribuirla en Google Play habria que generar una build release firmada y completar la revision de licencias, privacidad y politicas de la tienda.

## Compilar

Requisitos:

- JDK 17.
- Android SDK con Android 35.
- Gradle 8.7 o el wrapper incluido.

```powershell
.\gradlew.bat assembleDebug
```

El APK se genera en `app/build/outputs/apk/debug/UGscaler-v1.1.0-debug.apk`.

El modelo y el backend ocupan aproximadamente 50 MB dentro del APK. En el primer uso se extraen al almacenamiento privado de la aplicacion.

## Limitaciones conocidas

- La mejora generativa no puede reconstruir con certeza informacion que nunca fue capturada; puede inventar textura en zonas muy degradadas.
- El backend incluido es ARM64. En otros ABIs se utiliza el motor local de respaldo.
- La ejecucion real de GPU depende del driver Vulkan del dispositivo.
- El modo video exporta el fotograma recuperado como JPG; no re-renderiza todavia el video completo.
- El APK actual es debug y no esta optimizado para publicacion comercial.

## Licencia

El codigo propio de UGscaler se distribuye bajo la [UGscaler Non-Commercial License 1.0](LICENSE). Permite uso personal, educativo y de investigacion, pero no uso comercial sin autorizacion previa.

Los componentes de terceros mantienen sus propias licencias. Consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) y los avisos incluidos con el backend.
