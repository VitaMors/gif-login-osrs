# GIF Login Screen

GIF Login Screen is a RuneLite plugin that plays a looping GIF as the login screen background while keeping the normal Old School RuneScape login UI visible.

## Usage

Place your GIF at:

```text
%USERPROFILE%\.runelite\gif-login\login.gif
```

Then enable `GIF Login Screen` in RuneLite. The plugin creates the `gif-login` folder automatically and only accesses files inside that plugin-specific folder.

The plugin only runs on the login screen. It restores RuneLite's normal login background behavior when disabled or when leaving the login screen.

## Settings

- Fallback FPS
- Stretch

The plugin streams the GIF on a background thread and keeps at most three decoded frames in memory. It uses the GIF's own frame timing; `Fallback FPS` is only used when a frame has no timing metadata.

With `Stretch` enabled, each frame is stretched to the full login canvas. With it disabled, the aspect ratio is preserved and excess edges are cropped. GIFs whose decoded source frame exceeds the 32 MiB safety limit are rejected with a warning.
