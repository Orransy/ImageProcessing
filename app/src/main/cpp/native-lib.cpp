#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <vector>
#include <cstdint>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define HAS_ARM_NEON 1
#else
#define HAS_ARM_NEON 0
#endif

#define LOG_TAG "NativeImageOptimizer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

constexpr int ENGINE_CPP_PLAIN = 1;
constexpr int ENGINE_NEON = 2;

// 1. THUẬT TOÁN BILINEAR RESIZE BẰNG C++ THUẦN (Mốc so sánh E5)
static void resizeBilinearPlain(
        const uint8_t *srcPixels, int srcW, int srcH, int srcStride,
        uint8_t *dstPixels, int dstW, int dstH, int dstStride) {

    const float scaleX = static_cast<float>(srcW) / static_cast<float>(dstW);
    const float scaleY = static_cast<float>(srcH) / static_cast<float>(dstH);

    for (int y = 0; y < dstH; ++y) {
        float gy = y * scaleY;
        int y0 = static_cast<int>(gy);
        int y1 = std::min(y0 + 1, srcH - 1);
        uint16_t wy = static_cast<uint16_t>((gy - y0) * 256.0f);
        uint16_t invWy = 256 - wy;

        const uint8_t *row0 = srcPixels + y0 * srcStride;
        const uint8_t *row1 = srcPixels + y1 * srcStride;
        uint8_t *dstRow = dstPixels + y * dstStride;

        for (int x = 0; x < dstW; ++x) {
            float gx = x * scaleX;
            int x0 = static_cast<int>(gx);
            int x1 = std::min(x0 + 1, srcW - 1);
            uint16_t wx = static_cast<uint16_t>((gx - x0) * 256.0f);
            uint16_t invWx = 256 - wx;

            const uint8_t *p00 = row0 + x0 * 4;
            const uint8_t *p10 = row0 + x1 * 4;
            const uint8_t *p01 = row1 + x0 * 4;
            const uint8_t *p11 = row1 + x1 * 4;

            for (int c = 0; c < 4; ++c) {
                uint16_t top = (p00[c] * invWx + p10[c] * wx + 128) >> 8;
                uint16_t bot = (p01[c] * invWx + p11[c] * wx + 128) >> 8;
                dstRow[x * 4 + c] = static_cast<uint8_t>((top * invWy + bot * wy + 128) >> 8);
            }
        }
    }
}

// 2. THUẬT TOÁN BILINEAR RESIZE TỐI ƯU SIMD ARM NEON (Xử lý 4 pixel = 16 kênh màu mỗi vòng lặp)
static void resizeBilinearNeon(
        const uint8_t *srcPixels, int srcW, int srcH, int srcStride,
        uint8_t *dstPixels, int dstW, int dstH, int dstStride) {
#if HAS_ARM_NEON
    const float scaleX = static_cast<float>(srcW) / static_cast<float>(dstW);
    const float scaleY = static_cast<float>(srcH) / static_cast<float>(dstH);

    std::vector<int> x0Table(dstW);
    std::vector<int> x1Table(dstW);
    std::vector<uint16_t> wxTable(dstW);
    std::vector<uint16_t> invWxTable(dstW);

    for (int x = 0; x < dstW; ++x) {
        float gx = x * scaleX;
        int x0 = static_cast<int>(gx);
        x0Table[x] = x0 * 4;
        x1Table[x] = std::min(x0 + 1, srcW - 1) * 4;
        uint16_t wx = static_cast<uint16_t>((gx - x0) * 256.0f);
        wxTable[x] = wx;
        invWxTable[x] = 256 - wx;
    }

    for (int y = 0; y < dstH; ++y) {
        float gy = y * scaleY;
        int y0 = static_cast<int>(gy);
        int y1 = std::min(y0 + 1, srcH - 1);
        uint16_t wy = static_cast<uint16_t>((gy - y0) * 256.0f);
        uint16_t invWy = 256 - wy;

        uint16x8_t vWy = vdupq_n_u16(wy);
        uint16x8_t vInvWy = vdupq_n_u16(invWy);

        const uint8_t *row0 = srcPixels + y0 * srcStride;
        const uint8_t *row1 = srcPixels + y1 * srcStride;
        uint8_t *dstRow = dstPixels + y * dstStride;

        int x = 0;
        for (; x <= dstW - 4; x += 4) {
            uint32_t p00Arr[4], p10Arr[4], p01Arr[4], p11Arr[4];
            uint16_t wxLo[8], wxHi[8], invWxLo[8], invWxHi[8];

            for (int i = 0; i < 4; ++i) {
                int idx = x + i;
                p00Arr[i] = *reinterpret_cast<const uint32_t *>(row0 + x0Table[idx]);
                p10Arr[i] = *reinterpret_cast<const uint32_t *>(row0 + x1Table[idx]);
                p01Arr[i] = *reinterpret_cast<const uint32_t *>(row1 + x0Table[idx]);
                p11Arr[i] = *reinterpret_cast<const uint32_t *>(row1 + x1Table[idx]);
            }

            for (int c = 0; c < 4; ++c) {
                wxLo[c] = wxTable[x];
                wxLo[c + 4] = wxTable[x + 1];
                wxHi[c] = wxTable[x + 2];
                wxHi[c + 4] = wxTable[x + 3];

                invWxLo[c] = invWxTable[x];
                invWxLo[c + 4] = invWxTable[x + 1];
                invWxHi[c] = invWxTable[x + 2];
                invWxHi[c + 4] = invWxTable[x + 3];
            }

            uint8x16_t vP00 = vreinterpretq_u8_u32(vld1q_u32(p00Arr));
            uint8x16_t vP10 = vreinterpretq_u8_u32(vld1q_u32(p10Arr));
            uint8x16_t vP01 = vreinterpretq_u8_u32(vld1q_u32(p01Arr));
            uint8x16_t vP11 = vreinterpretq_u8_u32(vld1q_u32(p11Arr));

            uint16x8_t vWxLo = vld1q_u16(wxLo);
            uint16x8_t vWxHi = vld1q_u16(wxHi);
            uint16x8_t vInvWxLo = vld1q_u16(invWxLo);
            uint16x8_t vInvWxHi = vld1q_u16(invWxHi);
            uint16x8_t vRound = vdupq_n_u16(128);

            uint16x8_t topLo = vmlaq_u16(vmlaq_u16(vRound, vmovl_u8(vget_low_u8(vP00)), vInvWxLo),
                                         vmovl_u8(vget_low_u8(vP10)), vWxLo);
            uint16x8_t topHi = vmlaq_u16(vmlaq_u16(vRound, vmovl_u8(vget_high_u8(vP00)), vInvWxHi),
                                         vmovl_u8(vget_high_u8(vP10)), vWxHi);
            topLo = vshrq_n_u16(topLo, 8);
            topHi = vshrq_n_u16(topHi, 8);

            uint16x8_t botLo = vmlaq_u16(vmlaq_u16(vRound, vmovl_u8(vget_low_u8(vP01)), vInvWxLo),
                                         vmovl_u8(vget_low_u8(vP11)), vWxLo);
            uint16x8_t botHi = vmlaq_u16(vmlaq_u16(vRound, vmovl_u8(vget_high_u8(vP01)), vInvWxHi),
                                         vmovl_u8(vget_high_u8(vP11)), vWxHi);
            botLo = vshrq_n_u16(botLo, 8);
            botHi = vshrq_n_u16(botHi, 8);

            uint16x8_t resLo = vmlaq_u16(vmlaq_u16(vRound, topLo, vInvWy), botLo, vWy);
            uint16x8_t resHi = vmlaq_u16(vmlaq_u16(vRound, topHi, vInvWy), botHi, vWy);

            uint8x16_t result = vcombine_u8(vshrn_n_u16(resLo, 8), vshrn_n_u16(resHi, 8));
            vst1q_u8(dstRow + x * 4, result);
        }

        for (; x < dstW; ++x) {
            const uint8_t *p00 = row0 + x0Table[x];
            const uint8_t *p10 = row0 + x1Table[x];
            const uint8_t *p01 = row1 + x0Table[x];
            const uint8_t *p11 = row1 + x1Table[x];
            uint16_t wx = wxTable[x];
            uint16_t invWx = invWxTable[x];

            for (int c = 0; c < 4; ++c) {
                uint16_t top = (p00[c] * invWx + p10[c] * wx + 128) >> 8;
                uint16_t bot = (p01[c] * invWx + p11[c] * wx + 128) >> 8;
                dstRow[x * 4 + c] = static_cast<uint8_t>((top * invWy + bot * wy + 128) >> 8);
            }
        }
    }
#else
    resizeBilinearPlain(srcPixels, srcW, srcH, srcStride, dstPixels, dstW, dstH, dstStride);
#endif
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_imageprocessinglab_NativeImageProcessor_nativeResize(
        JNIEnv *env,
        jobject /* thiz */,
        jobject src_bitmap,
        jobject dst_bitmap,
        jint engine_type) {

    AndroidBitmapInfo srcInfo;
    AndroidBitmapInfo dstInfo;

    if (AndroidBitmap_getInfo(env, src_bitmap, &srcInfo) < 0 ||
        AndroidBitmap_getInfo(env, dst_bitmap, &dstInfo) < 0) {
        LOGE("Không đọc được thông tin Bitmap");
        return -1;
    }

    if (srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        dstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Chỉ hỗ trợ định dạng ARGB_8888");
        return -4;
    }

    void *srcPixels = nullptr;
    void *dstPixels = nullptr;

    if (AndroidBitmap_lockPixels(env, src_bitmap, &srcPixels) < 0) {
        return -2;
    }
    if (AndroidBitmap_lockPixels(env, dst_bitmap, &dstPixels) < 0) {
        AndroidBitmap_unlockPixels(env, src_bitmap);
        return -3;
    }

    if (engine_type == ENGINE_NEON) {
        resizeBilinearNeon(
                static_cast<const uint8_t *>(srcPixels), srcInfo.width, srcInfo.height, srcInfo.stride,
                static_cast<uint8_t *>(dstPixels), dstInfo.width, dstInfo.height, dstInfo.stride);
    } else {
        resizeBilinearPlain(
                static_cast<const uint8_t *>(srcPixels), srcInfo.width, srcInfo.height, srcInfo.stride,
                static_cast<uint8_t *>(dstPixels), dstInfo.width, dstInfo.height, dstInfo.stride);
    }

    AndroidBitmap_unlockPixels(env, dst_bitmap);
    AndroidBitmap_unlockPixels(env, src_bitmap);
    return 0;
}