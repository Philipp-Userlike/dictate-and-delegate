# Build and test

Drift is an Android app (Java 17, Android SDK 36, AGP 8.13.2, Gradle 8.13). Set `JAVA_HOME` and the SDK path in a local `local.properties`. The Gradle wrapper is included; production has no external runtime dependencies.

```sh
./scripts/build.sh            # unit tests, lint, debug APK
./scripts/device-test.sh      # instrumented tests against a fake OpenRouter
```

UX checks by a persona agent on the emulator: [scripts/qa](../scripts/qa/README.md).

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Android tests require a disposable Android 16 emulator: they replace the OpenRouter key and manipulate synthetic history, audio, permission grants, the clipboard and Quick Settings. Never run them on a phone with real Drift data. `device-test.sh` starts `scripts/fakes/fake_openrouter.py` on `DRIFT_TEST_PORT` (default 8797), forwards it with `adb reverse` and builds the app with `-PopenrouterUrl=http://127.0.0.1:8797/api/v1`. Tests save a fake key, queue transcripts, request-check answers and replies through the fake's `/test/script`, and read what the app sent from `/test/requests`. The fake reports fixed costs ($0.001 per transcription, $0.00005 per check, $0.0003 per text call). No provider calls, no charges.

To sign a release, create `.signing/release.jks` with alias `drift`, put its password in `.signing/password`, then run `./scripts/release.sh 2.0.0 13` (version name and code). It writes a signed APK and an AAB to `dist/`. Both files are ignored.

Translations: UI text stays English in code as `tr("…")`. After adding or changing a text, run `scripts/i18n.py extract`, then `OPENROUTER_API_KEY=… scripts/i18n.py translate` to fill the seven other languages (it also drops texts the code no longer uses); `I18nTest` fails when one is missing. Review German by hand. Legal texts are `res/raw*/`.

Network: the app talks only to OpenRouter (`OpenRouter`, base URL `BuildConfig.OPENROUTER_URL`, set with `-PopenrouterUrl=`; default `https://openrouter.ai/api/v1`), with the user's own key from `Vault`. Speech goes to `/audio/transcriptions` in chunks of about two minutes; the request check (`Router`, DeepSeek V4.1 Flash, no reasoning, 200 tokens) and the text model (`TextModel`, Claude Sonnet 5.5, 4,096 tokens, attachments as message parts, PDFs through OpenRouter's `file-parser` plugin) go to `/chat/completions`. Each response's `usage.cost` is what the note and Activity show; a missing cost stays unknown. The speech-model catalog is bundled in `assets/models.json`; `Profile.OFFERED` lists the models whose word hints work.

Debug-only hooks (absent in release because they check `BuildConfig.DEBUG`): launch extras `debugSeed` (sample notes) and `debugScreenshots` (lifts `FLAG_SECURE`). Debug builds also allow plain HTTP for the fake.

Main components: `DictationService` (capture, chunked transcription, request check, text, recovery), `AudioArchive` (retention), `HistoryStore` (encrypted versions/checkpoints), `Router` (request check), `TextModel` (the text call), `OpenRouter` (the HTTP client), `Attachments`, `Templates`, `I18n` (translations), `MainActivity` (UI), `DictationTile` / `OverlayIcon` (controls). `Mcp` is a small MCP client kept for the bb bridge ([plan](plans/bb-bridge.md)); nothing uses it yet.
