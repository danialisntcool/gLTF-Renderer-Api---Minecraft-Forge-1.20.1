#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec2 UV0;
in vec2 UV1;
in vec4 Color;
in vec3 Normal;
in vec4 Joints;
in vec4 Weights;
in vec3 MorphPosition0;
in vec3 MorphPosition1;
in vec3 MorphPosition2;
in vec3 MorphPosition3;
in vec3 MorphNormal0;
in vec3 MorphNormal1;
in vec3 MorphNormal2;
in vec3 MorphNormal3;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 NormalMat;
uniform mat3 IViewRotMat;
uniform int FogShape;
uniform ivec2 LightUV;
uniform int HasSkin;
uniform mat4 JointMatrices[64];
uniform int HasMorphTargets;
uniform vec4 MorphWeights;
uniform int HasInstances;
uniform samplerBuffer Sampler8;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec2 primaryTextureCoordinates;
out vec2 secondaryTextureCoordinates;
out vec3 viewPosition;
out vec3 viewNormal;

uniform sampler2D Sampler2;
uniform sampler2D Sampler6;
uniform ivec2 OverlayUV;

out vec4 overlayColor;

void main() {
    vec3 morphedPosition = Position;
    vec3 morphedNormal = Normal;
    if (HasMorphTargets == 1) {
        morphedPosition += MorphPosition0 * MorphWeights.x + MorphPosition1 * MorphWeights.y
            + MorphPosition2 * MorphWeights.z + MorphPosition3 * MorphWeights.w;
        morphedNormal += MorphNormal0 * MorphWeights.x + MorphNormal1 * MorphWeights.y
            + MorphNormal2 * MorphWeights.z + MorphNormal3 * MorphWeights.w;
    }
    mat4 skinMatrix = mat4(1.0);
    if (HasSkin == 1) {
        skinMatrix = JointMatrices[int(Joints.x)] * Weights.x
            + JointMatrices[int(Joints.y)] * Weights.y
            + JointMatrices[int(Joints.z)] * Weights.z
            + JointMatrices[int(Joints.w)] * Weights.w;
    }
    vec4 skinnedPosition = skinMatrix * vec4(morphedPosition, 1.0);
    vec3 skinnedNormal = mat3(skinMatrix) * morphedNormal;
    mat4 drawMatrix = ModelViewMat;
    mat3 normalMatrix = NormalMat;
    ivec2 lightUv = LightUV;
    ivec2 overlayUv = OverlayUV;
    if (HasInstances == 1) {
        int first = gl_InstanceID * 8;
        mat4 instanceMatrix = mat4(texelFetch(Sampler8, first), texelFetch(Sampler8, first + 1),
                texelFetch(Sampler8, first + 2), texelFetch(Sampler8, first + 3));
        normalMatrix = mat3(ModelViewMat) * mat3(texelFetch(Sampler8, first + 4).xyz,
                texelFetch(Sampler8, first + 5).xyz, texelFetch(Sampler8, first + 6).xyz);
        drawMatrix = ModelViewMat * instanceMatrix;
        ivec4 instanceLighting = ivec4(texelFetch(Sampler8, first + 7));
        lightUv = instanceLighting.xy;
        overlayUv = instanceLighting.zw;
    }
    vec4 position = drawMatrix * skinnedPosition;
    gl_Position = ProjMat * position;
    vertexDistance = fog_distance(mat4(1.0), IViewRotMat * position.xyz, FogShape);
    vertexColor = Color;
    lightMapColor = texelFetch(Sampler2, clamp(lightUv / 16, ivec2(0), ivec2(15)), 0);
    overlayColor = texelFetch(Sampler6, clamp(overlayUv, ivec2(0), ivec2(15)), 0);
    primaryTextureCoordinates = UV0;
    secondaryTextureCoordinates = UV1;
    viewPosition = position.xyz;
    viewNormal = normalize(normalMatrix * skinnedNormal);
}
