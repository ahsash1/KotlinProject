# Voice-Guided Accessibility Overlay

An Android overlay that helps older and less confident smartphone users complete tasks in apps they
don't know how to navigate. The user says what they want — "make the text bigger" — and the overlay
walks them through it one step at a time, highlighting the exact thing to tap and reading each
instruction aloud.

Built in a JetBrains hackathon at NYU Abu Dhabi (September 2026). Placed 3rd of ~40 teams.

## How it works

The overlay runs as an Android `AccessibilityService`, so it can read the current screen's view
hierarchy and draw on top of whatever app is in the foreground — Settings, WhatsApp, anything.

1. Ask. The user taps a floating help button and speaks a task in plain language.
2. Match. `VoiceTaskMatcher` maps the spoken phrase to a known task from the task library.
3. Point. `GuidanceEngine` walks the task's steps against a live stream of screen snapshots,
   finding the element each step refers to and highlighting it while the step is spoken aloud.
4. Recover. If nothing on screen matches the current step for 8 seconds the engine reports
   `Stuck`; after 15 seconds it reports `Lost`, and an LLM is asked to work out where the user
   actually ended up and how to get them back on track.

## Design notes

The interesting constraint was keeping the guidance logic testable. `GuidanceEngine` is pure Kotlin
in `commonMain` — no Android imports, and it never reads the clock itself; callers pass the current
time in. That makes every state transition, including the timeout-driven `Stuck` and `Lost`
transitions, reproducible in a unit test with no device and no emulator.

The Android layer is kept to the parts that genuinely need a platform: the accessibility service,
the overlay window, speech recognition, and text-to-speech.

## Project layout

```
shared/src/commonMain/kotlin/.../guidance/   Engine, matchers, task definitions (pure Kotlin)
shared/src/commonTest/                       Unit tests for the engine and matchers
androidApp/src/main/kotlin/.../accessibility Screen reading and action performing
androidApp/src/main/kotlin/.../overlay       Floating help button and highlight rendering
androidApp/src/main/kotlin/.../speech        Speech recognition and text-to-speech
androidApp/src/main/kotlin/.../llm           LLM-backed recovery when the user gets lost
```

## Running it

Requires JDK 17+ and the Android SDK.

```bash
./gradlew :androidApp:assembleDebug
```

Install the APK, then enable the service under **Settings → Accessibility** and grant the
"draw over other apps" permission.

LLM-backed recovery is optional. To enable it, add keys to `local.properties` (gitignored, never
committed):

```properties
llmApiKey=your-anthropic-key
openAiApiKey=your-openai-key
```

Without keys the app runs normally; only the `Lost` recovery path is disabled.

## Tests

```bash
./gradlew :shared:jvmTest
```

16 tests covering the guidance state machine, confirmation matching, and voice-to-task matching.

## Status

Prototype built under hackathon time pressure. Known limitations:

- The task library is small and hand-written; tasks are matched against known phrasings rather than
  interpreted freely.
- Android only. The repo is a Kotlin Multiplatform project and `desktopApp/` is scaffolding from the
  template — there is no desktop implementation, since the overlay depends on Android accessibility
  APIs.
- Spoken output uses the device's default locale. Multi-language support depends on which TTS
  voices the device has installed.
