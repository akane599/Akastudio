# Curator model assets

Pinned on-device models used by `com.akane.akastudio.core.curator.ml`. Each file was downloaded into an
empty scratch directory on 2026-10-21, hashed, and copied here only after verification. The same sha256
values live in `ModelManifest.kt`; `ModelAssetsTest` re-hashes these committed files against it.

| file | source URL | sha256 | size (bytes) | licence |
|---|---|---|---|---|
| `face_landmarker.task` | https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task | `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff` | 3758596 | Apache-2.0 |
| `mobilenet_v3_small.tflite` | https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/latest/mobilenet_v3_small.tflite | `bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40` | 4117670 | Apache-2.0 |
| `efficientnet_lite0.tflite` | https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/float32/latest/efficientnet_lite0.tflite | `6c7ab0a6e5dcbf38a8c33b960996a55a3b4300b36a018c4545801de3a3c8bde0` | 18582189 | Apache-2.0 |
| `nima_aesthetic_fp16.tflite` | https://huggingface.co/litert-community/NIMA-LiteRT/resolve/5e27e756cf38d2e738ee0c7c5d6f1c43f2073038/nima_aesthetic_fp16.tflite | `a5051a0fcced735682735e3e0fd58ee54c83ed664282a003f52235b3dbcb9320` | 6448632 | Apache-2.0 |

## Model notes

- **face_landmarker.task**: MediaPipe Face Landmarker bundle (float16): face detector, 478-point mesh and
  the 52-blendshape head. The adapter reads `eyeBlinkLeft` / `eyeBlinkRight` by name.
- **mobilenet_v3_small.tflite**: MediaPipe Image Embedder, input `[1,224,224,3]`, output `feature_vector`
  `[1,1024]`. MobileNetV4 (1280-d) is not published as a MediaPipe Tasks embedder, so this 1024-d model
  is used instead.
- **efficientnet_lite0.tflite**: MediaPipe Image Classifier, ImageNet-1k (1000 labels in the embedded
  metadata, `labels_without_background.txt`). Every label in the scene keyword table exists in this label set
  (`croissant`, which does not, was removed as a dead non-match).
- **nima_aesthetic_fp16.tflite**: NIMA MobileNet aesthetic head (AVA), re-authored for LiteRT from
  idealo/image-quality-assessment (Apache-2.0). Hugging Face repo `litert-community/NIMA-LiteRT`,
  revision `5e27e756cf38d2e738ee0c7c5d6f1c43f2073038`. Signature `serving_default`: input
  `keras_tensor` float32 `[1,224,224,3]` (`v/127.5 − 1`), output `output_0` float32 `[1,10]` softmax.

`tflite` and `task` files are stored uncompressed in the APK (`androidResources.noCompress`) so the
runtimes can memory-map them.
