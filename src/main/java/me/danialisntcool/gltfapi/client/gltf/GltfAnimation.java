package me.danialisntcool.gltfapi.client.gltf;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

record GltfAnimation(String name, float duration, List<Channel> channels) {
    GltfAnimation {
        channels = List.copyOf(channels);
    }

    void apply(float requestedTime, GltfNode.NodePose[] poses) {
        float time = duration > 0.0F ? requestedTime % duration : 0.0F;
        if (time < 0.0F) {
            time += duration;
        }
        for (Channel channel : channels) {
            channel.apply(time, poses[channel.node()]);
        }
    }

    record Channel(int node, Path path, int components, Interpolation interpolation, float[] times, float[] values) {
        void apply(float time, GltfNode.NodePose pose) {
            int key = interval(time);
            int next = Math.min(key + 1, times.length - 1);
            float span = times[next] - times[key];
            float amount = key == next || span <= 0.0F ? 0.0F : (time - times[key]) / span;
            if (interpolation == Interpolation.STEP) {
                amount = 0.0F;
            }
            if (path == Path.ROTATION) {
                Quaternionf value;
                if (interpolation == Interpolation.CUBICSPLINE) {
                    float[] sampled = cubic(key, next, amount, span, components);
                    value = new Quaternionf(sampled[0], sampled[1], sampled[2], sampled[3]).normalize();
                } else {
                    int first = key * components;
                    int second = next * components;
                    Quaternionf start = new Quaternionf(values[first], values[first + 1], values[first + 2], values[first + 3]);
                    Quaternionf end = new Quaternionf(values[second], values[second + 1], values[second + 2], values[second + 3]);
                    value = start.slerp(end, amount).normalize();
                }
                pose.rotation().set(value);
            } else if (path == Path.WEIGHTS) {
                float[] sampled = interpolation == Interpolation.CUBICSPLINE
                        ? cubic(key, next, amount, span, components)
                        : linear(key, next, amount, components);
                System.arraycopy(sampled, 0, pose.weights(), 0, components);
            } else {
                float[] sampled = interpolation == Interpolation.CUBICSPLINE
                        ? cubic(key, next, amount, span, components)
                        : linear(key, next, amount, components);
                Vector3f target = path == Path.TRANSLATION ? pose.translation() : pose.scale();
                target.set(sampled[0], sampled[1], sampled[2]);
            }
        }

        private int interval(float time) {
            int low = 0;
            int high = times.length - 1;
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (times[middle] <= time) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            return low;
        }

        private float[] linear(int firstKey, int secondKey, float amount, int components) {
            float[] result = new float[components];
            int first = firstKey * components;
            int second = secondKey * components;
            for (int component = 0; component < components; component++) {
                result[component] = values[first + component]
                        + (values[second + component] - values[first + component]) * amount;
            }
            return result;
        }

        private float[] cubic(int firstKey, int secondKey, float amount, float span, int components) {
            float amount2 = amount * amount;
            float amount3 = amount2 * amount;
            float h00 = 2.0F * amount3 - 3.0F * amount2 + 1.0F;
            float h10 = amount3 - 2.0F * amount2 + amount;
            float h01 = -2.0F * amount3 + 3.0F * amount2;
            float h11 = amount3 - amount2;
            int stride = components * 3;
            int firstValue = firstKey * stride + components;
            int firstOutTangent = firstKey * stride + components * 2;
            int secondInTangent = secondKey * stride;
            int secondValue = secondKey * stride + components;
            float[] result = new float[components];
            for (int component = 0; component < components; component++) {
                result[component] = h00 * values[firstValue + component]
                        + h10 * span * values[firstOutTangent + component]
                        + h01 * values[secondValue + component]
                        + h11 * span * values[secondInTangent + component];
            }
            return result;
        }
    }

    enum Path {
        TRANSLATION,
        ROTATION,
        SCALE,
        WEIGHTS
    }

    enum Interpolation {
        LINEAR,
        STEP,
        CUBICSPLINE
    }
}
