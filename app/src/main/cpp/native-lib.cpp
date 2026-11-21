#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#define LOG_TAG "NativeImageOptimizer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_imageprocessinglab_NativeImageProcessor_nativeResize(
        JNIEnv *env,
        jobject thiz,
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

    void *srcPixels = nullptr;
    void *dstPixels = nullptr;

    if (AndroidBitmap_lockPixels(env, src_bitmap, &srcPixels) < 0) {
        LOGE("Không khóa được vùng nhớ srcBitmap");
        return -2;
    }

    if (AndroidBitmap_lockPixels(env, dst_bitmap, &dstPixels) < 0) {
        AndroidBitmap_unlockPixels(env, src_bitmap);
        LOGE("Không khóa được vùng nhớ dstBitmap");
        return -3;
    }

    // MOCK LOGIC GIAI ĐOẠN 1 (Sẽ thay bằng thuật toán C++ thuần & ARM NEON ở Giai đoạn 2)
    LOGI("Mock Resize OK | Engine=%d | Src=(%ux%u) -> Dst=(%ux%u)",
         engine_type, srcInfo.width, srcInfo.height, dstInfo.width, dstInfo.height);

    AndroidBitmap_unlockPixels(env, dst_bitmap);
    AndroidBitmap_unlockPixels(env, src_bitmap);

    return 0;
}