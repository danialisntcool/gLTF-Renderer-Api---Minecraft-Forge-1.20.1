#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
in vec2 MaterialUV1;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform int FogShape;
uniform sampler2D Sampler2;
uniform sampler2D Sampler6;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 primaryTextureCoordinates;
out vec2 secondaryTextureCoordinates;
out vec3 viewPosition;
out vec3 viewNormal;

void main() {
    vec4 position = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * position;
    vertexDistance = fog_distance(mat4(1.0), IViewRotMat * position.xyz, FogShape);
    vertexColor = Color;
    lightMapColor = texelFetch(Sampler2, clamp(UV2 / 16, ivec2(0), ivec2(15)), 0);
    overlayColor = texelFetch(Sampler6, clamp(UV1, ivec2(0), ivec2(15)), 0);
    primaryTextureCoordinates = UV0;
    secondaryTextureCoordinates = MaterialUV1;
    viewPosition = position.xyz;
    viewNormal = normalize(mat3(ModelViewMat) * Normal);
}
