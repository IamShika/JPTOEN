# Third-party software

## sherpa-onnx (Apache License 2.0)

The speech engine. The Kotlin files in `app/src/main/java/com/k2fsa/sherpa/onnx/` are copied
unchanged from https://github.com/k2-fsa/sherpa-onnx at tag v1.13.8. The compiled native
libraries of the same version are downloaded at build time. Copyright the sherpa-onnx authors.
License: https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE

## Speech models

Downloaded by the app on first use, repackaged from the sherpa-onnx model releases:

- Japanese recognizer: sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01, a conversion of
  the ReazonSpeech k2 model (https://huggingface.co/reazon-research/reazonspeech-k2-v2).
  Check that page for the model's license terms.
- Voice detector: Silero VAD (https://github.com/snakers4/silero-vad).
