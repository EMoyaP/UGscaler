#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <string>

#include "net.h"
#include "gpu.h"
#include "cpu.h"

namespace {
constexpr int kModelScale = 4;
constexpr int kPadding = 10;
std::atomic<bool> cancelled{false};

std::string utf8(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

jobject createBitmap(JNIEnv* env, int width, int height) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argbField = env->GetStaticFieldID(
            configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argb = env->GetStaticObjectField(configClass, argbField);
    jmethodID create = env->GetStaticMethodID(
            bitmapClass, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    return env->CallStaticObjectMethod(bitmapClass, create, width, height, argb);
}

inline unsigned char channel(const ncnn::Mat& output, int c, int x, int y) {
    const float value = output.channel(c).row(y)[x] * 255.f;
    return static_cast<unsigned char>(std::max(0.f, std::min(255.f, value + .5f)));
}

inline unsigned char averagedChannel(
        const ncnn::Mat& output, int c, int x, int y, int step) {
    if (step == 1) return channel(output, c, x, y);
    float sum = 0.f;
    for (int yy = 0; yy < step; ++yy) {
        const float* row = output.channel(c).row(y + yy);
        for (int xx = 0; xx < step; ++xx) sum += row[x + xx];
    }
    float value = sum * (255.f / static_cast<float>(step * step));
    return static_cast<unsigned char>(std::max(0.f, std::min(255.f, value + .5f)));
}
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_mejorarfotos_app_NativeRealEsrgan_nativeEnhance(
        JNIEnv* env, jclass, jobject inputBitmap, jstring paramPath,
        jstring binPath, jint outputScale, jint tileSize, jboolean useGpu) {
    cancelled.store(false);
    if (!inputBitmap || (outputScale != 2 && outputScale != 4)) return nullptr;

    AndroidBitmapInfo inputInfo{};
    void* inputPixels = nullptr;
    if (AndroidBitmap_getInfo(env, inputBitmap, &inputInfo) != ANDROID_BITMAP_RESULT_SUCCESS
            || inputInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888
            || AndroidBitmap_lockPixels(env, inputBitmap, &inputPixels)
                    != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;

    const std::string param = utf8(env, paramPath);
    const std::string model = utf8(env, binPath);
    ncnn::create_gpu_instance();
    bool gpu = useGpu == JNI_TRUE && ncnn::get_gpu_count() > 0;
    ncnn::Net network;
    network.opt.num_threads = std::max(1, std::min(8, ncnn::get_cpu_count()));
    network.opt.use_vulkan_compute = gpu;
    network.opt.use_fp16_packed = gpu;
    network.opt.use_fp16_storage = gpu;
    network.opt.use_fp16_arithmetic = gpu;
    if (gpu) network.set_vulkan_device(ncnn::get_default_gpu_index());
    int loadResult = network.load_param(param.c_str());
    if (loadResult == 0) loadResult = network.load_model(model.c_str());
    if (loadResult != 0) {
        AndroidBitmap_unlockPixels(env, inputBitmap);
        ncnn::destroy_gpu_instance();
        return nullptr;
    }

    const int width = static_cast<int>(inputInfo.width);
    const int height = static_cast<int>(inputInfo.height);
    const int tile = std::max(64, std::min(256, static_cast<int>(tileSize)));
    jobject resultBitmap = createBitmap(env, width * outputScale, height * outputScale);
    if (!resultBitmap || env->ExceptionCheck()) {
        AndroidBitmap_unlockPixels(env, inputBitmap);
        network.clear();
        ncnn::destroy_gpu_instance();
        return nullptr;
    }
    AndroidBitmapInfo outputInfo{};
    void* outputPixels = nullptr;
    if (AndroidBitmap_getInfo(env, resultBitmap, &outputInfo) != ANDROID_BITMAP_RESULT_SUCCESS
            || AndroidBitmap_lockPixels(env, resultBitmap, &outputPixels)
                    != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, inputBitmap);
        network.clear();
        ncnn::destroy_gpu_instance();
        return nullptr;
    }
    std::memset(outputPixels, 0, outputInfo.stride * outputInfo.height);

    int status = 0;
    const int sampleStep = kModelScale / outputScale;
    for (int top = 0; top < height && status == 0; top += tile) {
        for (int left = 0; left < width; left += tile) {
            if (cancelled.load()) { status = -2; break; }
            const int contentWidth = std::min(tile, width - left);
            const int contentHeight = std::min(tile, height - top);
            const int sourceLeft = std::max(0, left - kPadding);
            const int sourceTop = std::max(0, top - kPadding);
            const int sourceRight = std::min(width, left + contentWidth + kPadding);
            const int sourceBottom = std::min(height, top + contentHeight + kPadding);

            const unsigned char* rgba = static_cast<const unsigned char*>(inputPixels);
            ncnn::Mat tileInput = ncnn::Mat::from_pixels_roi(
                    rgba, ncnn::Mat::PIXEL_RGBA2RGB,
                    width, height, static_cast<int>(inputInfo.stride),
                    sourceLeft, sourceTop,
                    sourceRight - sourceLeft, sourceBottom - sourceTop);
            const int padLeft = std::max(0, kPadding - left);
            const int padTop = std::max(0, kPadding - top);
            const int padRight = std::max(0, left + contentWidth + kPadding - width);
            const int padBottom = std::max(0, top + contentHeight + kPadding - height);
            ncnn::Mat padded;
            ncnn::copy_make_border(tileInput, padded, padTop, padBottom,
                    padLeft, padRight, 2, 0.f, network.opt);
            const float normalize[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
            padded.substract_mean_normalize(nullptr, normalize);

            ncnn::Extractor extractor = network.create_extractor();
            if (extractor.input("data", padded) != 0) { status = -3; break; }
            ncnn::Mat neural;
            if (extractor.extract("output", neural) != 0 || neural.c < 3) {
                status = -4;
                break;
            }

            const int interiorX = (left - sourceLeft + padLeft) * kModelScale;
            const int interiorY = (top - sourceTop + padTop) * kModelScale;
            for (int y = 0; y < contentHeight * outputScale; ++y) {
                auto* destination = static_cast<unsigned char*>(outputPixels)
                        + (top * outputScale + y) * outputInfo.stride
                        + left * outputScale * 4;
                int sourceY = interiorY + y * sampleStep;
                for (int x = 0; x < contentWidth * outputScale; ++x) {
                    int sourceX = interiorX + x * sampleStep;
                    destination[x * 4] = averagedChannel(neural, 0, sourceX, sourceY, sampleStep);
                    destination[x * 4 + 1] = averagedChannel(neural, 1, sourceX, sourceY, sampleStep);
                    destination[x * 4 + 2] = averagedChannel(neural, 2, sourceX, sourceY, sampleStep);
                    destination[x * 4 + 3] = 255;
                }
            }
        }
    }

    AndroidBitmap_unlockPixels(env, resultBitmap);
    AndroidBitmap_unlockPixels(env, inputBitmap);
    network.clear();
    ncnn::destroy_gpu_instance();
    return status == 0 ? resultBitmap : nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mejorarfotos_app_NativeRealEsrgan_nativeCancel(JNIEnv*, jclass) {
    cancelled.store(true);
}
