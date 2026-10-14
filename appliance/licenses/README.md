# Local caption dependencies

Vosk Android 0.3.75 and JNA 5.18.1 are pinned from Maven Central, following
https://github.com/alphacep/vosk-android-demo/blob/master/app/build.gradle.
Vosk is Apache-2.0; JNA offers Apache-2.0 or LGPL-2.1-or-later.

The bundled English caption model is `vosk-model-small-en-us-0.15.zip`,
downloaded from https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip.
The official model catalog lists this small Android model as Apache-2.0:
https://alphacephei.com/vosk/models. The license is included alongside this file.

Model SHA-256: `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`.
The model is installed locally from assets; Puck does not download a model
or contact a transcription provider at runtime. English captions are a preview;
Muse still receives the original audio on release.
