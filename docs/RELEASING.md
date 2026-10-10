# Publicar RankOffline

## Firma local (solo una vez)

> **ADVERTENCIA: NO pierdas el keystore ni sus contraseñas.** Si se pierde la signing key, las versiones futuras no podrán actualizar instalaciones firmadas con esa clave. Conserva una copia de seguridad segura fuera del repositorio.

La clave privada debe permanecer exclusivamente en tu Mac. No la subas a GitHub, no la añadas a Actions y no la copies al repositorio.

1. Crea el keystore de forma interactiva, desde una terminal en una ubicación privada fuera del repositorio:

   ```bash
   keytool -genkeypair -v -keystore RankOffline-release.jks -alias rankoffline -keyalg RSA -keysize 4096 -validity 10000
   ```

   `keytool` pedirá las contraseñas sin incluirlas en el comando. Muévelo después a una ubicación privada y respaldada si lo creaste en el directorio actual.

2. Copia `keystore.properties.example` como `keystore.properties` y completa sus cuatro valores. El archivo real está ignorado por Git. `storeFile` puede ser una ruta absoluta o una ruta relativa a la raíz del proyecto.

3. No reutilices la debug key. Todas las publicaciones deben conservar el mismo keystore y el alias `rankoffline`.

## Iconos de launcher

RankOffline incluye cinco iconos finales seleccionables (Dark, Blue, Red, Green y Yellow). Las fuentes PNG se usan como iconos legacy seguros porque no contienen capas foreground/background separadas. En el futuro se pueden generar variantes adaptive con Android Studio Image Asset Studio si se proporcionan esas capas, sin rediseñar los originales.

Algunos launchers pueden tardar unos segundos en reflejar un cambio de icono. La aplicación utiliza las APIs normales de Android y no fuerza el reinicio del launcher.

## Checklist de cada versión

1. Actualiza `versionName` en `app/build.gradle.kts`.
2. Incrementa siempre `versionCode`. Nunca reutilices ni reduzcas uno ya publicado. Por ejemplo: v0.1.0 → 1, v0.1.1 → 2, v0.2.0 → 3.
3. Ejecuta los tests: `./gradlew testDebugUnitTest`.
4. Ejecuta `tools/build_release.sh`. Lee la versión desde Gradle, construye el release firmado y lo copia a `dist/RankOffline-vX.Y.Z.apk`.
5. Verifica el APK con las herramientas Android disponibles:

   ```bash
   aapt dump badging dist/RankOffline-vX.Y.Z.apk | head -1
   apksigner verify --verbose --print-certs dist/RankOffline-vX.Y.Z.apk
   ```

   Confirma `package: name='org.rankoffline.app'`, `versionCode`, `versionName` y una firma válida. No publiques huellas o datos de firma innecesariamente.
6. Instala y prueba el APK en un dispositivo real, incluida la actualización sobre la versión anterior cuando exista.
7. Crea y sube el tag solo después de revisar los cambios:

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

8. Crea una GitHub Release con el mismo tag.
9. Adjunta exclusivamente el APK público, por ejemplo:

   ```bash
   gh release create v0.1.0 \
     dist/RankOffline-v0.1.0.apk \
     --title "RankOffline v0.1.0" \
     --notes-file docs/release-notes-v0.1.0.md
   ```

10. Añade o actualiza el repositorio en Obtainium y comprueba que detecta e instala la nueva versión.

No ejecutes la publicación desde GitHub Actions: el build y la firma de release se realizan localmente para que el keystore nunca salga de tu Mac.
