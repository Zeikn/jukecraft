# Jukeraft

A Fabric mod for Minecraft 1.21.1 that adds an always-on, draggable floating
music widget — a 1:1 in-game recreation of the [ytmfloat](ytmfloat/) /
[spotifloat](spotifloat/) companion-app widget — opened with **Right Shift**.
Switch between YouTube Music and Spotify live, in-game, without restarting.

## How it connects

The widget talks to the real browser extension over the same WebSocket
protocol the Electron companion apps use (`ws://127.0.0.1:38214` for YTM,
`ws://127.0.0.1:38215` for Spotify). It works standalone: if no companion
app is running, the mod hosts a minimal embedded WebSocket server on that
same port itself, so the unmodified browser extension can connect straight
to Minecraft. If a companion app *is* running, the mod connects to it
instead — either one works, whichever is up first.

## Controls

- **Right Shift** — show the widget / open it for dragging.
- **Right Shift** again, or **Esc** — close the draggable overlay (the
  widget itself stays visible everywhere: gameplay, inventory, chat, pause
  menu).
- Drag the card to move it; drag the FX panel separately to move it.
- Click the provider button (top-right of the card) to switch between YTM
  and Spotify in real time; each keeps its own independent FX settings.

## Project layout

- `src/client/java/com/jukeraft/client/JukeraftClient.java` — entrypoint;
  registers the keybinding, core shader, and render/input hooks.
- `src/client/java/com/jukeraft/client/render/` — the SDF rounded-rect draw
  helper and icon texture rendering.
- `src/client/java/com/jukeraft/client/gui/DraggableMenuScreen.java` — the
  drag-mode `Screen`.
- `src/client/java/com/jukeraft/client/music/` — the widget itself
  (`MusicWidget`, `FxPanel`), the bridge client (`BridgeClient`,
  `EmbeddedBridgeServer`), and the per-provider theme (`Provider`).
- `src/client/resources/assets/jukeraft/shaders/core/` — the GLSL core
  shader (`.json` descriptor, `.vsh`, `.fsh`).
- `ytmfloat/`, `spotifloat/` — the reference browser-extension + companion-app
  projects this mod mirrors.

## Building

```
./gradlew build
```

The output jar is written to `build/libs/jukeraft-<version>.jar`.

## Running in a dev environment

```
./gradlew runClient
```

This requires a Java 21 JDK (the Gradle wrapper will select it via the
toolchain if one is installed).
