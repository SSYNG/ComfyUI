# TangHua (躺画)

[简体中文](README.md) · [MIT License](LICENSE)

TangHua is an Android client for drawing with a ComfyUI server on your local network. The phone handles prompts, the task queue, and results; your computer runs inference. The bundled workflows target **Qwen Image 2.1** with an INT8 image model and W4A8 text encoders.

## Features

- Text to image with aspect ratio, sampling steps, seed, and standard or Heretic W4A8 encoder options.
- Reference image editing with one required primary image and an optional second image, both previewed in the app. The primary image determines the base output dimensions.
- A separate full screen prompt editor with its own scrolling, character count, jump controls, and a locally saved draft.
- A serial task queue: one submitted task at a time. Pending tasks can be dragged to reorder or removed; a running task can be canceled.
- Execution stages, sampler steps, estimated overall percentage and remaining time; results can be saved to the phone gallery.
- User configurable ComfyUI address, with Create, Tasks, and Settings tabs.

## Screenshots

<p align="center">
  <img src="docs/screenshots/create.jpg" alt="Create tab" width="260" />
  <img src="docs/screenshots/tasks.jpg" alt="Tasks tab" width="260" />
  <img src="docs/screenshots/settings.jpg" alt="Settings tab" width="260" />
</p>

These are screenshots from the running Android app. Original files are in `docs/screenshots/`.

## Requirements and use

1. A computer running ComfyUI with a compatible Qwen Image 2.1 workflow. The default model filenames are in [`app/src/main/assets`](app/src/main/assets). If your filenames differ, update the workflow JSON and the encoder choices in `MainActivity.java`.
2. An Android 10 (API 29) or newer phone on the same local network.
3. Start ComfyUI on a LAN accessible interface, for example `python main.py --listen 0.0.0.0 --port 8188`. In Settings, enter `http://YOUR_COMPUTER_LAN_IP:8188` and test the connection.

Enter a prompt in Create, choose text generation or reference editing, then tap Add to Queue. Tasks shows progress and results. Generated images are also stored in ComfyUI's `output` directory; Save to Gallery copies a result to `Pictures/躺画` on the phone.

## Build

Open this directory in Android Studio. The project uses Android Gradle Plugin 8.4, Gradle 8.6, Java 17, and Android SDK 34, with no third party runtime dependencies. On Windows, with `ANDROID_HOME` (or `ANDROID_SDK_ROOT`), Java 17, and an Android debug keystore configured, you can also run:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-offline.ps1
```

The script produces `TangHua-debug.apk`. Use your own release signing configuration for public distribution. The existing package name, `local.qwenimage.mobile`, is retained so users can upgrade from earlier versions.

## Implementation and limitations

- The client uses ComfyUI's `/upload/image`, `/prompt`, `/history/{prompt_id}`, `/view`, `/queue`, `/interrupt`, and `/ws` endpoints. Workflow templates are in `app/src/main/assets/`.
- Overall progress is estimated from workflow stages. Sampler steps come from live events; remaining time is estimated from recent sampling speed.
- Pending tasks are stored only in the current app process and are lost if the process is killed before they are submitted.
- The current LAN HTTP connection has no account authentication. Use it only on a trusted local network and do not expose ComfyUI directly to the public internet.

## License

This project is available under the [MIT License](LICENSE). Qwen Image 2.1, ComfyUI, and model files have their own licenses; no model weights are included here.
