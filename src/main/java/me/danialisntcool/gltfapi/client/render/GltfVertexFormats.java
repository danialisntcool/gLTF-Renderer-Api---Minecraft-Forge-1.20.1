package me.danialisntcool.gltfapi.client.render;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

public final class GltfVertexFormats {
    private static final VertexFormatElement UV1 = new VertexFormatElement(
            1, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.UV, 2);
    public static final VertexFormatElement MATERIAL_UV1 = new VertexFormatElement(
            3, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.UV, 2);
    private static final VertexFormatElement JOINTS = new VertexFormatElement(
            2, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.UV, 4);
    private static final VertexFormatElement WEIGHTS = new VertexFormatElement(
            3, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.UV, 4);
    private static final VertexFormatElement MORPH_POSITION_0 = genericVector(4);
    private static final VertexFormatElement MORPH_POSITION_1 = genericVector(5);
    private static final VertexFormatElement MORPH_POSITION_2 = genericVector(6);
    private static final VertexFormatElement MORPH_POSITION_3 = genericVector(7);
    private static final VertexFormatElement MORPH_NORMAL_0 = genericVector(8);
    private static final VertexFormatElement MORPH_NORMAL_1 = genericVector(9);
    private static final VertexFormatElement MORPH_NORMAL_2 = genericVector(10);
    private static final VertexFormatElement MORPH_NORMAL_3 = genericVector(11);

    public static final VertexFormat MODEL = new VertexFormat(ImmutableMap.<String, VertexFormatElement>builder()
            .put("Position", DefaultVertexFormat.ELEMENT_POSITION)
            .put("UV0", DefaultVertexFormat.ELEMENT_UV0)
            .put("UV1", UV1)
            .put("Color", DefaultVertexFormat.ELEMENT_COLOR)
            .put("Normal", DefaultVertexFormat.ELEMENT_NORMAL)
            .put("Padding", DefaultVertexFormat.ELEMENT_PADDING)
            .build());

    public static final VertexFormat BUFFERED_PBR = new VertexFormat(ImmutableMap.<String, VertexFormatElement>builder()
            .put("Position", DefaultVertexFormat.ELEMENT_POSITION)
            .put("Color", DefaultVertexFormat.ELEMENT_COLOR)
            .put("UV0", DefaultVertexFormat.ELEMENT_UV0)
            .put("UV1", DefaultVertexFormat.ELEMENT_UV1)
            .put("UV2", DefaultVertexFormat.ELEMENT_UV2)
            .put("Normal", DefaultVertexFormat.ELEMENT_NORMAL)
            .put("Padding", DefaultVertexFormat.ELEMENT_PADDING)
            .put("MaterialUV1", MATERIAL_UV1)
            .build());

    public static final VertexFormat SKINNED_MODEL = new VertexFormat(ImmutableMap.<String, VertexFormatElement>builder()
            .put("Position", DefaultVertexFormat.ELEMENT_POSITION)
            .put("UV0", DefaultVertexFormat.ELEMENT_UV0)
            .put("UV1", UV1)
            .put("Color", DefaultVertexFormat.ELEMENT_COLOR)
            .put("Normal", DefaultVertexFormat.ELEMENT_NORMAL)
            .put("Padding", DefaultVertexFormat.ELEMENT_PADDING)
            .put("Joints", JOINTS)
            .put("Weights", WEIGHTS)
            .build());

    public static final VertexFormat MORPHED_MODEL = new VertexFormat(ImmutableMap.<String, VertexFormatElement>builder()
            .put("Position", DefaultVertexFormat.ELEMENT_POSITION)
            .put("UV0", DefaultVertexFormat.ELEMENT_UV0)
            .put("UV1", UV1)
            .put("Color", DefaultVertexFormat.ELEMENT_COLOR)
            .put("Normal", DefaultVertexFormat.ELEMENT_NORMAL)
            .put("Padding", DefaultVertexFormat.ELEMENT_PADDING)
            .put("Joints", JOINTS)
            .put("Weights", WEIGHTS)
            .put("MorphPosition0", MORPH_POSITION_0)
            .put("MorphPosition1", MORPH_POSITION_1)
            .put("MorphPosition2", MORPH_POSITION_2)
            .put("MorphPosition3", MORPH_POSITION_3)
            .put("MorphNormal0", MORPH_NORMAL_0)
            .put("MorphNormal1", MORPH_NORMAL_1)
            .put("MorphNormal2", MORPH_NORMAL_2)
            .put("MorphNormal3", MORPH_NORMAL_3)
            .build());

    private static VertexFormatElement genericVector(int index) {
        return new VertexFormatElement(index, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.UV, 3);
    }

    private GltfVertexFormats() {
    }
}
