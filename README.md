# Solo Music Player

Solo Music Player is a Windows desktop application built as a standalone Kotlin/JVM and Compose Desktop app. It provides local playback and music-library management without Android framework dependencies.

## Implemented desktop features

- Local audio import and recursive folder scan, with embedded title, artist, album, duration, and artwork metadata where available.
- Persistent local library, favorites, playlists, play counts, and recently played history.
- Local playback queue with play/pause, previous/next, seek, volume, sequential play, repeat-one, repeat-all, shuffle, and shuffle plus repeat-all.
- Anonymous YouTube Music catalog search and country aware chart recommendations, with language and composer preferences. Recommendations use live catalog search results rather than bundled sample tracks.
- Online playback buffered through a temporary local audio cache, plus local playback, queue controls, shuffle, and repeat.
- Search across local track titles, artists, and albums; library, playlist, and listening-history views.
- Editable playlists with artwork, add/remove songs, play all, shuffle, rename, delete, and custom ordering.
- Synchronized local `.lrc` lyrics, keyboard shortcuts, and Solo-branded Windows installer configuration.

Keyboard shortcuts: **Space** toggles playback, **Ctrl+←/→** changes tracks, **Ctrl+K** focuses search, **Ctrl+L** toggles lyrics, and **Ctrl+O** opens the audio-file picker. Shortcuts yield to text entry; **Tab** remains normal focus navigation.

## Platform scope

The desktop build focuses on local library management and playback, along with catalog search and streaming features available in the Windows app. Mobile-only integrations and platform-specific services are outside this desktop build.

## Build and run

JDK 21 or newer is required. The build uses the installed JDK and emits Java 21-compatible bytecode, so a JDK 24 installation works without a separate JDK 21 toolchain.

```powershell
.\gradlew.bat run
.\gradlew.bat packageMsi
 .\gradlew.bat packageExe
```

The MSI and EXE are Windows installers that bundle the app runtime; users do not need a separate JDK. The installer creates a Start Menu entry and desktop shortcut, supports choosing the installation folder, and adds an uninstall entry to Windows Apps. Installers are written under `build/compose/binaries/main/`. Build on Windows with a full JDK 21+ and Visual Studio C++ build tools (needed for the in-process Windows media controls). The app stores its library data and extracted artwork under `%APPDATA%\SoloMusicPlayer`.

