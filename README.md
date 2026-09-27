# deepAssist

An experimental Android voice assistant designed for Turkish speech and accessibility. It listens to a request, transcribes it with OpenAI, sends the text and available tool definitions to DeepSeek, performs the selected device action, and speaks the answer. The app can also keep conversation history and short memories on the device.

**Türkçe özet:** deepAssist; Türkçe sesli komutlarla arama, mesaj, cihaz denetimi ve web araması gibi işlemleri yapan deneysel bir Android asistanıdır. Kurulum ve güvenlik ayrıntıları aşağıdadır.

## What it does

- Voice input through OpenAI realtime transcription, with a file transcription fallback; spoken replies through OpenAI or Android TTS.
- Tool based actions for calls, SMS, contacts, WhatsApp, notifications, media, YouTube, timers, device controls, location, weather, exchange rates, and web search.
- Local conversation history, saved memories, and notification/message history.
- Optional Tavily search fallback. Web search also tries Exa and DuckDuckGo without a personal API key.

This is a prototype. Some actions depend on installed apps, Android version, granted permissions, and third party services. Review the permissions and source before installing it on a personal phone.

## Build and run

You need Android Studio with Android SDK 36 and JDK 17. The Gradle wrapper is included; the app supports Android 8.0 (API 26) and newer.

1. Clone the repository and open its root folder in Android Studio.
2. Copy `.env.example` to `.env` in the repository root. Fill in `DEEPSEEK_API_KEY` and `OPENAI_API_KEY`; `TAVILY_API_KEY` is optional. Use your own keys and plain `KEY=value` lines without quotes. The Android SDK path belongs in `local.properties` or your usual Android environment configuration, not in `.env`.
3. Sync Gradle, select the `app` configuration, and run it on a device. From a terminal, `./gradlew assembleDebug` builds the APK.
4. Grant only the permissions needed for the features you plan to use. The app's **Ayarlar** screen links to Android's permission and default assistant settings. It does not accept API keys; changing a key requires editing `.env`, rebuilding, and reinstalling the APK.

A build with empty keys can compile, but voice transcription and assistant replies require the OpenAI and DeepSeek keys. Keep `.env` on your own machine. Do not attach your personal build artifact to a public release.

## How it is organized

| Location | Purpose |
| --- | --- |
| `app/src/main/kotlin/com/deepassist/service/` | Foreground assistant, triggers, accessibility, and notification services |
| `app/src/main/kotlin/com/deepassist/llm/` | DeepSeek client, prompts, and tool call parsing |
| `app/src/main/kotlin/com/deepassist/speech/` | Speech recognition and speech output |
| `app/src/main/kotlin/com/deepassist/tools/` | Device and web actions available to the assistant |
| `app/src/main/kotlin/com/deepassist/data/` | Local history, memory, and app preferences |
| `app/src/main/AndroidManifest.xml` | Android components and requested permissions |
| `app/build.gradle.kts` | Android build and `.env` to `BuildConfig` mapping |

## Keys, endpoints, and data

`.env` and `local.properties` are ignored by Git. The build reads `.env` and embeds its values in `BuildConfig`; the app sends them directly to the relevant APIs. **An APK is not a secret store:** anyone with the APK can extract embedded keys. Use restricted, revocable keys with spending limits, and rotate them if you distribute a build or suspect exposure. A backend that keeps keys server side is needed if you want to distribute an APK without exposing those keys.

API endpoint URLs in the source identify public services; the URLs themselves are not credentials. Do not put tokens in URLs, source files, screenshots, issue reports, or logs. If a key was committed previously, deleting it in a later commit does not remove it from Git history: revoke it first.

The app sends voice/text and relevant tool requests to the selected external providers. Chat history, saved memories, and captured notifications/messages are stored in app private files on the phone; they are not encrypted by this project. Android backup is disabled. Granting notification, contacts, SMS, location, and accessibility access exposes sensitive device data to the app and potentially to a tool request. See the manifest and individual tool implementations for exact behavior.

## Contributing

Open an issue with reproduction steps and Android version, or send a focused pull request. Before submitting, run `./gradlew assembleDebug`, check `git status`, and confirm that no `.env`, `local.properties`, APK, or personal data is staged. There is no automated test suite yet.
