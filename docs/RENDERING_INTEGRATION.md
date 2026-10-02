# Rendering paths and materials

All examples below belong in client-only code. The common mod entry point must not reference rendering classes on a dedicated server.

## Choosing a path

`GltfApi.renderBuffered` always submits CPU-skinned triangles through Minecraft's `MultiBufferSource` stream. With shaders off, its material-aware vertex format and shader apply glTF metallic/roughness, normal, occlusion and emissive properties. With an active shader pack or shadow pass, it uses Minecraft's standard entity vertex stream instead. Set `GltfRenderMode.BUFFERED` to force that standard stream even with shaders off.

`GltfApi.render` accepts a per-call preference:

```java
GltfApi.render(model, context.withRenderMode(GltfRenderMode.BUFFERED));
GltfApi.render(model, context.withRenderMode(GltfRenderMode.NATIVE));
```

The client configuration file `gltf_renderer_api-client.toml` also accepts `renderMode = "AUTO"`, `"BUFFERED"`, or `"NATIVE"`. A context set to `AUTO` uses this configuration. Automatic selection uses the buffered path with Embeddium, Rubidium, Sodium, an active Oculus shader pack, or a shadow pass. Explicit native selection works without an active shader pack; shader and shadow passes always retain the compatible path.

Native rendering explicitly binds Minecraft's lightmap and uses the supplied packed light coordinates. It restores the calling renderer's shader, textures, VAO and render state. Native and buffered PBR materials use glTF metallic (blue) and roughness (green) channels, linear lighting, normal maps, occlusion and emissive factors. Ambient reflections sample one reusable, mipmapped environment panorama, not world geometry. Its strength and the bounded exact-pose cache size are release settings in `gradle.properties`. A resource pack can supply an equirectangular PNG at `assets/gltf_renderer_api/textures/environment/reflection.png`; otherwise the renderer generates its own small neutral studio/sky panorama once at reload.

The standard shader-pack-compatible stream and baked paths use Minecraft's base-colour lighting. The buffered PBR shader does not translate arbitrary Blender nodes or make a shader pack understand glTF metallic/roughness textures. Baked replacements remain static and use one atlas texture.

## Batch performance

`GltfApi.renderWorld(model, context)` opts into the world queue. The supplied `GltfEntityRenderer` and `GltfBlockEntityRenderer` use it automatically when `worldBatching = true`. Requests are frozen, grouped and flushed at the entity and block-entity pass boundaries, not combined across incompatible passes. Native requests use instancing; compatible requests use the indexed material batches described below. Oculus entity/block-entity/item IDs are captured and restored around matching groups. Unsupported shader-state APIs, different framebuffer targets, outlines/wrapped buffer sources, custom material hooks, blended models and shadow passes retain immediate rendering. This is not a guarantee that every caller or every shader pack can be deferred.

Custom renderers must explicitly switch suitable opaque world submissions to `renderWorld`; ordinary `renderBuffered` calls are still immediate submissions to the caller's buffer source. No consumer project is edited automatically. Inventory, particles with special pass requirements and arbitrary GUI drawing must not use the world queue.

Queued requests use current scene/animation/morph/node-rotation bounds for frustum culling. The entity helper uses these bounds instead of only its hitbox. The block-entity helper exposes `renderBounds(blockEntity, partialTick)` and measures distance to the model's bounds. Forge can still reject a block entity before its renderer runs using `BlockEntity.getRenderBoundingBox()` and chunk visibility. Consumers with models larger than their block must supply a conservative bounding box there, in server-safe code. A client-only renderer cannot override a common block entity's bounding-box implementation.

`modelRenderDistance = 0.0` adds no distance cap; a positive value limits queued/helper models to that distance in blocks, while preserving vanilla visibility limits. Conservative bounds are cached per exact pose. Skin weights are validated as finite/nonnegative and normalized; used-joint bounds conservatively contain their weighted positions. No behind-wall occlusion or automatic LOD generation is added.

`GltfApi.renderBatch` groups matching native opaque/masked requests by model, primitive and exact pose state, and uploads per-instance transforms, packed light and overlay coordinates to a reusable GPU buffer. It issues indexed instanced draws. Blended models and custom material renderers retain their non-instanced path. Active shader/shadow passes never use this native shader.

Compatible batches group indexed geometry by render type. CPU staging and GPU buffer objects are reused under configurable retention budgets. Storage is streamed again when geometry changes; this is buffer-object reuse, not a claim that animated compatibility geometry needs no uploads. Direct `renderBuffered` calls remain in the caller's `MultiBufferSource`; they are not silently moved into a separate frame queue. With shaders off, the packed submission transforms each unique vertex once before expanding indices. Shader packs and wrapped vertex consumers retain the ordinary vertex submission so their extra attributes can be generated correctly.

The release settings `gltf_staging_mebibytes` and `gltf_gpu_stream_cache_mebibytes` control individual staging/chunk budgets and retained compatibility GPU storage. Native instance arrays and queue lists are reused. Immutable UV/colour/secondary-UV templates are retained under `gltf_static_attribute_cache_mebibytes`; positions, normals, lighting and overlays are still updated correctly. Repeated material uniform updates are skipped only for the same owned shader/material/GUI mode. Model GPU buffers, decoded model data and pose caches are separate allocations. These are not a total process-memory or VRAM cap.

The operator-only benchmark accepts an optional explicit path after the duration:

```text
/gltfbenchmark start gltf_renderer_api:models/gltf/test_character/scene.gltf 64 15
/gltfbenchmark start gltf_renderer_api:models/gltf/test_character/scene.gltf 64 15 native
/gltfbenchmark start gltf_renderer_api:models/gltf/test_character/scene.gltf 64 15 buffered
```

Omitting the path uses `AUTO` and the client configuration. `buffered` forces the standard, non-PBR stream; use `AUTO` with Embeddium to measure buffered PBR. Active shader packs override native selection. Results include instrumented API draws, instanced draws, indexed/instance uploads, CPU vertex transforms, bulk/indexed vertices, pose-cache hits and GPU buffer reuse. These counters do not cover all Minecraft draws/uploads or JVM allocations. The grid uses a shared static pose and full-bright lighting; its results are not a substitute for independent animated entities/block entities in a real scene.

## Inventory lighting and static icon caching

Orthographic PBR rendering uses a fixed view direction, an inventory-local reflection orientation, a separate `gltf_gui_environment_strength`, and no world fog. It therefore does not change its reflection just because the same icon is drawn in another slot or the world camera turns. Depth testing was already present and remains enabled.

`GltfItemRenderer` caches eligible static opaque/masked GUI icons in bounded offscreen colour/depth textures when `inventoryIconCache = true` and shader packs are off. Keys include model, scene, transforms, lighting, render mode and resolution; slot translation is not a distinct icon. Animated/skinned/morphed models, live node offsets, blended materials, shader packs and custom material hooks stay live. Captures use their own buffers and restore projection, model-view, framebuffer, viewport, scissor, clear values and native render state. Transparent cached pixels discard instead of covering the inventory with invisible depth.

Release controls are `gltf_icon_cache_mebibytes`, `gltf_icon_cache_entries`, `gltf_icon_max_resolution` and `gltf_icon_builds_per_frame`. Cache storage is an estimate of colour/depth allocation, not driver overhead. A frame's capture limit falls back to live rendering; it never hides uncached items. Resource reload destroys old cached targets. Static baked-model items and arbitrary consumer-written item renderers are not automatically converted into cached icons.

## Real-scene profiling

```text
/gltfprofile start 20
/gltfprofile status
/gltfprofile stop
```

These operator-only commands add no models. After a three-second warmup they observe normal world/inventory rendering and report FPS, mean/p95/p99 frame time, API CPU submission time, instrumented GPU time, draws, instancing, uploads, transforms, pose/buffer reuse, icon hits/builds and culling counts. Mixed shader states are labelled as mixed. FPS/frame time includes the rest of the game, VSync and any FPS limiter.

GPU timings use a bounded timestamp ring with nonblocking availability checks; unavailable/pending/dropped samples are shown. Covered GPU time includes the API's indexed draws, owned PBR stream and cached icon draws, not all game rendering or arbitrary shader-pack/custom-consumer buffered draws. It excludes CPU-to-GPU upload time outside the draw scopes. Do not compare a partially covered GPU result to total frame time as if it were complete. The profiler itself has measurement overhead and does not force the GPU to finish.

## Replacing an existing item's static model

Register the replacement during client mod construction, before the first model bake:

```java
GltfItemModels.register(
        ResourceLocation.fromNamespaceAndPath("minecraft", "diamond_sword"),
        ResourceLocation.fromNamespaceAndPath("examplemod", "item/gltf_sword"));
```

Create `assets/examplemod/models/item/gltf_sword.json`:

```json
{
  "parent": "minecraft:item/handheld",
  "loader": "gltf_renderer_api:gltf",
  "model": "examplemod:models/gltf/sword.gltf",
  "texture": "surface",
  "textures": {
    "surface": "examplemod:item/sword",
    "particle": "examplemod:item/sword"
  }
}
```

The atlas texture belongs at `assets/examplemod/textures/item/sword.png`. Use the JSON `display` transforms for GUI, ground and hand orientation. This replacement is reapplied at resource reload and needs no consumer-written mixin. Baked replacements are static and currently use one atlas texture; skins, morph targets, live node rotations and animated vanilla-item replacement are not supported by this helper. A resource pack can alternatively override `assets/minecraft/models/item/diamond_sword.json` with the glTF loader directly. Custom items owned by your mod can use `GltfItemRenderer` for animation.

## Custom material renderer

Register one `GltfMaterialRenderer` for a model using `GltfMaterialRenderers.register(handle, renderer)`. The callback runs for each primitive on both rendering paths, including batched compatibility rendering. It receives CPU-deformed positions and normals, both original UV sets, vertex colours, sorted triangle indices, the complete glTF material, the transformed pose, buffers, packed light/overlay and shader/shadow-pass flags.

Return `true` only after submitting the primitive yourself; return `false` without submitting anything to retain the built-in renderer. Buffer views are read-only and valid for the current call. Do not retain them or the pose. The callback must submit through appropriate render types, honor shadow passes and alpha modes, and restore any GL state it changes. Do not call `GltfApi.render` recursively for the same model from its own callback.

This hook allows a shader-pack integration to implement its own channel conversion, passes and blending. It does not translate arbitrary Blender node graphs to GLSL or automatically blend with every shader pack. Removing a registration requires the same callback instance with `GltfMaterialRenderers.unregister`.

## Validation

`gradlew.bat test` runs the Java regression tests. `gradlew.bat gltfShaderCompileSmoke` compiles and links the actual model/icon shaders and checks normal-map scale invariance, metallic/roughness environment response, GUI camera/slot invariance, icon transparency/depth, GPU timestamps, native instance transforms/light and compatibility buffer reuse in a hidden OpenGL context without launching Minecraft. Neither proves in-game Embeddium/Oculus compatibility or an FPS gain; those require gameplay testing with the intended mod set.
