Vosk offline English caption models, Alpha Cephei

Source and benchmark/license listing: https://alphacephei.com/vosk/models

Bundled models:
- vosk-model-small-en-us-0.15 (Apache-2.0), fast captions.
- vosk-model-en-us-0.22-lgraph (Apache-2.0), enhanced captions.
  Download SHA-256: d9838b4aaa82a75c4a17f5aca300eaca129aaab2a7cbf951bafbb500eb9c4334
  Model README: Accurate universal English model, based on Appen Kaldi
  https://github.com/Appen/UHV-OTS-Speech, dynamic graph version.

Upstream benchmark differences do not establish accuracy on a user's speech.
Both recognizers process PCM locally for display only. The original audio is
sent independently to the paired Muse when the user releases the talk button.
