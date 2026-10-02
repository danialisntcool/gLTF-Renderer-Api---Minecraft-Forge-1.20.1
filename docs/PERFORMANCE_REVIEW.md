# Material and performance review — 2026-10-02

## Verified distance-dependent material bug

The previous fragment shader rejected its derivative-derived normal-map tangent frame when the UV determinant was below an absolute threshold. UV derivatives become small when a surface occupies more screen pixels, so valid normal maps could stop applying close up. This is independent of whether the model is high-poly.

The fix normalizes the derivatives before checking relative degeneracy. A GPU pixel regression uses the real fragment shader, a constant normal texture and two UV footprints. With the previous built JAR as the control, the measured normal-map effect was 0.06191182 at the larger footprint and 0.0 at the smaller footprint. With the corrected shader it was 0.06191 at both. This demonstrates the specific shader bug and its correction, not that every consumer asset is correct.

## Research and independent implementation

The supplied Luxium 2.8.0 pre-alpha archive was inspected through its resource/class inventory and public/private class signatures, not copied or decompiled into this project. Its GPU shadow cache, frame cache, reflection-plane cache and render-list cache expose retention, pooling, validity and bounded-storage concepts. Those structural observations informed cache reuse; no Luxium classes, shaders, textures or implementation code were reused. This mod does not depend on Luxium.

The technical references were:

- [Khronos glTF 2.0 specification](https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html): material channel conventions, texture transforms and sampling. Preserve explicit samplers, provide mipmaps for mipmapped filtering, and keep metallic/roughness data separate from sRGB base colour.
- [Khronos OpenGL buffer streaming](https://wikis.khronos.org/opengl/Buffer_Object_Streaming): re-specifying stream storage allows driver-managed orphaning instead of forcing the CPU to wait for storage still consumed by a previous draw. This does not guarantee zero driver overhead.
- [Filament rendering theory](https://google.github.io/filament/Filament.md.html) and [material documentation](https://google.github.io/filament/Materials.md.html): conductors need a specular/environment response, roughness broadens reflection, and normal variation can alias specular highlights. The renderer implements its own limited static-environment approximation rather than adopting another engine's source.

## Implemented optimizations

- Direct buffered submission, with shaders off, transforms unique vertices once and copies the indexed packed vertices into the caller's stream. Wrapper consumers and shader-pack paths preserve their normal attribute-writing hooks.
- Compatible batches retain CPU builders, reusable index staging and pooled GPU buffer objects. Their storage uses streaming uploads; objects are not recreated/deleted for every successful frame.
- Native batches use indexed GPU instancing for matching model/primitive/exact-pose groups. Per-instance pose, inverse-transpose normal matrix, packed light and overlay are uploaded together. The instance chunk size respects the device's texture-buffer limit and the release staging limit.
- Matching animation/node-rotation states share cached transforms, skin matrices and deformed arrays. The exact-pose cache remains bounded and does not change animation timing.
- CPU skinning uses the existing flat matrices directly instead of reconstructing 64 matrix objects for each deformed primitive. Matrix-reference regression tests cover morphing, weighted skinning and zero normals.
- Transparency sorting reuses primitive arrays and cached triangle centers rather than boxing triangle indices. Unchanged geometry/pose reuses the order.
- Regular decoded textures get mipmaps at upload. Explicit glTF filters are retained; unspecified minification uses trilinear sampling. KTX textures without a mip chain use a complete base-level filter.
- Native and buffered PBR share one small static environment texture with roughness-selected mip levels. There are no scene reflection passes. Unlit surfaces skip the PBR calculation. Normal variation contributes bounded specular filtering.
- Oculus state queries use bound typed method handles instead of repeated reflective singleton lookup and boxed return conversion.
- Resource reload clears renderer caches, instance storage and pooled GPU objects. Batch failures release retained resources and restore the instance-mode uniform.

## Validation and limitations

Hidden OpenGL checks compile/link the real native and buffered shaders and render material pixels. Metallic reflection and roughness tests produce distinct finite results, and native instancing verifies separate transforms/light and texture-buffer reuse. Compatibility streaming verifies GPU object reuse and caller binding restoration. Java tests verify packed formats, transforms, request snapshots, mode propagation, pose caching, transparency and skinning.

No Minecraft instance was launched, no benchmark FPS improvement was measured and no shader-pack gameplay result is claimed. The benchmark uses a full-bright static grid through the public batch API; it can be faster than unrelated entities with independent poses or immediate callers. Supplied entity/block-entity helpers now opt into pass-boundary world batching. Other consumers must explicitly adopt `renderWorld`; ordinary immediate calls are not silently moved into a frame queue.

Shader packs retain the standard Minecraft stream. Their arbitrary PBR conventions are not automatically translated from glTF textures. The new static reflection is available to the API's native/buffered PBR shaders, not injected into every Oculus pack. Automatic LOD generation and behind-wall occlusion are not added here.

Use the documented benchmark path choices with shaders off/on, and test the actual affected model while moving close/far and rotating the camera. Inspect ordinary animated entities/block entities separately from the static benchmark grid. See [rendering integration](RENDERING_INTEGRATION.md) for commands and memory-budget settings.

## Inventory and real-scene follow-up

Orthographic inventory shading previously reused a perspective view direction and the world's inverse-view rotation. GUI mode now uses a fixed view direction and inventory-local environment orientation, a separate reflection strength and no world fog. The actual fragment-shader GPU regression produces the same 0.56543 result before and after shifting view coordinates and rotating the world-camera matrix. This verifies that specific invariance, not the appearance of every inventory asset.

Eligible unchanged static icons are captured under bounded count/storage/resolution/per-frame budgets. Dynamic, translucent, custom-hook and shader-pack icons stay live. The actual icon shader's GPU test verifies that transparent texels preserve existing depth while opaque texels render with depth. Java tests cover icon projection sizing, negative morph weights, rotated-node/weighted-skin bounds and frozen request rotations. The suite currently passes 26 Java tests plus hidden OpenGL regressions and the complete build.

World queue requests are frozen and grouped within compatible entity/block-entity passes. Native array storage, queue lists and bounded static UV/colour templates are reused; the owned shaders retain unchanged material uniforms. The supplied Oculus 1.8.0 class signatures were checked for capture/restoration of per-entity shader IDs. Unsupported adapters and special passes stay immediate. This dependency inspection is not an in-game shader-pack validation.

The supplied Quantified API 2.2.3 JAR was inspected via metadata/class inventory and method signatures. Its CPU scheduling and Vulkan/OpenCL compute interfaces did not expose a shared OpenGL rendering-buffer path in that inspected API. No code, assets or dependency were copied/added, and no performance multiplier is inferred from its marketing. There is no evidence here of a sufficiently large benefit to justify another compute backend and data-transfer path.

`/gltfprofile start 20` now profiles actual world/GUI API activity. GPU timestamp availability is checked asynchronously using a bounded ring, with unavailable/pending/dropped results explicit, consistent with [Khronos query-object guidance](https://wikis.khronos.org/opengl/Query_Object). It does not block on a GPU result or report uncovered custom/vanilla draws as measured API GPU time. Minecraft gameplay, real-world FPS and the user's specific inventory models remain for user testing.
