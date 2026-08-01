# Privacidad de UGscaler

## Modo IA local

El procesado se ejecuta íntegramente en el dispositivo. UGscaler no transmite la fotografía, el recorte ni el resultado a ningún servidor.

## Modo IA generativa

Este modo solo se ejecuta después de que el usuario seleccione expresamente la pestaña **IA generativa** y pulse **Mejorar con IA**. Para proporcionar contexto de restauración, la aplicación envía directamente a la API de Gemini:

- la zona que se desea mejorar;
- una copia reducida de la fotografía original;
- instrucciones técnicas de restauración.

La comunicación usa HTTPS mediante Firebase AI Logic y no pasa por servidores propios de UGscaler. El tratamiento posterior por Google se rige por las condiciones y políticas aplicables a Firebase, Gemini API y la cuenta Google utilizada.

## Acceso con Google

UGscaler usa Firebase Authentication y el selector oficial de cuentas de Android. La aplicación recibe un token temporal para iniciar la sesión; no recibe ni almacena la contraseña de Google. Firebase conserva el identificador de usuario y los datos básicos del perfil facilitados por Google, como nombre y correo electrónico, para mantener la sesión y autorizar solicitudes a Firebase AI Logic.

El usuario puede cerrar sesión desde la pestaña generativa. También puede borrar los datos de UGscaler o desinstalar la aplicación para eliminar la sesión local. La eliminación de la cuenta registrada en Firebase debe solicitarse al responsable del repositorio.

La configuración cliente de Firebase incluida en la app identifica el proyecto, pero no es una contraseña ni concede por sí sola acceso a Gemini: Firebase AI Logic exige una sesión autenticada.

## Archivos resultantes

Los resultados se guardan automáticamente como PNG en la colección de imágenes del dispositivo. UGscaler nunca sobrescribe el archivo original.
