#include <jni.h>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <android/bitmap.h>

// Один проход P0 вместо 4 Kotlin: tone + contrast + sat + wb + NEON friendly
// Вход: jintArray ARGB pixels (0xAARRGGBB), w*h
// Параметры уже адаптированы по яркости/сцене в Kotlin (toneStrength, contrast 1.087, sat 1.428, warmth)
// Делает всё в одном цикле — в 8-10× быстрее Kotlin getPixels loops

extern "C" JNIEXPORT void JNICALL
Java_com_example_thundercam20_imageprocessing_NativeProcessor_processPixels(
        JNIEnv* env, jobject /* this */,
        jintArray pixels, jint n,
        jfloat toneStrength, jfloat contrastFactor, jfloat satFactor, jfloat warmth,
        jfloat meanL) {

    jint* ptr = env->GetIntArrayElements(pixels, nullptr);
    if (!ptr) return;

    // В сумерках и темноте (meanL < 0.45) старая формула вычитала d*255 и гасила тени вдвое.
    // Теперь: при meanL < 0.45 мягко поднимаем тени (shadow lift) с защитой чёрного порога шума
    // и плавным компрессом светов (чтобы фонари не выгорали). На ярком свету — лёгкая S-кривая.
    // Сумеречный гард (0.18-0.48): HAL уже ярко — лифт ×0.35 и потолок gain 1.6,
    // иначе вытаскиваем шум из черноты (превью без лифта чище итога).
    bool twilit = (meanL >= 0.18f && meanL <= 0.48f);
    float gainCap = twilit ? 1.6f : 2.2f;
    float twK = twilit ? 0.45f : 1.0f;
    float shadowLift = 0.0f;
    float highlightPull = 0.0f;
    if (meanL < 0.45f) {
        float darkness = (0.45f - meanL) / 0.45f; // 0..1
        shadowLift = (0.12f + 0.26f * darkness) * toneStrength * twK; // до +0.38 в глубоких сумерках
        highlightPull = (0.08f + 0.06f * (1.0f - darkness)) * toneStrength;
    } else {
        shadowLift = 0.04f * toneStrength;
        highlightPull = (0.08f + 0.06f) * toneStrength;
    }

    // Средняя яркость для контраста: в сумерках не даём ярким фонарям задирать опорную точку
    long sumR=0, sumG=0, sumB=0;
    for (int i=0;i<n;i++) {
        jint p = ptr[i];
        sumR += (p >> 16) & 0xFF;
        sumG += (p >> 8) & 0xFF;
        sumB += p & 0xFF;
    }
    float meanR = sumR / (float)n;
    float meanG = sumG / (float)n;
    float meanB = sumB / (float)n;

    // wb gains: R/0.98 G/1.02 B/1.06 + bias -0.025*255
    float rGain = 1.0f/0.98f;
    float gGain = 1.0f/1.02f;
    float bGain = 1.0f/1.06f;
    // интерполируем по warmth
    rGain = 1.0f + (rGain - 1.0f) * warmth;
    gGain = 1.0f + (gGain - 1.0f) * warmth;
    bGain = 1.0f + (bGain - 1.0f) * warmth;
    float bBias = -0.025f * 255.0f * warmth;

    for (int i=0;i<n;i++) {
        jint p = ptr[i];
        int a = (p >> 24) & 0xFF;
        int r = (p >> 16) & 0xFF;
        int g = (p >> 8) & 0xFF;
        int b = p & 0xFF;

        // 1. Tone curve: подъём теней без задирания шумового пола + защита светов
        float lum = 0.299f * r + 0.587f * g + 0.114f * b;
        if (lum > 0.5f && toneStrength > 0.001f) {
            float x = lum / 255.0f; // 0..1
            // noiseGate не даёт поднимать совсем чёрный шум (x < 0.025)
            float noiseGate = x / (x + 0.022f);
            float shWeight = (1.0f - x) * (1.0f - x);
            float hiWeight = x * x;
            float xNew = x + shadowLift * shWeight * noiseGate - highlightPull * hiWeight * (1.0f - x);
            float gain = (xNew * 255.0f) / lum;
            if (gain < 0.75f) gain = 0.75f;
            if (gain > gainCap) gain = gainCap;
            r = (int)(r * gain); g = (int)(g * gain); b = (int)(b * gain);
        }

        // 2. Contrast per channel (в тенях ослабляем растяжку вниз, чтобы не проваливать тени обратно)
        float lumAfter = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f;
        float cWeight = lumAfter < 0.35f ? (0.35f + 0.65f * (lumAfter / 0.35f)) : 1.0f;
        float cEff = 1.0f + (contrastFactor - 1.0f) * cWeight;
        r = (int)((r - meanR) * cEff + meanR);
        g = (int)((g - meanG) * cEff + meanG);
        b = (int)((b - meanB) * cEff + meanB);

        // saturation via lerp gray
        // gray = (r+g+b)/3 approx, но берём luma для точности
        float gray = 0.299f * r + 0.587f * g + 0.114f * b;
        r = (int)(gray + (r - gray) * satFactor);
        g = (int)(gray + (g - gray) * satFactor);
        b = (int)(gray + (b - gray) * satFactor);

        // wb
        float fr = r * rGain;
        float fg = g * gGain;
        float fb = (b + bBias) * bGain;

        // Filmic-плечо: клипп сенсора (255,255,255) данных не имеет — вместо жесткого
        // среза мягко desat+jкомпрессия к белому. Деталей не вернет (их нет), но вместо
        // рваного пятна с цветной каймой будет плавный переход. Трогает только >235.
        {
            float mx = fr; if (fg > mx) mx = fg; if (fb > mx) mx = fb;
            if (mx > 235.0f) {
                float f = (mx - 235.0f) / 20.0f; if (f > 1.5f) f = 1.5f;
                float lumw = 0.299f * fr + 0.587f * fg + 0.114f * fb;
                float d = f * 0.6f; if (d > 0.9f) d = 0.9f;
                fr += (lumw - fr) * d; fg += (lumw - fg) * d; fb += (lumw - fb) * d;
                float mx2 = fr; if (fg > mx2) mx2 = fg; if (fb > mx2) mx2 = fb;
                if (mx2 > 235.0f) {
                    float k = (235.0f + (mx2 - 235.0f) * 0.5f) / mx2;
                    fr *= k; fg *= k; fb *= k;
                }
            }
        }
        r = (int)fr; g = (int)fg; b = (int)fb;

        if (r < 0) r = 0; if (r > 255) r = 255;
        if (g < 0) g = 0; if (g > 255) g = 255;
        if (b < 0) b = 0; if (b > 255) b = 255;

        ptr[i] = (a << 24) | (r << 16) | (g << 8) | b;
    }

    env->ReleaseIntArrayElements(pixels, ptr, 0);
}

// HDR-мерж 3 кадров zero-copy: вход — 3 Bitmap ARGB_8888 одного размера (dark, mid, bright),
// выход — готовый out Bitmap. Та же математика что mergeHdrBrackets в Kotlin:
// веса по яркости mid + попиксельный анти-гостинг (нормализованные dark×2/bright/2 vs mid).
// Память Bitmap — RGBA порядок байт (LE uint32 = 0xAABBGGRR), работаем побайтово чтобы не ошибиться.
// Возвращает false при любом несоответствии — Kotlin идёт в свой фолбэк.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_thundercam20_imageprocessing_NativeProcessor_mergeHdrBitmaps(
        JNIEnv* env, jobject /* this */,
        jobject dark, jobject mid, jobject bright, jobject out) {

    AndroidBitmapInfo iD, iM, iB, iO;
    if (AndroidBitmap_getInfo(env, dark, &iD) < 0) return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, mid, &iM) < 0) return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, bright, &iB) < 0) return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, out, &iO) < 0) return JNI_FALSE;
    if (iD.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        iM.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        iB.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        iO.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    uint32_t w = iM.width, h = iM.height;
    if (iD.width != w || iD.height != h || iB.width != w || iB.height != h ||
        iO.width != w || iO.height != h) return JNI_FALSE;

    uint8_t *pD = nullptr, *pM = nullptr, *pB = nullptr, *pO = nullptr;
    if (AndroidBitmap_lockPixels(env, dark, (void**)&pD) < 0) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, mid, (void**)&pM) < 0) { AndroidBitmap_unlockPixels(env, dark); return JNI_FALSE; }
    if (AndroidBitmap_lockPixels(env, bright, (void**)&pB) < 0) { AndroidBitmap_unlockPixels(env, dark); AndroidBitmap_unlockPixels(env, mid); return JNI_FALSE; }
    if (AndroidBitmap_lockPixels(env, out, (void**)&pO) < 0) {
        AndroidBitmap_unlockPixels(env, dark); AndroidBitmap_unlockPixels(env, mid); AndroidBitmap_unlockPixels(env, bright);
        return JNI_FALSE;
    }

    for (uint32_t y = 0; y < h; y++) {
        uint8_t* rD = pD + y * iD.stride;
        uint8_t* rM = pM + y * iM.stride;
        uint8_t* rB = pB + y * iB.stride;
        uint8_t* rO = pO + y * iO.stride;
        for (uint32_t x = 0; x < w; x++) {
            uint8_t* pd = rD + x * 4;
            uint8_t* pm = rM + x * 4;
            uint8_t* pb = rB + x * 4;
            float dr = pd[0], dg = pd[1], db = pd[2];
            float mr = pm[0], mg = pm[1], mb = pm[2];
            float br = pb[0], bg = pb[1], bb = pb[2];

            float lum = (0.299f * mr + 0.587f * mg + 0.114f * mb) / 255.0f;
            float wB = (0.4f - lum) / 0.4f;
            if (wB < 0) wB = 0; else if (wB > 1) wB = 1;
            float wD = (lum - 0.6f) / 0.4f;
            if (wD < 0) wD = 0; else if (wD > 1) wD = 1;
            float wM = 1.0f - wB - wD;
            if (wM < 0) wM = 0;

            // анти-гостинг как в Kotlin
            float dLum = ((0.299f * dr + 0.587f * dg + 0.114f * db) * 2.0f) / 255.0f;
            float bLum = ((0.299f * br + 0.587f * bg + 0.114f * bb) * 0.5f) / 255.0f;
            float ghost = (fabsf(dLum - lum) + fabsf(bLum - lum) - 0.12f) / 0.25f;
            if (ghost < 0) ghost = 0; else if (ghost > 1) ghost = 1;
            ghost *= 0.9f;
            wM += (wB + wD) * ghost; wB *= (1.0f - ghost); wD *= (1.0f - ghost);
            float sum = wB + wM + wD;
            if (sum < 1e-6f) sum = 1e-6f;
            float wb = wB / sum, wm = wM / sum, wd = wD / sum;

            int r = (int)(dr * wd + mr * wm + br * wb);
            int g = (int)(dg * wd + mg * wm + bg * wb);
            int b = (int)(db * wd + mb * wm + bb * wb);
            uint8_t* po = rO + x * 4;
            po[0] = r < 0 ? 0 : (r > 255 ? 255 : r);
            po[1] = g < 0 ? 0 : (g > 255 ? 255 : g);
            po[2] = b < 0 ? 0 : (b > 255 ? 255 : b);
            po[3] = 255;
        }
    }

    AndroidBitmap_unlockPixels(env, dark);
    AndroidBitmap_unlockPixels(env, mid);
    AndroidBitmap_unlockPixels(env, bright);
    AndroidBitmap_unlockPixels(env, out);
    return JNI_TRUE;
}

static inline void boxH(uint8_t* src, uint8_t* dst, uint32_t w, uint32_t h,
                        int sSrc, int sDst, int r) {
    int div = r * 2 + 1;
    for (uint32_t y = 0; y < h; y++) {
        uint8_t* rs = src + y * sSrc;
        uint8_t* rd = dst + y * sDst;
        int sr = 0, sg = 0, sb = 0;
        for (int x = -r; x <= r; x++) {
            int cx = x < 0 ? 0 : (x >= (int)w ? (int)w - 1 : x);
            uint8_t* p = rs + cx * 4;
            sr += p[0]; sg += p[1]; sb += p[2];
        }
        for (uint32_t x = 0; x < w; x++) {
            uint8_t* q = rd + x * 4;
            q[0] = sr / div; q[1] = sg / div; q[2] = sb / div; q[3] = 255;
            int xOut = (int)x - r; if (xOut < 0) xOut = 0; else if (xOut >= (int)w) xOut = w - 1;
            int xIn = (int)x + r + 1; if (xIn < 0) xIn = 0; else if (xIn >= (int)w) xIn = w - 1;
            uint8_t* po = rs + xOut * 4;
            uint8_t* pi = rs + xIn * 4;
            sr += pi[0] - po[0]; sg += pi[1] - po[1]; sb += pi[2] - po[2];
        }
    }
}

static inline void boxV(uint8_t* src, uint8_t* dst, uint32_t w, uint32_t h,
                        int sSrc, int sDst, int r) {
    int div = r * 2 + 1;
    for (uint32_t x = 0; x < w; x++) {
        int sr = 0, sg = 0, sb = 0;
        for (int y = -r; y <= r; y++) {
            int cy = y < 0 ? 0 : (y >= (int)h ? (int)h - 1 : y);
            uint8_t* p = src + cy * sSrc + x * 4;
            sr += p[0]; sg += p[1]; sb += p[2];
        }
        for (uint32_t y = 0; y < h; y++) {
            uint8_t* q = dst + y * sDst + x * 4;
            q[0] = sr / div; q[1] = sg / div; q[2] = sb / div; q[3] = 255;
            int yOut = (int)y - r; if (yOut < 0) yOut = 0; else if (yOut >= (int)h) yOut = h - 1;
            int yIn = (int)y + r + 1; if (yIn < 0) yIn = 0; else if (yIn >= (int)h) yIn = h - 1;
            uint8_t* po = src + yOut * sSrc + x * 4;
            uint8_t* pi = src + yIn * sSrc + x * 4;
            sr += pi[0] - po[0]; sg += pi[1] - po[1]; sb += pi[2] - po[2];
        }
    }
}

// Мягкий шумодав: 3× (box H + box V) ≈ гаусс + подмес к оригиналу.
// src/dst — Bitmap ARGB_8888 одного размера (обычно ≤2048, даунскейл делает Kotlin).
// false при любом несоответствии — Kotlin идёт в свой фолбэк.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_thundercam20_imageprocessing_NativeProcessor_softDenoise(
        JNIEnv* env, jobject /* this */,
        jobject src, jobject dst, jint radius, jfloat blend) {

    AndroidBitmapInfo iS, iD;
    if (AndroidBitmap_getInfo(env, src, &iS) < 0) return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, dst, &iD) < 0) return JNI_FALSE;
    if (iS.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        iD.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    uint32_t w = iS.width, h = iS.height;
    if (iD.width != w || iD.height != h || w == 0 || h == 0) return JNI_FALSE;
    int r = radius;
    if (r < 1) r = 1; else if (r > 8) r = 8;
    float bl = blend;
    if (bl < 0) bl = 0; else if (bl > 1) bl = 1;

    uint8_t *pS = nullptr, *pD = nullptr;
    if (AndroidBitmap_lockPixels(env, src, (void**)&pS) < 0) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, dst, (void**)&pD) < 0) { AndroidBitmap_unlockPixels(env, src); return JNI_FALSE; }

    size_t n = (size_t)w * h * 4;
    uint8_t* tmpA = (uint8_t*)malloc(n);
    uint8_t* tmpB = (uint8_t*)malloc(n);
    if (!tmpA || !tmpB) {
        if (tmpA) free(tmpA);
        if (tmpB) free(tmpB);
        AndroidBitmap_unlockPixels(env, src);
        AndroidBitmap_unlockPixels(env, dst);
        return JNI_FALSE;
    }
    // копия src в tmpA (stride может отличаться — построчно)
    for (uint32_t y = 0; y < h; y++) {
        uint8_t* s = pS + y * iS.stride;
        uint8_t* d = tmpA + (size_t)y * w * 4;
        for (uint32_t x = 0; x < w * 4; x++) d[x] = s[x];
    }
    for (int i = 0; i < 3; i++) {
        boxH(tmpA, tmpB, w, h, (int)(w * 4), (int)(w * 4), r);
        boxV(tmpB, tmpA, w, h, (int)(w * 4), (int)(w * 4), r);
    }
    // подмес к оригиналу
    float inv = 1.0f - bl;
    for (uint32_t y = 0; y < h; y++) {
        uint8_t* s = pS + y * iS.stride;
        uint8_t* b = tmpA + (size_t)y * w * 4;
        uint8_t* d = pD + y * iD.stride;
        for (uint32_t x = 0; x < w; x++) {
            uint8_t* ps = s + x * 4;
            uint8_t* pb = b + x * 4;
            uint8_t* pd = d + x * 4;
            pd[0] = (uint8_t)(ps[0] * inv + pb[0] * bl);
            pd[1] = (uint8_t)(ps[1] * inv + pb[1] * bl);
            pd[2] = (uint8_t)(ps[2] * inv + pb[2] * bl);
            pd[3] = 255;
        }
    }

    free(tmpA);
    free(tmpB);
    AndroidBitmap_unlockPixels(env, src);
    AndroidBitmap_unlockPixels(env, dst);
    return JNI_TRUE;
}

// RGB bitmap → NV12 YUV420 (full-range, как Kotlin bitmapToYuv420: Y=0.299/0.587/0.114, UV centered 128).
// yuv — предвыделенный ByteArray(w*h*3/2). Целочисленная математика, в разы быстрее Kotlin double-цикла.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_thundercam20_imageprocessing_NativeProcessor_bitmapToYuv420(
        JNIEnv* env, jobject /* this */,
        jobject bmp, jbyteArray yuvArr, jint w, jint h) {

    if (w <= 0 || h <= 0 || (w & 1) || (h & 1)) return JNI_FALSE;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bmp, &info) < 0) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if ((int)info.width != w || (int)info.height != h) return JNI_FALSE;

    jbyte* yuv = env->GetByteArrayElements(yuvArr, nullptr);
    if (!yuv) return JNI_FALSE;
    uint8_t* px = nullptr;
    if (AndroidBitmap_lockPixels(env, bmp, (void**)&px) < 0) {
        env->ReleaseByteArrayElements(yuvArr, yuv, JNI_ABORT);
        return JNI_FALSE;
    }

    // Y plane
    for (int y = 0; y < h; y++) {
        uint8_t* rowS = px + (size_t)y * info.stride;
        uint8_t* rowD = (uint8_t*)yuv + (size_t)y * w;
        for (int x = 0; x < w; x++) {
            uint8_t* p = rowS + x * 4;
            int Y = (77 * p[0] + 150 * p[1] + 29 * p[2]) >> 8;
            rowD[x] = Y < 0 ? 0 : (Y > 255 ? 255 : Y);
        }
    }
    // UV NV12 2x2 avg, U first then V
    uint8_t* uv = (uint8_t*)yuv + (size_t)w * h;
    for (int y = 0; y < h; y += 2) {
        for (int x = 0; x < w; x += 2) {
            int sumU = 0, sumV = 0;
            for (int dy = 0; dy < 2; dy++) {
                int yy = y + dy; if (yy >= h) yy = h - 1;
                uint8_t* rowS = px + (size_t)yy * info.stride;
                for (int dx = 0; dx < 2; dx++) {
                    int xx = x + dx; if (xx >= w) xx = w - 1;
                    uint8_t* p = rowS + xx * 4;
                    int R = p[0], G = p[1], B = p[2];
                    int U = ((-43 * R - 85 * G + 128 * B + 128) >> 8) + 128;
                    int V = ((128 * R - 107 * G - 21 * B + 128) >> 8) + 128;
                    sumU += U < 0 ? 0 : (U > 255 ? 255 : U);
                    sumV += V < 0 ? 0 : (V > 255 ? 255 : V);
                }
            }
            size_t o = ((size_t)(y / 2) * (w / 2) + (x / 2)) * 2;
            uv[o] = sumU / 4;
            uv[o + 1] = sumV / 4;
        }
    }

    AndroidBitmap_unlockPixels(env, bmp);
    env->ReleaseByteArrayElements(yuvArr, yuv, 0);
    return JNI_TRUE;
}

// Бленд stock→p0 in-place в dst (zero-copy, без IntArray): dst = stock*(1-s) + p0*s.
// stock/p0/dst — ARGB_8888 одного размера; dst может совпадать с p0. OOM невозможен.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_thundercam20_imageprocessing_NativeProcessor_blendBitmaps(
        JNIEnv* env, jobject /* this */,
        jobject stock, jobject p0, jobject dst, jfloat s) {
    if (s <= 0.0f || s > 1.0f) return JNI_FALSE;
    AndroidBitmapInfo iS, iP, iD;
    if (AndroidBitmap_getInfo(env, stock, &iS) < 0) return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, p0, &iP) < 0) return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, dst, &iD) < 0) return JNI_FALSE;
    if (iS.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        iP.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        iD.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    uint32_t w = iS.width, h = iS.height;
    if (w == 0 || h == 0) return JNI_FALSE;
    if (iP.width != w || iP.height != h || iD.width != w || iD.height != h) return JNI_FALSE;

    uint8_t *pS = nullptr, *pP = nullptr, *pD = nullptr;
    const bool aliasPD = env->IsSameObject(p0, dst) == JNI_TRUE;
    if (AndroidBitmap_lockPixels(env, stock, (void**)&pS) < 0) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, p0, (void**)&pP) < 0) { AndroidBitmap_unlockPixels(env, stock); return JNI_FALSE; }
    if (aliasPD) {
        pD = pP;
    } else if (AndroidBitmap_lockPixels(env, dst, (void**)&pD) < 0) {
        AndroidBitmap_unlockPixels(env, stock); AndroidBitmap_unlockPixels(env, p0);
        return JNI_FALSE;
    }
    // strides: при alias strides p0/dst могут отличаться? нет — тот же bitmap, берём iP для чтения и iD для записи
    float inv = 1.0f - s;
    bool same = (pP == pD);
    for (uint32_t y = 0; y < h; y++) {
        uint8_t* rs = pS + y * iS.stride;
        uint8_t* rp = pP + y * iP.stride;
        uint8_t* rd = pD + y * iD.stride;
        for (uint32_t x = 0; x < w; x++) {
            uint8_t* ps = rs + x * 4;
            uint8_t* pp = rp + x * 4;
            uint8_t* pd = rd + x * 4;
            // при same==true читаем pp до записи — порядок важен
            float sr = ps[0], sg = ps[1], sb = ps[2];
            float pr = pp[0], pg = pp[1], pb = pp[2];
            pd[0] = (uint8_t)(sr * inv + pr * s);
            pd[1] = (uint8_t)(sg * inv + pg * s);
            pd[2] = (uint8_t)(sb * inv + pb * s);
            pd[3] = 255;
            (void)same;
        }
    }
    AndroidBitmap_unlockPixels(env, stock);
    AndroidBitmap_unlockPixels(env, p0);
    if (!aliasPD) AndroidBitmap_unlockPixels(env, dst);
    return JNI_TRUE;
}

// Взвешенный мерж N кадров zero-copy: без FloatArray-аккумуляторов w*h*3 (50-144МБ).
// Для каждого пикселя идём по кадрам и копим на лету — пик O(N) вместо O(w*h).
// ref == null → чистое взвешенное среднее; ref != null → робастный rejectThr
// (пиксель кадра с max(|dr|,|dg|,|db|) > rejectThr отбрасывается, недобор — пиксель опоры).
// frames — jobjectArray Bitmap ARGB_8888 одного размера, weights — int[] той же длины,
// out — готовый Bitmap того же размера. false при любом несоответствии.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_thundercam20_imageprocessing_NativeProcessor_mergeWeightedN(
        JNIEnv* env, jobject /* this */,
        jobjectArray frames, jintArray weightsArr, jobject refOrNull, jobject out, jint rejectThr) {
    jsize n = env->GetArrayLength(frames);
    if (n <= 0 || n > 8) return JNI_FALSE;
    jsize wn = env->GetArrayLength(weightsArr);
    if (wn < n) return JNI_FALSE;
    jint* wPtr = env->GetIntArrayElements(weightsArr, nullptr);
    if (!wPtr) return JNI_FALSE;

    AndroidBitmapInfo iO;
    if (AndroidBitmap_getInfo(env, out, &iO) < 0) { env->ReleaseIntArrayElements(weightsArr, wPtr, JNI_ABORT); return JNI_FALSE; }
    if (iO.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || iO.width == 0 || iO.height == 0) {
        env->ReleaseIntArrayElements(weightsArr, wPtr, JNI_ABORT); return JNI_FALSE;
    }
    uint32_t w = iO.width, h = iO.height;
    float wSum = 0;
    for (int k = 0; k < n; k++) wSum += (float)(wPtr[k] <= 0 ? 0 : wPtr[k]);
    if (wSum < 1e-6f) { env->ReleaseIntArrayElements(weightsArr, wPtr, JNI_ABORT); return JNI_FALSE; }

    uint8_t* fPtr[8] = {nullptr,nullptr,nullptr,nullptr,nullptr,nullptr,nullptr,nullptr};
    uint32_t fStride[8] = {0,0,0,0,0,0,0,0};
    jobject fObj[8] = {nullptr,nullptr,nullptr,nullptr,nullptr,nullptr,nullptr,nullptr};
    uint8_t* pR = nullptr;
    uint32_t sR = 0;
    bool useRef = (refOrNull != nullptr);
    uint8_t* pO = nullptr;
    for (int k = 0; k < n; k++) {
        jobject o = env->GetObjectArrayElement(frames, k);
        if (o == nullptr) goto fail;
        fObj[k] = o;
        AndroidBitmapInfo ii;
        if (AndroidBitmap_getInfo(env, o, &ii) < 0) goto fail;
        if (ii.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || ii.width != w || ii.height != h) goto fail;
        if (AndroidBitmap_lockPixels(env, o, (void**)&fPtr[k]) < 0) { fPtr[k] = nullptr; goto fail; }
        fStride[k] = (uint32_t)ii.stride;
    }
    if (useRef) {
        AndroidBitmapInfo iR;
        if (AndroidBitmap_getInfo(env, refOrNull, &iR) < 0) goto fail;
        if (iR.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || iR.width != w || iR.height != h) goto fail;
        if (AndroidBitmap_lockPixels(env, refOrNull, (void**)&pR) < 0) { pR = nullptr; goto fail; }
        sR = (uint32_t)iR.stride;
    }
    if (AndroidBitmap_lockPixels(env, out, (void**)&pO) < 0) goto fail;

    {
        int rt = rejectThr;
        if (rt < 0) rt = 0; else if (rt > 100) rt = 100;
        float wk[8];
        for (int k = 0; k < n; k++) wk[k] = (float)(wPtr[k] <= 0 ? 0 : wPtr[k]) / wSum;
        for (uint32_t y = 0; y < h; y++) {
            uint8_t* rO = pO + (size_t)y * iO.stride;
            uint8_t* rR = useRef ? (pR + (size_t)y * sR) : nullptr;
            for (uint32_t x = 0; x < w; x++) {
                if (!useRef) {
                    float rA = 0, gA = 0, bA = 0;
                    for (int k = 0; k < n; k++) {
                        uint8_t* p = fPtr[k] + (size_t)y * fStride[k] + (size_t)x * 4;
                        float wkk = wk[k];
                        if (wkk <= 0) continue;
                        rA += p[0] * wkk; gA += p[1] * wkk; bA += p[2] * wkk;
                    }
                    uint8_t* po = rO + x * 4;
                    int r = (int)(rA + 0.5f), g = (int)(gA + 0.5f), b = (int)(bA + 0.5f);
                    po[0] = r < 0 ? 0 : (r > 255 ? 255 : (uint8_t)r);
                    po[1] = g < 0 ? 0 : (g > 255 ? 255 : (uint8_t)g);
                    po[2] = b < 0 ? 0 : (b > 255 ? 255 : (uint8_t)b);
                    po[3] = 255;
                } else {
                    uint8_t* pr = rR + x * 4;
                    int rr = pr[0], gg = pr[1], bb = pr[2];
                    float rA = 0, gA = 0, bA = 0, wA = 0;
                    for (int k = 0; k < n; k++) {
                        uint8_t* p = fPtr[k] + (size_t)y * fStride[k] + (size_t)x * 4;
                        float wkk = wk[k];
                        if (wkk <= 0) continue;
                        int dr = (int)p[0] - rr; if (dr < 0) dr = -dr;
                        int dg = (int)p[1] - gg; if (dg < 0) dg = -dg;
                        int db = (int)p[2] - bb; if (db < 0) db = -db;
                        int m = dr; if (dg > m) m = dg; if (db > m) m = db;
                        if (m > rt) continue;
                        rA += p[0] * wkk; gA += p[1] * wkk; bA += p[2] * wkk; wA += wkk;
                    }
                    uint8_t* po = rO + x * 4;
                    if (wA > 1e-6f) {
                        int r = (int)(rA / wA + 0.5f), g = (int)(gA / wA + 0.5f), b = (int)(bA / wA + 0.5f);
                        po[0] = r < 0 ? 0 : (r > 255 ? 255 : (uint8_t)r);
                        po[1] = g < 0 ? 0 : (g > 255 ? 255 : (uint8_t)g);
                        po[2] = b < 0 ? 0 : (b > 255 ? 255 : (uint8_t)b);
                    } else {
                        po[0] = (uint8_t)rr; po[1] = (uint8_t)gg; po[2] = (uint8_t)bb;
                    }
                    po[3] = 255;
                }
            }
        }
    }

    AndroidBitmap_unlockPixels(env, out);
    if (useRef && pR) AndroidBitmap_unlockPixels(env, refOrNull);
    for (int k = 0; k < n; k++) { if (fPtr[k]) AndroidBitmap_unlockPixels(env, fObj[k]); env->DeleteLocalRef(fObj[k]); }
    env->ReleaseIntArrayElements(weightsArr, wPtr, JNI_ABORT);
    return JNI_TRUE;

fail:
    if (pO) AndroidBitmap_unlockPixels(env, out);
    if (useRef && pR) AndroidBitmap_unlockPixels(env, refOrNull);
    for (int k = 0; k < n; k++) { if (fPtr[k]) AndroidBitmap_unlockPixels(env, fObj[k]); if (fObj[k]) env->DeleteLocalRef(fObj[k]); }
    env->ReleaseIntArrayElements(weightsArr, wPtr, JNI_ABORT);
    return JNI_FALSE;
}

// Настоящий exposure fusion по Mertens et al. 2007.
//
// Веса попиксельно: контраст (|лапласиан| люмы) × насыщенность (std RGB) ×
// экспонированность (гаусс каналов вокруг 0.5, sigma 0.2) × анти-гостинг
// (сходство с опорным кадром refIdx, sigma 0.25).
// Бленд — пирамидами Лапласа люмы + гауссовыми пирамидами весов: никаких швов
// и ореолов, в отличие от попиксельного среднего. Хрома — взвешенное среднее
// по тем же полноразмерным весам (веса гладкие — цветных швов нет); это в 3 раза
// легче полного RGB-пирамидального стека.
// Пирамиды строятся инкрементно уровень за уровнем (в памяти только текущий
// уровень + уже смешанные верхние), пик ~20B/px. Любой malloc fail /
// несоответствие — честный false, вызыватель идёт в mergeWeighted.
namespace mertens {

static inline float clampf01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

// 5-тапный биномиальный редуктор [1 4 6 4 1]/16 + децимация. Выход (w+1)/2 x (h+1)/2.
static float* reduceHalf(const float* src, int w, int h, int* ow, int* oh) {
    int nw = (w + 1) / 2; int nh = (h + 1) / 2;
    if (nw < 1) nw = 1; if (nh < 1) nh = 1;
    float* tmp = (float*)malloc((size_t)nw * h * sizeof(float));
    float* dst = (float*)malloc((size_t)nw * nh * sizeof(float));
    if (!tmp || !dst) { if (tmp) free(tmp); if (dst) free(dst); return nullptr; }
    static const float k[5] = {1.f/16, 4.f/16, 6.f/16, 4.f/16, 1.f/16};
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < nw; x++) {
            int sx = x * 2;
            float s = 0;
            for (int t = -2; t <= 2; t++) {
                int ix = sx + t; if (ix < 0) ix = 0; else if (ix >= w) ix = w - 1;
                s += src[(size_t)y * w + ix] * k[t + 2];
            }
            tmp[(size_t)y * nw + x] = s;
        }
    }
    for (int y = 0; y < nh; y++) {
        int sy = y * 2;
        for (int x = 0; x < nw; x++) {
            float s = 0;
            for (int t = -2; t <= 2; t++) {
                int iy = sy + t; if (iy < 0) iy = 0; else if (iy >= h) iy = h - 1;
                s += tmp[(size_t)iy * nw + x] * k[t + 2];
            }
            dst[(size_t)y * nw + x] = s;
        }
    }
    free(tmp);
    *ow = nw; *oh = nh;
    return dst;
}

// Билинейный апскейл до точного размера (детерминированный expand: коллапс
// пирамиды с тем же expand даёт точную реконструкцию по построению).
static float* expandTo(const float* src, int sw, int sh, int dw, int dh) {
    float* dst = (float*)malloc((size_t)dw * dh * sizeof(float));
    if (!dst) return nullptr;
    if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) { free(dst); return nullptr; }
    float fx = sw > 1 && dw > 1 ? (float)(sw - 1) / (dw - 1) : 0;
    float fy = sh > 1 && dh > 1 ? (float)(sh - 1) / (dh - 1) : 0;
    for (int y = 0; y < dh; y++) {
        float sy = y * fy;
        int y0 = (int)sy; if (y0 < 0) y0 = 0; if (y0 >= sh) y0 = sh - 1;
        int y1 = y0 + 1; if (y1 >= sh) y1 = sh - 1;
        float wy = sy - (int)sy; if (sh <= 1) wy = 0;
        for (int x = 0; x < dw; x++) {
            float sx = x * fx;
            int x0 = (int)sx; if (x0 < 0) x0 = 0; if (x0 >= sw) x0 = sw - 1;
            int x1 = x0 + 1; if (x1 >= sw) x1 = sw - 1;
            float wx = sx - (int)sx; if (sw <= 1) wx = 0;
            float a = src[(size_t)y0 * sw + x0], b = src[(size_t)y0 * sw + x1];
            float c = src[(size_t)y1 * sw + x0], d = src[(size_t)y1 * sw + x1];
            dst[(size_t)y * dw + x] = a * (1 - wx) * (1 - wy) + b * wx * (1 - wy) + c * (1 - wx) * wy + d * wx * wy;
        }
    }
    return dst;
}

} // namespace mertens

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_thundercam20_imageprocessing_NativeProcessor_mergeMertensN(
        JNIEnv* env, jobject /* this */,
        jobjectArray frames, jint refIdx, jobject out) {
    using namespace mertens;
    jsize n = env->GetArrayLength(frames);
    if (n < 2 || n > 4) return JNI_FALSE;

    AndroidBitmapInfo iO;
    if (AndroidBitmap_getInfo(env, out, &iO) < 0) return JNI_FALSE;
    if (iO.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || iO.width == 0 || iO.height == 0) return JNI_FALSE;
    uint32_t w = iO.width, h = iO.height;
    size_t N = (size_t)w * h;
    int ref = refIdx < 0 ? 0 : (refIdx >= n ? n - 1 : refIdx);

    // Все объявления — здесь: дальше goto fail прыгает через них, clang такое режет.
    uint8_t* fPtr[4] = {nullptr, nullptr, nullptr, nullptr};
    uint32_t fStride[4] = {0, 0, 0, 0};
    jobject fObj[4] = {nullptr, nullptr, nullptr, nullptr};
    uint8_t* pO = nullptr;
    float* Y[4] = {nullptr, nullptr, nullptr, nullptr};
    float* W[4] = {nullptr, nullptr, nullptr, nullptr};
    float* Wn0[4] = {nullptr, nullptr, nullptr, nullptr};
    float* curY[4] = {nullptr, nullptr, nullptr, nullptr};
    float* curW[4] = {nullptr, nullptr, nullptr, nullptr};
    int curWy[4] = {0, 0, 0, 0};
    int curHy[4] = {0, 0, 0, 0};
    int L = 1;
    int* lw = nullptr;
    int* lh = nullptr;
    float** BL = nullptr;
    bool ok = true;
    float* img = nullptr; int imgW = 0, imgH = 0;
    for (int k = 0; k < n; k++) {
        jobject o = env->GetObjectArrayElement(frames, k);
        if (o == nullptr) goto fail;
        fObj[k] = o;
        AndroidBitmapInfo ii;
        if (AndroidBitmap_getInfo(env, o, &ii) < 0) goto fail;
        if (ii.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || ii.width != w || ii.height != h) goto fail;
        if (AndroidBitmap_lockPixels(env, o, (void**)&fPtr[k]) < 0) { fPtr[k] = nullptr; goto fail; }
        fStride[k] = (uint32_t)ii.stride;
    }
    if (AndroidBitmap_lockPixels(env, out, (void**)&pO) < 0) goto fail;

    // YUV-веса попиксельно из RGB 0..1.
    for (int k = 0; k < n; k++) {
        Y[k] = (float*)malloc(N * sizeof(float));
        W[k] = (float*)malloc(N * sizeof(float));
        if (!Y[k] || !W[k]) goto fail;
        for (uint32_t y = 0; y < h; y++) {
            uint8_t* row = fPtr[k] + (size_t)y * fStride[k];
            uint8_t* rowR = fPtr[ref] + (size_t)y * fStride[ref];
            for (uint32_t x = 0; x < w; x++) {
                uint8_t* p = row + x * 4;
                float r = p[0] / 255.f, g = p[1] / 255.f, b = p[2] / 255.f;
                float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                size_t i = (size_t)y * w + x;
                Y[k][i] = lum;
                // Контраст: 4-соседний лапласиан (границы — 0).
                float c = 0;
                if (x > 0 && x + 1 < w && y > 0 && y + 1 < h) {
                    uint8_t* pl = p - 4; uint8_t* prr = p + 4;
                    uint8_t* pu = row - fStride[k] + x * 4; uint8_t* pd = row + fStride[k] + x * 4;
                    float yl = (0.299f * pl[0] + 0.587f * pl[1] + 0.114f * pl[2]) / 255.f;
                    float yr = (0.299f * prr[0] + 0.587f * prr[1] + 0.114f * prr[2]) / 255.f;
                    float yu = (0.299f * pu[0] + 0.587f * pu[1] + 0.114f * pu[2]) / 255.f;
                    float yd = (0.299f * pd[0] + 0.587f * pd[1] + 0.114f * pd[2]) / 255.f;
                    float dd = 4 * lum - yl - yr - yu - yd;
                    c = dd < 0 ? -dd : dd;
                }
                // Насыщенность: std RGB.
                float m = (r + g + b) / 3.f;
                float s = sqrtf(((r - m) * (r - m) + (g - m) * (g - m) + (b - m) * (b - m)) / 3.f);
                // Экспонированность: гаусс каналов вокруг 0.5, sigma 0.2.
                float e = expf(-((r - 0.5f) * (r - 0.5f) + (g - 0.5f) * (g - 0.5f) + (b - 0.5f) * (b - 0.5f)) / (2 * 0.04f));
                float wt = (c + 1e-3f) * (s + 1e-3f) * e;
                // Анти-гостинг: несхожесть с опорой гасит вес (кроме самой опоры).
                if (k != ref) {
                    uint8_t* q = rowR + x * 4;
                    float dr = r - q[0] / 255.f, dg = g - q[1] / 255.f, db = b - q[2] / 255.f;
                    float dd = (dr < 0 ? -dr : dr) + (dg < 0 ? -dg : dg) + (db < 0 ? -db : db);
                    dd /= 3.f;
                    wt *= expf(-(dd * dd) / (2 * 0.0625f));
                }
                W[k][i] = wt;
            }
        }
    }
    // Нормировка весов попиксельно + копия полноразмерных для хромы.
    for (size_t i = 0; i < N; i++) {
        float s = 0;
        for (int k = 0; k < n; k++) s += W[k][i];
        if (s < 1e-12f) {
            for (int k = 0; k < n; k++) W[k][i] = 1.f / n;
        } else {
            for (int k = 0; k < n; k++) W[k][i] /= s;
        }
    }
    for (int k = 0; k < n; k++) {
        Wn0[k] = (float*)malloc(N * sizeof(float));
        if (!Wn0[k]) goto fail;
        memcpy(Wn0[k], W[k], N * sizeof(float));
    }

    // Число уровней: пока меньшая сторона >= 128.
    { int m = w < h ? (int)w : (int)h; while (m >= 128) { m /= 2; L++; } }
    if (L > 8) L = 8;
    lw = (int*)malloc((size_t)L * sizeof(int));
    lh = (int*)malloc((size_t)L * sizeof(int));
    BL = (float**)malloc((size_t)L * sizeof(float*));
    if (!lw || !lh || !BL) { if (lw) free(lw); if (lh) free(lh); if (BL) free(BL); goto fail; }
    for (int l = 0; l < L; l++) { BL[l] = nullptr; }
    {
        int cw = (int)w, ch = (int)h;
        for (int l = 0; l < L; l++) { lw[l] = cw; lh[l] = ch; cw = (cw + 1) / 2; ch = (ch + 1) / 2; }
    }
    // Текущие гауссианы: старт с полных Y/W.
    for (int k = 0; k < 4; k++) { curY[k] = Y[k]; curW[k] = W[k]; curWy[k] = (int)w; curHy[k] = (int)h; }
    for (int l = 0; l < L - 1 && ok; l++) {
        float* nxtY[4] = {nullptr, nullptr, nullptr, nullptr};
        float* nxtW[4] = {nullptr, nullptr, nullptr, nullptr};
        int nw[4], nh[4];
        for (int k = 0; k < n && ok; k++) {
            nxtY[k] = reduceHalf(curY[k], curWy[k], curHy[k], &nw[k], &nh[k]);
            nxtW[k] = reduceHalf(curW[k], curWy[k], curHy[k], &nw[k], &nh[k]);
            if (!nxtY[k] || !nxtW[k]) ok = false;
        }
        if (!ok) {
            for (int k = 0; k < n; k++) { if (nxtY[k]) free(nxtY[k]); if (nxtW[k]) free(nxtW[k]); }
            break;
        }
        BL[l] = (float*)calloc((size_t)lw[l] * lh[l], sizeof(float));
        if (!BL[l]) ok = false;
        else {
            // Поп пикселям: expand каждого кадра отдельно, вычесть, взвесить ренорм-весами, освободить.
            for (int k = 0; k < n && ok; k++) {
                float* ex = expandTo(nxtY[k], nw[k], nh[k], lw[l], lh[l]);
                if (!ex) { ok = false; break; }
                for (int i = 0; i < lw[l] * lh[l]; i++) {
                    float s = 0;
                    for (int q = 0; q < n; q++) s += curW[q][i];
                    if (s < 1e-12f) s = 1e-12f;
                    BL[l][i] += (curW[k][i] / s) * (curY[k][i] - ex[i]);
                }
                free(ex);
            }
        }
        for (int k = 0; k < n; k++) { free(curY[k]); free(curW[k]); curY[k] = nxtY[k]; curW[k] = nxtW[k]; curWy[k] = nw[k]; curHy[k] = nh[k]; }
        Y[0]=Y[1]=Y[2]=Y[3]=nullptr; W[0]=W[1]=W[2]=W[3]=nullptr; // ownership moved to cur
    }
    // Верх: взвешенное среднее текущих гауссиан.
    if (ok) {
        BL[L - 1] = (float*)calloc((size_t)lw[L - 1] * lh[L - 1], sizeof(float));
        if (!BL[L - 1]) ok = false;
        else {
            for (int i = 0; i < lw[L - 1] * lh[L - 1]; i++) {
                float s = 0;
                for (int k = 0; k < n; k++) s += curW[k][i];
                if (s < 1e-12f) s = 1e-12f;
                float acc = 0;
                for (int k = 0; k < n; k++) acc += (curW[k][i] / s) * curY[k][i];
                BL[L - 1][i] = acc;
            }
        }
    }
    // Коллапс сверху вниз.
    imgW = 0; imgH = 0;
    if (ok) {
        img = BL[L - 1]; imgW = lw[L - 1]; imgH = lh[L - 1]; BL[L - 1] = nullptr;
        for (int l = L - 2; l >= 0 && ok; l--) {
            float* up = expandTo(img, imgW, imgH, lw[l], lh[l]);
            free(img); img = nullptr;
            if (!up) { ok = false; break; }
            for (int i = 0; i < lw[l] * lh[l]; i++) up[i] += BL[l][i];
            free(BL[l]); BL[l] = nullptr;
            img = up; imgW = lw[l]; imgH = lh[l];
        }
    }
    // Хрома взвешенным средним по полноразмерным весам + сборка RGB.
    if (ok && img && imgW == (int)w && imgH == (int)h) {
        for (uint32_t y = 0; y < h; y++) {
            uint8_t* rO = pO + (size_t)y * iO.stride;
            for (uint32_t x = 0; x < w; x++) {
                size_t i = (size_t)y * w + x;
                float yy = img[i]; if (yy < 0) yy = 0; else if (yy > 1) yy = 1;
                float u = 0, v = 0;
                for (int k = 0; k < n; k++) {
                    uint8_t* p = fPtr[k] + (size_t)y * fStride[k] + (size_t)x * 4;
                    float r = p[0] / 255.f, g = p[1] / 255.f, b = p[2] / 255.f;
                    float yk = 0.299f * r + 0.587f * g + 0.114f * b;
                    u += Wn0[k][i] * ((b - yk) * 0.5f + 0.5f);
                    v += Wn0[k][i] * ((r - yk) * 0.5f + 0.5f);
                }
                float uu = (u - 0.5f) * 2.f, vv = (v - 0.5f) * 2.f;
                float r = yy + 0.5f * vv, g = yy - 0.25f * uu - 0.25f * vv, b = yy + 0.5f * uu;
                uint8_t* po = rO + x * 4;
                int ri = (int)(r * 255 + 0.5f), gi = (int)(g * 255 + 0.5f), bi = (int)(b * 255 + 0.5f);
                po[0] = ri < 0 ? 0 : (ri > 255 ? 255 : (uint8_t)ri);
                po[1] = gi < 0 ? 0 : (gi > 255 ? 255 : (uint8_t)gi);
                po[2] = bi < 0 ? 0 : (bi > 255 ? 255 : (uint8_t)bi);
                po[3] = 255;
            }
        }
    } else if (ok) {
        ok = false;
    }

    if (img) free(img);
    for (int l = 0; l < L; l++) if (BL[l]) free(BL[l]);
    free(lw); free(lh); free(BL);
    for (int k = 0; k < n; k++) {
        if (curY[k]) free(curY[k]);
        if (curW[k]) free(curW[k]);
        if (Wn0[k]) free(Wn0[k]);
    }
    // Y/W ownership: freed via cur (nulled originals) — ничего двойно не чистим.
    AndroidBitmap_unlockPixels(env, out);
    for (int k = 0; k < n; k++) if (fPtr[k]) AndroidBitmap_unlockPixels(env, fObj[k]);
    for (int k = 0; k < n; k++) if (fObj[k]) env->DeleteLocalRef(fObj[k]);
    return ok ? JNI_TRUE : JNI_FALSE;

fail:
    if (pO) AndroidBitmap_unlockPixels(env, out);
    for (int k = 0; k < n; k++) { if (fPtr[k]) AndroidBitmap_unlockPixels(env, fObj[k]); if (fObj[k]) env->DeleteLocalRef(fObj[k]); }
    // fail достижим только до пирамидального цикла (все goto — раньше него):
    // cur* там ещё алиасят Y/W, чистим только базовые буферы.
    for (int k = 0; k < n; k++) {
        if (Y[k]) free(Y[k]);
        if (W[k]) free(W[k]);
        if (Wn0[k]) free(Wn0[k]);
    }
    return JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Треугольный dither ±1 LSB на готовом 8-бит кадре. Ломает бандинг в поднятых
// тенях и градиентах неба перед JPEG-энкодом: глазом невидим (±0.5 уровня),
// но убирает полосы, которые рисует единственное 8-битное округление лифта.
// In-place, без аллокаций. Только ARGB_8888.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_thundercam20_imageprocessing_NativeProcessor_ditherBitmap(
        JNIEnv* env, jobject /* this */, jobject bmp) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bmp, &info) < 0) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0)
        return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bmp, &pixels) < 0) return JNI_FALSE;
    uint8_t* base = (uint8_t*)pixels;
    uint32_t st = 0x9E3779B9u;
    for (uint32_t y = 0; y < info.height; y++) {
        uint8_t* row = base + (size_t)y * info.stride;
        for (uint32_t x = 0; x < info.width; x++) {
            uint8_t* q = row + (size_t)x * 4;
            for (int c = 0; c < 3; c++) {
                st = st * 1664525u + 1013904223u;
                float u1 = (st >> 8) * (1.0f / 16777216.0f);
                st = st * 1664525u + 1013904223u;
                float u2 = (st >> 8) * (1.0f / 16777216.0f);
                int v = (int)(q[c] + (u1 - u2) + 0.5f);
                q[c] = (uint8_t)(v < 0 ? 0 : (v > 255 ? 255 : v));
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bmp);
    return JNI_TRUE;
}

// P0 напрямую на Bitmap (zero-copy): тот же tone/contrast/sat/wb что processPixels,
// но без IntArray-транзиента (12Мп: 0 вместо 48МБ). src/dst могут совпадать (in-place).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_thundercam20_imageprocessing_NativeProcessor_processDirect(
        JNIEnv* env, jobject /* this */,
        jobject src, jobject dst,
        jfloat toneStrength, jfloat contrastFactor, jfloat satFactor, jfloat warmth,
        jfloat meanL) {
    AndroidBitmapInfo iS, iD;
    if (AndroidBitmap_getInfo(env, src, &iS) < 0) return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, dst, &iD) < 0) return JNI_FALSE;
    if (iS.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        iD.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    uint32_t w = iS.width, h = iS.height;
    if (w == 0 || h == 0) return JNI_FALSE;
    if (iD.width != w || iD.height != h) return JNI_FALSE;

    float shadowLift, highlightPull;
    bool twilitD = (meanL >= 0.18f && meanL <= 0.48f);
    float gainCapD = twilitD ? 1.6f : 2.2f;
    float twKD = twilitD ? 0.45f : 1.0f;
    if (meanL < 0.45f) {
        float darkness = (0.45f - meanL) / 0.45f;
        shadowLift = (0.12f + 0.26f * darkness) * toneStrength * twKD;
        highlightPull = (0.08f + 0.06f * (1.0f - darkness)) * toneStrength;
    } else {
        shadowLift = 0.04f * toneStrength;
        highlightPull = (0.08f + 0.06f) * toneStrength;
    }

    uint8_t *pS = nullptr, *pD = nullptr;
    const bool aliasSD = env->IsSameObject(src, dst) == JNI_TRUE;
    if (AndroidBitmap_lockPixels(env, src, (void**)&pS) < 0) return JNI_FALSE;
    if (aliasSD) { pD = pS; }
    else if (AndroidBitmap_lockPixels(env, dst, (void**)&pD) < 0) { AndroidBitmap_unlockPixels(env, src); return JNI_FALSE; }

    // средний цвет для контраста — одним проходом по src
    double sumR = 0, sumG = 0, sumB = 0;
    size_t n = (size_t)w * h;
    for (uint32_t y = 0; y < h; y++) {
        uint8_t* r = pS + y * iS.stride;
        for (uint32_t x = 0; x < w; x++) {
            uint8_t* p = r + x * 4;
            sumR += p[0]; sumG += p[1]; sumB += p[2];
        }
    }
    float meanR = (float)(sumR / n), meanG = (float)(sumG / n), meanB = (float)(sumB / n);

    float rGain = 1.0f + ((1.0f/0.98f) - 1.0f) * warmth;
    float gGain = 1.0f + ((1.0f/1.02f) - 1.0f) * warmth;
    float bGain = 1.0f + ((1.0f/1.06f) - 1.0f) * warmth;
    float bBias = -0.025f * 255.0f * warmth;

    for (uint32_t y = 0; y < h; y++) {
        uint8_t* rs = pS + y * iS.stride;
        uint8_t* rd = pD + y * iD.stride;
        for (uint32_t x = 0; x < w; x++) {
            uint8_t* ps = rs + x * 4;
            uint8_t* pd = rd + x * 4;
            float r = ps[0], g = ps[1], b = ps[2];
            float lum = 0.299f * r + 0.587f * g + 0.114f * b;
            if (lum > 0.5f && toneStrength > 0.001f) {
                float xx = lum / 255.0f;
                float noiseGate = xx / (xx + 0.022f);
                float shW = (1.0f - xx) * (1.0f - xx);
                float hiW = xx * xx;
                float xNew = xx + shadowLift * shW * noiseGate - highlightPull * hiW * (1.0f - xx);
                float gain = (xNew * 255.0f) / lum;
                if (gain < 0.75f) gain = 0.75f;
                else if (gain > gainCapD) gain = gainCapD;
                r *= gain; g *= gain; b *= gain;
            }
            float lumA = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f;
            float cW = lumA < 0.35f ? (0.35f + 0.65f * (lumA / 0.35f)) : 1.0f;
            float cEff = 1.0f + (contrastFactor - 1.0f) * cW;
            r = (r - meanR) * cEff + meanR;
            g = (g - meanG) * cEff + meanG;
            b = (b - meanB) * cEff + meanB;
            float gray = 0.299f * r + 0.587f * g + 0.114f * b;
            r = gray + (r - gray) * satFactor;
            g = gray + (g - gray) * satFactor;
            b = gray + (b - gray) * satFactor;
            r *= rGain; g *= gGain; b = (b + bBias) * bGain;
            // Filmic-плечо как в processPixels: мягкий переход вместо рваного клиппа.
            {
                float mx = r; if (g > mx) mx = g; if (b > mx) mx = b;
                if (mx > 235.0f) {
                    float f = (mx - 235.0f) / 20.0f; if (f > 1.5f) f = 1.5f;
                    float lumw = 0.299f * r + 0.587f * g + 0.114f * b;
                    float d = f * 0.6f; if (d > 0.9f) d = 0.9f;
                    r += (lumw - r) * d; g += (lumw - g) * d; b += (lumw - b) * d;
                    float mx2 = r; if (g > mx2) mx2 = g; if (b > mx2) mx2 = b;
                    if (mx2 > 235.0f) {
                        float k = (235.0f + (mx2 - 235.0f) * 0.5f) / mx2;
                        r *= k; g *= k; b *= k;
                    }
                }
            }
            pd[0] = r < 0 ? 0 : (r > 255 ? 255 : (uint8_t)r);
            pd[1] = g < 0 ? 0 : (g > 255 ? 255 : (uint8_t)g);
            pd[2] = b < 0 ? 0 : (b > 255 ? 255 : (uint8_t)b);
            pd[3] = 255;
        }
    }
    AndroidBitmap_unlockPixels(env, src);
    if (!aliasSD) AndroidBitmap_unlockPixels(env, dst);
    return JNI_TRUE;
}

// Винер-мерж для fixed-burst (HDR+ style): одинаковая экспозиция, тайл 16x16.
// Идея: var_tile = дисперсия опоры, noise из ISO-догадки вызывателя.
// w = var/(var + k*noise^2), out = ref*(1-w) + mean*w, выбросы vs ref отбрасываются.
// Хрома — тем же весом (цвет не шумит отдельно). Быстрее Мертенса в разы, резче.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_thundercam20_imageprocessing_NativeProcessor_mergeWienerN(
        JNIEnv* env, jobject /* this */,
        jobjectArray frames, jint refIdx, jobject out, jfloat noise, jint rejectThr) {
    jsize n = env->GetArrayLength(frames);
    if (n < 2 || n > 8) return JNI_FALSE;
    int ref = refIdx < 0 ? 0 : (refIdx >= n ? n - 1 : refIdx);
    float ns = noise;
    if (ns < 2) ns = 2; else if (ns > 30) ns = 30;
    int rt = rejectThr;
    if (rt < 0) rt = 0; else if (rt > 100) rt = 100;
    AndroidBitmapInfo iO;
    if (AndroidBitmap_getInfo(env, out, &iO) < 0) return JNI_FALSE;
    if (iO.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || iO.width == 0 || iO.height == 0) return JNI_FALSE;
    uint32_t w = iO.width, h = iO.height;
    uint8_t* fPtr[8] = {nullptr,nullptr,nullptr,nullptr,nullptr,nullptr,nullptr,nullptr};
    uint32_t fStride[8] = {0,0,0,0,0,0,0,0};
    jobject fObj[8] = {nullptr,nullptr,nullptr,nullptr,nullptr,nullptr,nullptr,nullptr};
    uint8_t* pO = nullptr;
    for (int k = 0; k < n; k++) {
        jobject o = env->GetObjectArrayElement(frames, k);
        if (o == nullptr) goto wfail;
        fObj[k] = o;
        AndroidBitmapInfo ii;
        if (AndroidBitmap_getInfo(env, o, &ii) < 0) goto wfail;
        if (ii.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || ii.width != w || ii.height != h) goto wfail;
        if (AndroidBitmap_lockPixels(env, o, (void**)&fPtr[k]) < 0) { fPtr[k] = nullptr; goto wfail; }
        fStride[k] = (uint32_t)ii.stride;
    }
    if (AndroidBitmap_lockPixels(env, out, (void**)&pO) < 0) goto wfail;
    {
        const int T = 16;
        float ns2 = ns * ns * 1.5f;
        for (uint32_t ty = 0; ty < h; ty += T) {
            for (uint32_t tx = 0; tx < w; tx += T) {
                uint32_t x1 = tx + T; if (x1 > w) x1 = w;
                uint32_t y1 = ty + T; if (y1 > h) y1 = h;
                // var опоры по люме в тайле
                double sum = 0, sum2 = 0; int cnt = 0;
                for (uint32_t y = ty; y < y1; y++) {
                    uint8_t* rr = fPtr[ref] + (size_t)y * fStride[ref];
                    for (uint32_t x = tx; x < x1; x++) {
                        uint8_t* p = rr + x * 4;
                        float lum = 0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2];
                        sum += lum; sum2 += lum * lum; cnt++;
                    }
                }
                double mean = cnt ? sum / cnt : 128;
                double var = cnt ? (sum2 / cnt - mean * mean) : 0;
                if (var < 0) var = 0;
                float ww = (float)(var / (var + ns2));
                if (ww < 0) ww = 0; else if (ww > 0.8f) ww = 0.8f;
                float wr = 1.0f - ww;
                for (uint32_t y = ty; y < y1; y++) {
                    uint8_t* rO = pO + (size_t)y * iO.stride;
                    uint8_t* rR = fPtr[ref] + (size_t)y * fStride[ref];
                    for (uint32_t x = tx; x < x1; x++) {
                        uint8_t* pr = rR + x * 4;
                        float rr0 = pr[0], gg0 = pr[1], bb0 = pr[2];
                        float aR = 0, aG = 0, aB = 0; float acc = 0;
                        for (int k = 0; k < n; k++) {
                            uint8_t* p = fPtr[k] + (size_t)y * fStride[k] + (size_t)x * 4;
                            int dr = (int)p[0] - (int)rr0; if (dr < 0) dr = -dr;
                            int dg = (int)p[1] - (int)gg0; if (dg < 0) dg = -dg;
                            int db = (int)p[2] - (int)bb0; if (db < 0) db = -db;
                            int m = dr; if (dg > m) m = dg; if (db > m) m = db;
                            if (m > rt) continue;
                            aR += p[0]; aG += p[1]; aB += p[2]; acc += 1;
                        }
                        uint8_t* po = rO + x * 4;
                        if (acc < 1) {
                            po[0]=(uint8_t)rr0; po[1]=(uint8_t)gg0; po[2]=(uint8_t)bb0; po[3]=255;
                        } else {
                            float mR = aR / acc, mG = aG / acc, mB = aB / acc;
                            int oR = (int)(rr0 * wr + mR * ww + 0.5f);
                            int oG = (int)(gg0 * wr + mG * ww + 0.5f);
                            int oB = (int)(bb0 * wr + mB * ww + 0.5f);
                            po[0]=oR<0?0:(oR>255?255:(uint8_t)oR);
                            po[1]=oG<0?0:(oG>255?255:(uint8_t)oG);
                            po[2]=oB<0?0:(oB>255?255:(uint8_t)oB);
                            po[3]=255;
                        }
                    }
                }
            }
        }
    }
    AndroidBitmap_unlockPixels(env, out);
    for (int k = 0; k < n; k++) { if (fPtr[k]) AndroidBitmap_unlockPixels(env, fObj[k]); if (fObj[k]) env->DeleteLocalRef(fObj[k]); }
    return JNI_TRUE;
wfail:
    if (pO) AndroidBitmap_unlockPixels(env, out);
    for (int k = 0; k < n; k++) { if (fPtr[k]) AndroidBitmap_unlockPixels(env, fObj[k]); if (fObj[k]) env->DeleteLocalRef(fObj[k]); }
    return JNI_FALSE;
}
