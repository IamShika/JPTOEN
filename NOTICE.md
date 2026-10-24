# Third-party software

## sherpa-onnx (Apache License 2.0)

The speech engine. The Kotlin files in `app/src/main/java/com/k2fsa/sherpa/onnx/` are copied
unchanged from https://github.com/k2-fsa/sherpa-onnx at tag v1.13.8. The compiled native
libraries of the same version are downloaded at build time. Copyright the sherpa-onnx authors.
License: https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE

## Google ML Kit Translation

On-device translation (`com.google.mlkit:translate`). Used under the ML Kit terms:
https://developers.google.com/ml-kit/terms
Its language models are downloaded by ML Kit from Google.

## Speech models

Downloaded by the app on first use, repackaged from the sherpa-onnx model releases:

- Japanese recognizer: sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01, a conversion of
  the ReazonSpeech k2 model (https://huggingface.co/reazon-research/reazonspeech-k2-v2).
  Check that page for the model's license terms.
- Voice detector: Silero VAD (https://github.com/snakers4/silero-vad).
- Noise suppression: GTCRN (`gtcrn_simple.onnx`) from the sherpa-onnx speech-enhancement models
  (https://github.com/k2-fsa/sherpa-onnx/releases/tag/speech-enhancement-models).

### Optional recognizers (downloaded only if you choose them)

Repackaged from the sherpa-onnx model releases (https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models).
Check each project's page for the license terms of the model weights before redistributing them:

- SenseVoice (FunAudioLLM / Alibaba): https://github.com/FunAudioLLM/SenseVoice
- Fun-ASR Nano (FunAudioLLM): https://www.modelscope.cn/models/FunAudioLLM/Fun-ASR-Nano-2512
- Qwen3-ASR 0.6B (Qwen team, Alibaba): https://github.com/Wasser1462/Qwen3-ASR-onnx (ONNX export)
- Whisper small (OpenAI): https://github.com/openai/whisper

## Online translators (optional, only if you choose them)

- Google Translate web address (unofficial, no key): used at your own risk under Google's terms.
- DeepL API (https://www.deepl.com/pro-api): you supply your own key and accept DeepL's terms.
- Any OpenAI-compatible chat service (for example Google Gemini): you supply your own key and
  accept that service's terms.

These services receive the recognized Japanese text, never the audio. Keys stay on your phone.
