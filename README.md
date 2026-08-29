# glTF Renderer API

For Minecraft Forge 1.20.1, this is a glTF 2.0 rendering API maintained by danialisntcool and published by Uncool Labs.

## Requirements

- Minecraft 1.20.1
- Forge 47 or newer
- Java 17

Embeddium 0.3.31+ and Oculus 1.8.0+ are optional and supported.

## Maven dependency

```gradle
repositories {
    maven {
        url = "https://danialisntcool.github.io/gLTF-Renderer-Api---Minecraft-Forge-1.20.1/maven"
    }
}

dependencies {
    implementation fg.deobf("me.danialisntcool.gltfapi:gltf_renderer_api:1.0.0-beta.1")
}
```

The renderer API is client-side, so all calls to `me.danialisntcool.gltfapi.api.client` should be made within client-only code.

## Basic rendering

Place models in `assets/<namespace>/models/gltf/`, then obtain a handle using the full resource path.

```java
private static final GltfModelHandle MODEL = GltfApi.model(
        ResourceLocation.fromNamespaceAndPath("examplemod", "models/gltf/example.gltf")
);

GltfRenderContext context = GltfRenderContext.create(poseStack, buffers, packedLight);
GltfApi.render(MODEL, context);
```

The API includes reusable helpers for entities, block entities, items, armor layers, particles, and GUI rendering. It also provides a Forge block/item geometry loader for ordinary baked models. The bundled test character and static block illustrate the integration, although they are not necessary for API consumers.

## Supported features

- `.gltf` and `.glb` models
- Embedded and external resource-pack buffers and textures
- Draco and Meshopt compressed geometry
- KTX2/BasisU textures with GPU format selection and fallback handling
- Materials, transparency, camera-distance sorting, and double-sided geometry
- Skeletal animation, skins, morph targets, multiple scenes, and GPU instancing
- Model bounds, frustum culling, LOD selection, and compatible batch submission
- Embeddium rendering and Oculus/Iris shader and shadow-pass integration
- Operator-only in-game benchmark commands

Individual glTF resources are limited to 128 MiB and decoded geometry buffers to 256 MiB to stop malicious or defective assets from exhausting client memory. Release builders can alter these limits in `gradle.properties`.

## Build

Use Java 17, then run:

```text
gradlew.bat build
```

The JAR file is written to the `build/libs` directory.

## License and support

Copyright (c) 2026 Uncool Labs. All Rights Reserved.

This project is proprietary source-available software, not open-source software. Its source can be inspected publicly, and official unmodified releases may be used directly or through the documented API under the terms in `LICENSE.txt`. Modification and redistribution are restricted unless Uncool Labs grants written permission.

Bundled third-party libraries and test assets remain under their own licenses. See `THIRD_PARTY_NOTICES.txt` for attribution and license details.

Support: https://discord.gg/JMstSCsJqr
