#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <unordered_set>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <memory>
#include <mutex>
#include <cctype>

#include "apk_mmap.hpp"
#include "aho_corasick.hpp"
#include "lgbm_evaluator.hpp"

#define LOG_TAG "NativeScanner"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    privacyguard::AhoCorasick tracker_ac;
    privacyguard::LightGbmEvaluator evaluator;
};

static std::shared_ptr<const Engine> g_engine;
static std::mutex g_engine_mutex;

double computeSampledEntropy(const uint8_t* data, size_t len) {
    if (!data || len == 0) return 0.0;
    size_t sample_len = std::min(len, static_cast<size_t>(4096)); // 4 KB window
    uint32_t counts[256] = {0};
    for (size_t i = 0; i < sample_len; ++i) {
        counts[data[i]]++;
    }

    double entropy = 0.0;
    double log2 = std::log(2.0);
    double total = static_cast<double>(sample_len);

    for (int i = 0; i < 256; ++i) {
        if (counts[i] > 0) {
            double p = static_cast<double>(counts[i]) / total;
            entropy -= p * (std::log(p) / log2);
        }
    }
    return entropy;
}

double computeMultiWindowEntropy(const uint8_t* data, size_t len) {
    if (!data || len == 0) return 0.0;
    constexpr size_t WINDOW_SIZE = 4096;
    constexpr size_t NUM_WINDOWS = 16;
    if (len <= WINDOW_SIZE) return computeSampledEntropy(data, len);

    double total_entropy = 0.0;
    size_t count = 0;
    size_t step = (len - WINDOW_SIZE) / NUM_WINDOWS;
    if (step == 0) step = 1;

    for (size_t i = 0; i < NUM_WINDOWS && i * step + WINDOW_SIZE <= len; ++i) {
        total_entropy += computeSampledEntropy(data + i * step, WINDOW_SIZE);
        count++;
    }
    return (count > 0) ? (total_entropy / static_cast<double>(count)) : 0.0;
}

bool containsSubsequence(const uint8_t* data, size_t len, const char* target) {
    if (!data || !target) return false;
    size_t target_len = std::strlen(target);
    if (target_len == 0 || target_len > len) return false;

    for (size_t i = 0; i <= len - target_len; ++i) {
        if (std::memcmp(data + i, target, target_len) == 0) {
            return true;
        }
    }
    return false;
}

bool isDexFile(const std::string& name) {
    if (name == "classes.dex") return true;
    if (name.rfind("classes", 0) != 0) return false;
    if (name.size() < 11 || name.compare(name.size() - 4, 4, ".dex") != 0) return false;
    for (size_t i = 7; i < name.size() - 4; ++i) {
        if (!std::isdigit(static_cast<unsigned char>(name[i]))) return false;
    }
    return true;
}

jobject createScanResult(
    JNIEnv* env,
    int scanStatus,
    const std::vector<float>& features,
    double riskScore,
    double maxConfidence,
    const std::unordered_set<std::string>& matched_trackers,
    double entropy,
    bool hasDynamicLoading,
    const std::string& framework
) {
    jclass resultClass = env->FindClass("org/privacyguard/android/scan/NativeScanResult");
    if (!resultClass || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }

    jmethodID resultCtor = env->GetMethodID(
        resultClass,
        "<init>",
        "(I[FDDLjava/util/List;DZLjava/lang/String;)V"
    );
    if (!resultCtor || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }

    jfloatArray jFeatures = env->NewFloatArray(features.size());
    if (jFeatures) {
        env->SetFloatArrayRegion(jFeatures, 0, features.size(), features.data());
    }

    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID arrayListInit = env->GetMethodID(arrayListClass, "<init>", "()V");
    jmethodID arrayListAdd = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");
    jobject jTrackersList = env->NewObject(arrayListClass, arrayListInit);

    for (const auto& tracker : matched_trackers) {
        jstring jTracker = env->NewStringUTF(tracker.c_str());
        if (jTracker) {
            env->CallBooleanMethod(jTrackersList, arrayListAdd, jTracker);
            env->DeleteLocalRef(jTracker);
        }
    }

    jstring jFramework = env->NewStringUTF(framework.c_str());

    jobject resultObj = env->NewObject(
        resultClass,
        resultCtor,
        static_cast<jint>(scanStatus),
        jFeatures,
        riskScore,
        maxConfidence,
        jTrackersList,
        entropy,
        static_cast<jboolean>(hasDynamicLoading),
        jFramework
    );

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }

    return resultObj;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_privacyguard_android_scan_ScanService_nativeInitEngine(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray trackerAcBytes,
    jbyteArray modelBytes
) {
    if (!trackerAcBytes || !modelBytes) {
        return JNI_FALSE;
    }

    auto newEngine = std::make_shared<Engine>();

    jsize acLen = env->GetArrayLength(trackerAcBytes);
    jbyte* acData = env->GetByteArrayElements(trackerAcBytes, nullptr);
    if (acData) {
        newEngine->tracker_ac.loadPatternsFromMemory(reinterpret_cast<const uint8_t*>(acData), static_cast<size_t>(acLen));
        env->ReleaseByteArrayElements(trackerAcBytes, acData, JNI_ABORT);
    }

    jsize modelLen = env->GetArrayLength(modelBytes);
    jbyte* modelData = env->GetByteArrayElements(modelBytes, nullptr);
    if (modelData) {
        newEngine->evaluator.loadModel(reinterpret_cast<const uint8_t*>(modelData), static_cast<size_t>(modelLen));
        env->ReleaseByteArrayElements(modelBytes, modelData, JNI_ABORT);
    }

    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        g_engine = newEngine;
    }

    return JNI_TRUE;
}

JNIEXPORT jobject JNICALL
Java_org_privacyguard_android_scan_ScanService_nativeScanApkFd(
    JNIEnv* env,
    jobject /* thiz */,
    jint fd
) {
    std::shared_ptr<const Engine> engine;
    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        engine = g_engine;
    }

    if (!engine) {
        std::vector<float> emptyFeatures(16, 0.0f);
        return createScanResult(env, 3 /* STATUS_INIT_FAILED */, emptyFeatures, 0.0, 0.0, {}, 0.0, false, "UNKNOWN");
    }

    if (fd < 0) {
        std::vector<float> emptyFeatures(16, 0.0f);
        return createScanResult(env, 1 /* STATUS_PARSE_FAILED */, emptyFeatures, 0.0, 0.0, {}, 0.0, false, "UNKNOWN");
    }

    // Initialize archive from open FD without taking ownership of closing it (fdsan safe)
    privacyguard::ApkArchive archive(fd);
    if (!archive.openArchive()) {
        LOGE("Failed to mmap APK archive from fd: %d", fd);
        std::vector<float> emptyFeatures(16, 0.0f);
        return createScanResult(env, 1 /* STATUS_PARSE_FAILED */, emptyFeatures, 0.0, 0.0, {}, 0.0, false, "UNKNOWN");
    }

    const auto& entries = archive.getEntries();

    std::unordered_set<std::string> matched_trackers;
    uint32_t total_classes = 0;
    uint32_t dex_count = 0;
    uint32_t short_name_classes = 0;
    bool has_dynamic_loading = false;
    double max_entropy = 0.0;
    bool has_native_libs = false;
    bool has_native_danger = false;
    std::string framework = "Native";

    std::vector<uint8_t> buffer;

    for (const auto& entry : entries) {
        // 1. Prioritize framework detection before generic .so
        if (entry.name == "lib/arm64-v8a/libflutter.so" || entry.name == "assets/flutter_assets/NOTICES.Z") {
            framework = "Flutter";
            if (entry.name == "assets/flutter_assets/NOTICES.Z" && archive.extractEntry(entry, buffer)) {
                engine->tracker_ac.search(buffer.data(), buffer.size(), matched_trackers);
            }
        } else if (entry.name == "assets/index.android.bundle" || entry.name == "lib/arm64-v8a/libhermes.so") {
            framework = "Hermes";
            privacyguard::EntryView view;
            if (archive.getEntryView(entry, view) && view.size >= 8) {
                const uint8_t hermes_magic[8] = {0xc6, 0x1f, 0xbc, 0x03, 0xc1, 0x03, 0x19, 0x1f};
                if (std::memcmp(view.data, hermes_magic, 8) == 0) {
                    framework = "Hermes";
                }
            }
        } else if (entry.name == "assets/bin/Data/Managed/Metadata/global-metadata.dat" || entry.name == "lib/arm64-v8a/libil2cpp.so") {
            framework = "Unity";
            privacyguard::EntryView view;
            if (archive.getEntryView(entry, view) && view.size >= 4) {
                const uint8_t unity_magic[4] = {0xaf, 0x1b, 0xb1, 0xfa};
                if (std::memcmp(view.data, unity_magic, 4) == 0) {
                    framework = "Unity";
                }
            }
        }
        // 2. DEX file extraction and parsing
        else if (isDexFile(entry.name)) {
            dex_count++;
            privacyguard::EntryView view;
            const uint8_t* dexData = nullptr;
            size_t dexSize = 0;

            if (archive.getEntryView(entry, view)) {
                dexData = view.data;
                dexSize = view.size;
            } else if (archive.extractEntry(entry, buffer)) {
                dexData = buffer.data();
                dexSize = buffer.size();
            }

            if (dexData && dexSize >= 0x70 && std::memcmp(dexData, "dex\n", 4) == 0) {
                // Validate DEX header fields
                uint32_t headerSize = 0;
                uint32_t fileSize = 0;
                uint32_t classDefsSize = 0;
                std::memcpy(&headerSize, dexData + 36, sizeof(headerSize));
                std::memcpy(&fileSize, dexData + 32, sizeof(fileSize));
                std::memcpy(&classDefsSize, dexData + 0x60, sizeof(classDefsSize));

                if (headerSize == 0x70 && classDefsSize * 32 <= dexSize) {
                    total_classes += classDefsSize;

                    // Compute multi-window entropy
                    double ent = computeMultiWindowEntropy(dexData, dexSize);
                    if (ent > max_entropy) max_entropy = ent;

                    // Dynamic loading signatures
                    if (!has_dynamic_loading) {
                        if (containsSubsequence(dexData, dexSize, "Ldalvik/system/DexClassLoader;") ||
                            containsSubsequence(dexData, dexSize, "Ldalvik/system/InMemoryDexClassLoader;")) {
                            has_dynamic_loading = true;
                        }
                    }

                    // Aho-Corasick tracker search
                    engine->tracker_ac.search(dexData, dexSize, matched_trackers);

                    // Scan short-name class ratio
                    uint32_t stringIdsSize = 0;
                    uint32_t stringIdsOff = 0;
                    std::memcpy(&stringIdsSize, dexData + 0x38, sizeof(stringIdsSize));
                    std::memcpy(&stringIdsOff, dexData + 0x3C, sizeof(stringIdsOff));

                    if (stringIdsOff < dexSize) {
                        uint32_t limit = std::min(stringIdsSize, static_cast<uint32_t>(5000));
                        for (uint32_t s = 0; s < limit; ++s) {
                            size_t off = stringIdsOff + s * 4;
                            if (off + 4 > dexSize) break;
                            uint32_t strDataOff = 0;
                            std::memcpy(&strDataOff, dexData + off, sizeof(strDataOff));
                            if (strDataOff + 1 < dexSize) {
                                const uint8_t* p = dexData + strDataOff;
                                // Skip ULEB128 utf16_size
                                while (p < dexData + dexSize && (*p & 0x80)) p++;
                                if (p < dexData + dexSize) p++;

                                if (p < dexData + dexSize && *p == 'L') {
                                    const char* cstr = reinterpret_cast<const char*>(p);
                                    size_t maxLen = (dexData + dexSize) - p;
                                    size_t slen = strnlen(cstr, maxLen);
                                    if (slen > 1 && cstr[slen - 1] == ';') {
                                        // Exclude framework prefixes
                                        if (std::strncmp(cstr, "Landroid/", 9) != 0 &&
                                            std::strncmp(cstr, "Landroidx/", 10) != 0 &&
                                            std::strncmp(cstr, "Lkotlin/", 8) != 0 &&
                                            std::strncmp(cstr, "Lkotlinx/", 9) != 0 &&
                                            std::strncmp(cstr, "Ljava/", 6) != 0) {
                                            if (slen <= 5) {
                                                short_name_classes++;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 3. Native libraries
        else if (entry.name.rfind("lib/", 0) == 0 &&
                 entry.name.size() > 3 &&
                 entry.name.compare(entry.name.size() - 3, 3, ".so") == 0) {
            has_native_libs = true;

            if (!has_native_danger) {
                privacyguard::EntryView view;
                const uint8_t* soData = nullptr;
                size_t soSize = 0;

                if (archive.getEntryView(entry, view)) {
                    soData = view.data;
                    soSize = view.size;
                } else if (archive.extractEntry(entry, buffer)) {
                    soData = buffer.data();
                    soSize = buffer.size();
                }

                if (soData && soSize > 0) {
                    if (containsSubsequence(soData, soSize, "ptrace") ||
                        containsSubsequence(soData, soSize, "mprotect")) {
                        has_native_danger = true;
                    }
                }
            }
        }
    }

    // Build feature vector (16 dimensions)
    std::vector<float> features(16, 0.0f);
    features[0] = static_cast<float>(total_classes);
    features[1] = static_cast<float>(dex_count);
    features[2] = (total_classes > 0) ? (static_cast<float>(short_name_classes) / static_cast<float>(total_classes)) : 0.0f;
    features[3] = static_cast<float>(max_entropy);
    features[4] = has_dynamic_loading ? 1.0f : 0.0f;
    features[5] = static_cast<float>(matched_trackers.size());
    features[6] = has_native_libs ? 1.0f : 0.0f;
    features[7] = has_native_danger ? 1.0f : 0.0f;

    float framework_code = 0.0f;
    if (framework == "Flutter") framework_code = 1.0f;
    else if (framework == "Hermes") framework_code = 2.0f;
    else if (framework == "Unity") framework_code = 3.0f;
    features[8] = framework_code;

    // Evaluate risk and maxConfidence via LightGBM + Isotonic Calibration
    privacyguard::EvalResult eval = engine->evaluator.evaluate(features);

    return createScanResult(
        env,
        0 /* STATUS_OK */,
        features,
        eval.riskScore,
        eval.maxConfidence,
        matched_trackers,
        max_entropy,
        has_dynamic_loading,
        framework
    );
}

} // extern "C"
