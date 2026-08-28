#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;
uniform vec4 ColorModulator;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;
uniform float AlphaCutoff;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform int HasBaseColorTexture;
uniform int HasMetallicRoughnessTexture;
uniform int HasNormalTexture;
uniform int HasOcclusionTexture;
uniform int HasEmissiveTexture;
uniform int IsUnlit;
uniform float MetallicFactor;
uniform float RoughnessFactor;
uniform float NormalScale;
uniform float OcclusionStrength;
uniform vec3 EmissiveFactor;
uniform ivec4 TextureCoordinateSets;
uniform int EmissiveTextureCoordinateSet;
uniform vec4 BaseColorUvTransform;
uniform vec4 MetallicRoughnessUvTransform;
uniform vec4 NormalUvTransform;
uniform vec4 OcclusionUvTransform;
uniform vec4 EmissiveUvTransform;
uniform float BaseColorUvRotation;
uniform float MetallicRoughnessUvRotation;
uniform float NormalUvRotation;
uniform float OcclusionUvRotation;
uniform float EmissiveUvRotation;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec2 primaryTextureCoordinates;
in vec2 secondaryTextureCoordinates;
in vec3 viewPosition;
in vec3 viewNormal;

out vec4 fragColor;

vec2 transformedUv(int coordinateSet, vec4 transform, float rotation) {
    vec2 uv = coordinateSet == 1 ? secondaryTextureCoordinates : primaryTextureCoordinates;
    vec2 scaled = uv * transform.zw;
    float sine = sin(rotation);
    float cosine = cos(rotation);
    return vec2(cosine * scaled.x - sine * scaled.y, sine * scaled.x + cosine * scaled.y) + transform.xy;
}

vec3 mappedNormal(vec2 uv) {
    vec3 sampled = texture(Sampler3, uv).xyz * 2.0 - 1.0;
    sampled.xy *= NormalScale;
    vec3 positionDx = dFdx(viewPosition);
    vec3 positionDy = dFdy(viewPosition);
    vec2 uvDx = dFdx(uv);
    vec2 uvDy = dFdy(uv);
    float determinant = uvDx.x * uvDy.y - uvDx.y * uvDy.x;
    if (abs(determinant) < 0.000001) {
        return normalize(viewNormal);
    }
    vec3 tangent = normalize((positionDx * uvDy.y - positionDy * uvDx.y) / determinant);
    tangent = normalize(tangent - viewNormal * dot(viewNormal, tangent));
    vec3 bitangent = normalize(cross(viewNormal, tangent)) * sign(determinant);
    return normalize(mat3(tangent, bitangent, viewNormal) * sampled);
}

void main() {
    vec2 baseUv = transformedUv(TextureCoordinateSets.x, BaseColorUvTransform, BaseColorUvRotation);
    vec4 baseSample = HasBaseColorTexture == 1 ? texture(Sampler0, baseUv) : vec4(1.0);
    vec4 baseColor = baseSample * vertexColor * ColorModulator;
    if (baseColor.a < AlphaCutoff) {
        discard;
    }

    vec2 metallicUv = transformedUv(TextureCoordinateSets.y, MetallicRoughnessUvTransform, MetallicRoughnessUvRotation);
    vec4 metallicRoughness = HasMetallicRoughnessTexture == 1 ? texture(Sampler1, metallicUv) : vec4(1.0);
    float metallic = clamp(MetallicFactor * metallicRoughness.b, 0.0, 1.0);
    float roughness = clamp(RoughnessFactor * metallicRoughness.g, 0.04, 1.0);

    vec2 normalUv = transformedUv(TextureCoordinateSets.z, NormalUvTransform, NormalUvRotation);
    vec3 normal = HasNormalTexture == 1 ? mappedNormal(normalUv) : normalize(viewNormal);
    vec3 viewDirection = normalize(-viewPosition);
    vec3 lightA = normalize(Light0_Direction);
    vec3 lightB = normalize(Light1_Direction);
    float diffuse = max(dot(normal, lightA), 0.0) * 0.6 + max(dot(normal, lightB), 0.0) * 0.6 + 0.2;
    vec3 halfA = normalize(lightA + viewDirection);
    vec3 halfB = normalize(lightB + viewDirection);
    float exponent = mix(256.0, 4.0, roughness);
    float specular = pow(max(dot(normal, halfA), 0.0), exponent) + pow(max(dot(normal, halfB), 0.0), exponent);
    vec3 dielectric = vec3(0.04);
    vec3 reflectance = mix(dielectric, baseColor.rgb, metallic);
    vec3 diffuseColor = baseColor.rgb * (1.0 - metallic);

    vec2 occlusionUv = transformedUv(TextureCoordinateSets.w, OcclusionUvTransform, OcclusionUvRotation);
    float sampledOcclusion = HasOcclusionTexture == 1 ? texture(Sampler4, occlusionUv).r : 1.0;
    float occlusion = mix(1.0, sampledOcclusion, OcclusionStrength);
    vec2 emissiveUv = transformedUv(EmissiveTextureCoordinateSet, EmissiveUvTransform, EmissiveUvRotation);
    vec3 emissiveSample = HasEmissiveTexture == 1 ? texture(Sampler5, emissiveUv).rgb : vec3(1.0);
    vec3 emissive = emissiveSample * EmissiveFactor;

    vec3 lit = (diffuseColor * diffuse + reflectance * specular) * lightMapColor.rgb * occlusion + emissive;
    vec3 outputColor = IsUnlit == 1 ? baseColor.rgb : lit;
    fragColor = linear_fog(vec4(outputColor, baseColor.a), vertexDistance, FogStart, FogEnd, FogColor);
}
