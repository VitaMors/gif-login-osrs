# GIF Login Screen

GIF Login Screen is a RuneLite plugin that plays a looping GIF as the login screen background while keeping the normal Old School RuneScape login UI visible.

## Usage

Place your GIF at:

```text
%USERPROFILE%\.runelite\login.gif
```

Then enable `GIF Login Screen` in RuneLite.

The plugin only runs on the login screen. It restores RuneLite's normal login background behavior when disabled or when leaving the login screen.

## Settings

- GIF path
- Fallback FPS
- Stretch

The plugin uses the GIF's own frame timing. `Fallback FPS` is only used when a GIF frame has no timing metadata. For best results, use a looping GIF with a 16:9-style frame. Large GIFs work, but smaller files will load faster and use less memory.
