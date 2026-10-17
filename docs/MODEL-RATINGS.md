# Model comparison methodology — 1.2 / 3 October 2026

The picker offers guidance for German/English dictation, not a claim that model intelligence can be measured with five dots. Accuracy means expected speech recognition quality. Speed means expected processing speed after Stop. Neither is a measured per-user result.

## Inputs

- Accuracy and speed: coarse editorial tiers from 1 to 5, explicitly marked ≈. The model's info dialog explains the inference and links its source. A zero/missing tier is displayed as —, never as a poor measured result. Claims about a model family or efficient architecture are not latency measurements through OpenRouter.
- Language flags: only documented German/English support is flagged. Unverified coverage appears under Any, with no flag. A flag does not guarantee accuracy or mixed-language recognition.
- Cost: the currently cached public OpenRouter rate, normalized to USD per minute for second/hour-priced models. Bands: 1 ≤ $0.0005; 2 ≤ $0.002; 3 ≤ $0.004; 4 ≤ $0.008; 5 > $0.008. These are starting rates, separate from returned actual billing.
- Token-priced or newly discovered unverified-unit models: no invented per-minute conversion. Cost dots show — and price sorting puts them after known minute prices. Exact token rates are in details.

## Recommendation mix

Recommendations are computed from the filtered catalog, selecting a different model for each role:

1. All-rounder: highest score among models with known accuracy, speed and cost tiers. Score = 0.65 × accuracy + 0.20 × (6 − cost tier) + 0.15 × speed.
2. Accuracy first: highest remaining accuracy tier, at least 4; known lower minute cost breaks an accuracy tie.
3. Budget pick: lowest remaining known minute price, with accuracy tier at least 3.
4. Speed first: highest remaining speed tier, at least 4; accuracy breaks speed ties.
5. Mixed languages: remaining documented code-switching candidate with the highest all-rounder score.

Missing components earn zero points in general ranking. Unknown models cannot silently become recommendations. Small or partially unassessed catalogs can yield fewer than five picks. Ties are deterministic by name and ID. Recommendation labels describe why a model was selected, not a measured victory over every other provider.

The bundled German+English mix is MAI-Transcribe 2 (all-rounder), Universal-3.5 Pro (accuracy), Qwen3 ASR 0.6B (budget), Parakeet TDT 0.6B v3 (speed), and Nova-3 (mixed languages). It can change after price/catalog refresh or language filtering. The selected transcription model is never changed automatically.

## Evidence and limits

Every model retains source and language-source links in `app/src/main/assets/models.json`, plus its rating rationale. Examples:

- [Microsoft MAI-Transcribe 2](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe): multilingual recognition and provider benchmark claims.
- [OpenRouter MAI-Transcribe 2](https://openrouter.ai/microsoft/mai-transcribe-2): hourly pricing and model capabilities.
- [AssemblyAI Universal-3.5 Pro](https://www.assemblyai.com/blog/universal-3-5-pro-code-switching-contextual-prompting): German/English, native code-switching and accuracy positioning.
- [Qwen ASR model card](https://huggingface.co/Qwen/Qwen3-ASR-1.7B): German/English coverage, model sizes, benchmark and efficiency discussion.
- [Parakeet model page](https://openrouter.ai/nvidia/parakeet-tdt-0.6b-v3): European-language coverage, architecture and reported ASR results.
- [Deepgram language coverage](https://developers.deepgram.com/docs/models-languages-overview/): German/English and multilingual options.
- [Whisper Turbo model page](https://openrouter.ai/openai/whisper-large-v3-turbo): speed-focused Whisper variant.

These sources use different benchmarks and serving environments. The app does not turn their numbers into a claimed common accuracy percentage. No paid comparative benchmark was run; the same German/English recordings, reference text, measured latency and actual returned costs would be needed for that. Network conditions, provider routing, accents and specialized vocabulary can change the practical ranking.
