# TubeLite — YouTube sin anuncios (WebView)

App Android mínima (`com.nexos.tubelite`) que abre YouTube móvil en un
WebView con triple filtro:

1. **Bloqueo de hosts** (`shouldInterceptRequest`): los dominios de
   publicidad y rastreo (`doubleclick.net`, `googlesyndication.com`,
   `google-analytics.com`, `adservice.google.com`, `ads.youtube.com`)
   se responden vacíos. Esto quita banners y rastreo.
2. **Auto-skip con JS**: cada 0.5 s pulsa el botón "Omitir anuncio" si
   aparece, y si hay anuncio de video lo pone mudo a 16x para que pase
   rápido. Los anuncios de video salen de los mismos hosts que el video,
   por eso no se pueden bloquear por host y se aceleran.
3. **CSS**: oculta contenedores de anuncios y banners.

Además: pantalla completa para video, botón atrás navega el historial,
sin dependencias externas.

## Segundo plano, PiP y controles

- **Fondo ON**: foreground inmediato tipo `mediaPlayback`, STICKY y
  wake lock parcial: el audio sigue con la app cerrada, el móvil
  bloqueado o la app barrida de recientes. El WebView se aparca en la
  aplicación al morir la actividad (atrás minimiza en vez de cerrar) y
  al reabrir sigue donde iba; si el proceso muere, se restaura la
  última URL.
- **Notificación multimedia**: play/pausa desde la barra, la pantalla
  de bloqueo y botones Bluetooth, con el título real del video.
- **Puente JS ↔ Java**: la página reporta play/pausa y título
  (`TubeBridge`), y nativo controla el video. Sincroniza notificación,
  lockscreen y MediaSession incluso con la actividad muerta.
- **PiP**: botón flotante o automático al salir con video en pantalla
  completa (requiere Android 8+).

## Blocklist actualizable

La lista de bloqueo vive en `blocklist.json` (raíz del repo): la app
la descarga al iniciar y guarda caché. Si YouTube cambia algo, basta
editar ese JSON y subirlo: sin compilar APK nuevo.

## Limitaciones honestas

- YouTube cambia su web seguido y combate bloqueadores: algún selector
  puede dejar de funcionar y verse un aviso anti-adblock. Avisar y se
  ajusta.
- Requiere conexión y WebView del sistema actualizado.

## Compilar y obtener el APK

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

O sube a GitHub: cada push compila solo (**Actions → Build APK
(debug) → Artifacts → app-debug**).

## Versiones fijadas

- Java 17 (Temurin), Gradle 8.9, AGP 8.5.2, Kotlin 1.9.24
- `compileSdk`/`targetSdk` 34, `minSdk` 24 (Android 7.0+, ARM64)
