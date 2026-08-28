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

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec2 primaryTextureCoordinates;
out vec2 secondaryTextureCoordinates;
out vec3 viewPosition;
out vec3 viewNormal;

uniform sampler2D Sampler2;

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
    vec4 position = ModelViewMat * skinnedPosition;
    gl_Position = ProjMat * position;
    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
    vertexColor = Color;
    lightMapColor = texelFetch(Sampler2, LightUV / 16, 0);
    primaryTextureCoordinates = UV0;
    secondaryTextureCoordinates = UV1;
    viewPosition = position.xyz;
    viewNormal = normalize(NormalMat * skinnedNormal);
}
