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
- **Anti-pausa**: YouTube pausa al ocultar la página; la app le reporta
  siempre visible, reanuda pausas no pedidas y un vigilante nativo cada
  2 s hace lo mismo aunque la página esté dormida. La pausa que TÚ
  pides (tap o notificación) sí se respeta.
- Si tu marca (Xiaomi/Oppo/Vivo/Huawei) igual lo corta: Ajustes >
  Batería > TubeLite > Sin restricciones.

## V2: foco de audio, diagnóstico y recuperación

- **Audio Focus**: llamadas y otras apps pausan (transitorio) o bajan
  volumen (duck); al volver el foco reanuda solo si la app pausó (nunca
  si pausaste tú).
- **Diag**: botón que muestra errores JS/nativos con hora (sin URLs ni
  títulos). Todo `evaluateJavascript` crítico registra resultado.
- **Ciclo**: `onPause`/`onResume` pausan solo con Fondo OFF (nunca en
  PiP); `onTaskRemoved` no detiene; sin referencias obsoletas.
- **Renderer**: `onRenderProcessGone` reconstruye el WebView con última
  URL+título+posición (mejor esfuerzo, respeta tu pausa).
- **Notificación multimedia**: play/pausa desde la barra, la pantalla
  de bloqueo y botones Bluetooth, con el título real del video.
- **Puente JS ↔ Java**: la página reporta play/pausa y título
  (`TubeBridge`), y nativo controla el video. Sincroniza notificación,
  lockscreen y MediaSession incluso con la actividad muerta.

## V2 media: órdenes PLAY / PAUSE / TOGGLE

- Administrador único de órdenes (`dispatch`): sesión, notificación,
  foco de audio y botones usan el mismo embudo.
- `PLAY`/`PAUSE` idempotentes + `TOGGLE`; la API JS `TubePlayback`
  (`play/pause/toggle/next/prev/getState`) comprueba el estado real y
  reporta si no hay video.
- El servicio NO voltea estado al pulsar: espera confirmación del
  reproductor (puente), con anti-duplicados y reconciliación a los 3 s
  (un reintento). Sin bucles ni notificaciones infinitas.
- Notificación propia con 5 acciones discretas: anterior, reproducir,
  pausar, siguiente, detener (+ tap abre la app).
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

## Módulo nativo (ExoPlayer)

Además del modo WebView, la app trae un reproductor nativo con
Media3/ExoPlayer (botones Nativo/Buscar en la segunda fila de controles):

- **Búsqueda nativa** sin WebView: canciones, videos o modo libre.
- **Traspaso WebView → nativo**: al apagar la pantalla con Fondo ON
  (o botón Audio), el video actual sigue en audio nativo ahorrando
  batería; →Web lo devuelve al WebView en la misma posición.
- **Audio/Video en caliente** sin perder la posición, **calidad**
  (Auto/1080p/720p/480p/360p), **velocidad** 0.5x–2x, **saltar
  silencios**, **sleep timer**, **cola + repetición** y **favoritos**.
- **Ecualizador** de bandas + Bass Boost + presets, y **visualizador
  FFT** de 36 bandas sin permisos extra.
- **Descargas**: audio M4A o video 360p MP4 con cola y biblioteca
  sin red (botón ⬇, búsqueda con pulsación larga, o pantalla DLs).
- **Carga con búfer**: arranque con 1.5 s, colchón de hasta 60 s y caché
  en disco (200 MB): lo ya visto no se descarga de nuevo.
- Segundo plano con MediaSession: notificación, lockscreen y
  Bluetooth. Solo un modo suena a la vez (WebView o nativo).

Dependencias solo del módulo nativo: Media3 1.4.1, Gson, corrutinas.
Sin FFmpeg a propósito: el audio se guarda en M4A directo (reproducible)
y el video usa el stream progresivo oficial, así el APK no crece 30-50 MB.

## Licencia y créditos

GPL-3.0-only (ver `LICENSE`). El módulo nativo (extractor Innertube,
reproductor, ecualizador, visualizador, descargas) está portado de
[mUzVidX](https://github.com/Spartan117x/mUzVidX) (GPL-3.0), con
adaptaciones: sin FFmpeg, servicio MediaSession y traspaso híbrido.
