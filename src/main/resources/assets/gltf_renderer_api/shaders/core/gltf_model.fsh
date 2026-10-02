#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;
uniform sampler2D Sampler7;
uniform vec4 ColorModulator;
uniform vec4 BaseColorFactor;
uniform int AlphaMode;
uniform float EnvironmentStrength;
uniform mat3 IViewRotMat;
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
uniform int BaseColorDecodedSrgb;
uniform int EmissiveDecodedSrgb;
uniform int IsUnlit;
uniform int GuiMode;
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
in vec4 overlayColor;
in vec2 primaryTextureCoordinates;
in vec2 secondaryTextureCoordinates;
in vec3 viewPosition;
in vec3 viewNormal;

out vec4 fragColor;

vec3 safeNormal(vec3 value) {
    float squaredLength = dot(value, value);
    return squaredLength > 0.00000001 ? value * inversesqrt(squaredLength) : vec3(0.0, 0.0, 1.0);
}

vec3 toLinear(vec3 value) {
    return mix(value / 12.92, pow((value + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), value));
}

vec3 toSrgb(vec3 value) {
    value = max(value, vec3(0.0));
    return mix(value * 12.92, 1.055 * pow(value, vec3(1.0 / 2.4)) - 0.055,
            step(vec3(0.0031308), value));
}

vec3 directLight(vec3 normal, vec3 view, vec3 light, vec3 albedo, float metallic, float roughness) {
    float nDotL = max(dot(normal, light), 0.0);
    float nDotV = max(dot(normal, view), 0.0001);
    vec3 halfway = safeNormal(light + view);
    float nDotH = max(dot(normal, halfway), 0.0);
    float vDotH = max(dot(view, halfway), 0.0);
    float alpha = roughness * roughness;
    float alphaSquared = alpha * alpha;
    float denominator = nDotH * nDotH * (alphaSquared - 1.0) + 1.0;
    float distribution = alphaSquared / max(3.14159265 * denominator * denominator, 0.0000001);
    float visibility = 0.5 / max(nDotL * sqrt(nDotV * nDotV * (1.0 - alphaSquared) + alphaSquared)
            + nDotV * sqrt(nDotL * nDotL * (1.0 - alphaSquared) + alphaSquared), 0.0001);
    vec3 f0 = mix(vec3(0.04), albedo, metallic);
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - vDotH, 5.0);
    vec3 diffuse = (1.0 - fresnel) * (1.0 - metallic) * albedo / 3.14159265;
    return (diffuse + fresnel * distribution * visibility) * nDotL;
}

vec3 environmentLight(vec3 normal, vec3 view, vec3 albedo, float metallic, float roughness) {
    vec3 reflected = safeNormal(GuiMode == 1 ? reflect(-view, normal) : IViewRotMat * reflect(-view, normal));
    vec2 environmentUv = vec2(atan(reflected.z, reflected.x) / 6.28318531 + 0.5,
            acos(clamp(reflected.y, -1.0, 1.0)) / 3.14159265);
    vec3 radiance = toLinear(textureLod(Sampler7, environmentUv, roughness * 7.0).rgb);
    vec3 f0 = mix(vec3(0.04), albedo, metallic);
    float nDotV = max(dot(normal, view), 0.0);
    vec4 coefficients = roughness * vec4(-1.0, -0.0275, -0.572, 0.022)
            + vec4(1.0, 0.0425, 1.04, -0.04);
    float a004 = min(coefficients.x * coefficients.x, exp2(-9.28 * nDotV))
            * coefficients.x + coefficients.y;
    vec2 integrated = vec2(-1.04, 1.04) * a004 + coefficients.zw;
    return radiance * max(f0 * integrated.x + integrated.y, vec3(0.0)) * EnvironmentStrength;
}

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
    float uvMagnitude = max(dot(uvDx, uvDx), dot(uvDy, uvDy));
    float positionMagnitude = max(dot(positionDx, positionDx), dot(positionDy, positionDy));
    if (uvMagnitude < 1e-30 || positionMagnitude < 1e-30) return safeNormal(viewNormal);
    uvDx *= inversesqrt(uvMagnitude);
    uvDy *= inversesqrt(uvMagnitude);
    positionDx *= inversesqrt(positionMagnitude);
    positionDy *= inversesqrt(positionMagnitude);
    float determinant = uvDx.x * uvDy.y - uvDx.y * uvDy.x;
    if (determinant * determinant < 1e-12 * dot(uvDx, uvDx) * dot(uvDy, uvDy)) {
        return safeNormal(viewNormal);
    }
    vec3 baseNormal = safeNormal(viewNormal);
    vec3 tangent = (positionDx * uvDy.y - positionDy * uvDx.y) * sign(determinant);
    tangent -= baseNormal * dot(baseNormal, tangent);
    if (dot(tangent, tangent) < 1e-20) return baseNormal;
    tangent = safeNormal(tangent);
    vec3 rawBitangent = (positionDy * uvDx.x - positionDx * uvDy.x) * sign(determinant);
    vec3 perpendicular = cross(baseNormal, tangent);
    vec3 bitangent = safeNormal(perpendicular) * (dot(perpendicular, rawBitangent) < 0.0 ? -1.0 : 1.0);
    return safeNormal(mat3(tangent, bitangent, baseNormal) * sampled);
}

void main() {
    vec2 baseUv = transformedUv(TextureCoordinateSets.x, BaseColorUvTransform, BaseColorUvRotation);
    vec4 baseSample = HasBaseColorTexture == 1 ? texture(Sampler0, baseUv) : vec4(1.0);
    vec3 baseLinear = BaseColorDecodedSrgb == 1 ? baseSample.rgb : toLinear(baseSample.rgb);
    vec4 baseColor = vec4(baseLinear, baseSample.a) * vertexColor * BaseColorFactor;
    baseColor.rgb *= toLinear(ColorModulator.rgb);
    baseColor.a = AlphaMode == 0 ? ColorModulator.a : baseColor.a * ColorModulator.a;
    if (baseColor.a < AlphaCutoff) {
        discard;
    }
    if (IsUnlit == 1) {
        vec3 outputColor = mix(overlayColor.rgb, toSrgb(baseColor.rgb), overlayColor.a);
        fragColor = GuiMode == 1 ? vec4(outputColor, baseColor.a)
                : linear_fog(vec4(outputColor, baseColor.a), vertexDistance, FogStart, FogEnd, FogColor);
        return;
    }

    vec2 metallicUv = transformedUv(TextureCoordinateSets.y, MetallicRoughnessUvTransform, MetallicRoughnessUvRotation);
    vec4 metallicRoughness = HasMetallicRoughnessTexture == 1 ? texture(Sampler1, metallicUv) : vec4(1.0);
    float metallic = clamp(MetallicFactor * metallicRoughness.b, 0.0, 1.0);
    float roughness = clamp(RoughnessFactor * metallicRoughness.g, 0.04, 1.0);

    vec2 normalUv = transformedUv(TextureCoordinateSets.z, NormalUvTransform, NormalUvRotation);
    vec3 normal = HasNormalTexture == 1 ? mappedNormal(normalUv) : safeNormal(viewNormal);
    if (!gl_FrontFacing) normal = -normal;
    vec3 normalDx = dFdx(normal);
    vec3 normalDy = dFdy(normal);
    float normalVariance = min(0.25, 0.25 * (dot(normalDx, normalDx) + dot(normalDy, normalDy)));
    roughness = sqrt(min(1.0, roughness * roughness + normalVariance));
    vec3 viewDirection = GuiMode == 1 ? vec3(0.0, 0.0, 1.0) : safeNormal(-viewPosition);
    vec3 direct = vec3(0.0);
    if (dot(Light0_Direction, Light0_Direction) > 0.00000001) {
        direct += directLight(normal, viewDirection, safeNormal(Light0_Direction), baseColor.rgb, metallic, roughness);
    }
    if (dot(Light1_Direction, Light1_Direction) > 0.00000001) {
        direct += directLight(normal, viewDirection, safeNormal(Light1_Direction), baseColor.rgb, metallic, roughness);
    }
    vec3 ambient = baseColor.rgb * (1.0 - metallic) * (GuiMode == 1 ? 0.3 : 0.2)
            + environmentLight(normal, viewDirection, baseColor.rgb, metallic, roughness);

    vec2 occlusionUv = transformedUv(TextureCoordinateSets.w, OcclusionUvTransform, OcclusionUvRotation);
    float sampledOcclusion = HasOcclusionTexture == 1 ? texture(Sampler4, occlusionUv).r : 1.0;
    float occlusion = mix(1.0, sampledOcclusion, OcclusionStrength);
    vec2 emissiveUv = transformedUv(EmissiveTextureCoordinateSet, EmissiveUvTransform, EmissiveUvRotation);
    vec3 emissiveSample = HasEmissiveTexture == 1 ? texture(Sampler5, emissiveUv).rgb : vec3(1.0);
    vec3 emissive = (EmissiveDecodedSrgb == 1 ? emissiveSample : toLinear(emissiveSample)) * EmissiveFactor;

    vec3 lit = (direct * 1.9 + ambient * occlusion) * toLinear(lightMapColor.rgb) + emissive;
    vec3 outputColor = toSrgb(lit);
    outputColor = mix(overlayColor.rgb, outputColor, overlayColor.a);
    fragColor = GuiMode == 1 ? vec4(outputColor, baseColor.a)
            : linear_fog(vec4(outputColor, baseColor.a), vertexDistance, FogStart, FogEnd, FogColor);
}
