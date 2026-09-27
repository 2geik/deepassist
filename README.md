# deepAssist

deepAssist is an experimental Android voice assistant I developed for people who are blind or have low vision. Its voice first design aims to make everyday phone tasks possible with less screen navigation. It listens to a request, transcribes it, uses an AI model to decide which available actions to take, and speaks the result.

**Türkçe özet:** deepAssist, görme engelli kullanıcıların telefonu daha az ekrana bağımlı kullanabilmesi için geliştirdiğim deneysel bir Android sesli asistanıdır. Türkçe konuşmaları anlayıp arama, mesaj, cihaz denetimi ve web araştırması gibi işleri sesli etkileşimle yürütür.

## What it does

- Voice input through OpenAI realtime transcription, with a file transcription fallback; spoken replies through OpenAI or Android TTS.
- Tool based actions for calls, SMS, contacts, WhatsApp, notifications, media, YouTube, timers, device controls, location, weather, exchange rates, and web search.
- Local conversation history, saved memories, and notification/message history.
- Optional Tavily search fallback. Web search also tries Exa and DuckDuckGo without a personal API key.

## Agent capabilities

The assistant can carry out a request in several steps instead of matching one phrase to one fixed command. It selects from the tools available on the phone, uses a tool's result to decide the next step, and then gives a spoken answer. For example, it can look up a contact before placing a call, search the web and read a relevant page before answering, or check device context before changing a setting.

It can ask a spoken follow up when a request is ambiguous, request confirmation before calls or messages, and continue a conversation after completing an action. Saved memories and conversation history can provide context for later requests. Actions remain limited by Android permissions, installed apps, available services, and the tools implemented in this repository.

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
