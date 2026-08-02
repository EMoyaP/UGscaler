# Privacidad de UGscaler

## Procesamiento local

El procesado se ejecuta íntegramente en el dispositivo. UGscaler no transmite la fotografía, el recorte ni el resultado a ningún servidor.

La aplicación no contiene Firebase, Gemini, inicio de sesión con Google, servicios cloud ni claves API. No se crean cuentas de usuario y no existe consumo facturable por procesar imágenes.

UGscaler solicita acceso a Internet exclusivamente para consultar el catálogo y descargar los modelos locales opcionales que el usuario elija. Las descargas se verifican mediante SHA-256 antes de activarse. Las fotografías, recortes, prompts y resultados no se envían durante ese proceso y, una vez instalado el modelo, la inferencia funciona sin conexión.

Android puede mostrar notificaciones locales de progreso y finalización mientras una tarea continúa en segundo plano. Estas notificaciones no contienen la fotografía ni el prompt y no transmiten información a servidores externos.

## Archivos resultantes

Los resultados se guardan automáticamente como PNG en la colección de imágenes del dispositivo. UGscaler nunca sobrescribe el archivo original.
