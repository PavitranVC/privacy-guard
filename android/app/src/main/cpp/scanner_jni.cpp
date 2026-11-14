#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <unordered_set>
#include <cmath>
#include <algorithm>
#include <cstring>

#include "apk_mmap.hpp"
#include "aho_corasick.hpp"
#include "lgbm_evaluator.hpp"

#define LOG_TAG "NativeScanner"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

privacyguard::AhoCorasick g_tracker_ac;
privacyguard::LightGbmEvaluator g_evaluator;

double computeSampledEntropy(const uint8_t* data, size_t len) {
    if (!data || len == 0) return 0.0;
    size_t sample_len = std::min(len, static_cast<size_t>(65536)); // 64 KB sample
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

    jsize acLen = env->GetArrayLength(trackerAcBytes);
    jbyte* acData = env->GetByteArrayElements(trackerAcBytes, nullptr);
    if (acData) {
        g_tracker_ac = privacyguard::AhoCorasick();
        g_tracker_ac.loadPatternsFromMemory(reinterpret_cast<const uint8_t*>(acData), static_cast<size_t>(acLen));
        env->ReleaseByteArrayElements(trackerAcBytes, acData, JNI_ABORT);
    }

    jsize modelLen = env->GetArrayLength(modelBytes);
    jbyte* modelData = env->GetByteArrayElements(modelBytes, nullptr);
    if (modelData) {
        g_evaluator.loadModel(reinterpret_cast<const uint8_t*>(modelData), static_cast<size_t>(modelLen));
        env->ReleaseByteArrayElements(modelBytes, modelData, JNI_ABORT);
    }

    return JNI_TRUE;
}

JNIEXPORT jobject JNICALL
Java_org_privacyguard_android_scan_ScanService_nativeScanApk(
    JNIEnv* env,
    jobject /* thiz */,
    jstring apkPath
) {
    if (!apkPath) return nullptr;

    const char* pathStr = env->GetStringUTFChars(apkPath, nullptr);
    if (!pathStr) return nullptr;
    std::string path(pathStr);
    env->ReleaseStringUTFChars(apkPath, pathStr);

    privacyguard::ApkArchive archive(path);
    if (!archive.openArchive()) {
        LOGE("Failed to open APK archive: %s", path.c_str());
        return nullptr;
    }

    const auto& entries = archive.getEntries();

    std::unordered_set<std::string> matched_trackers;
    uint32_t total_classes = 0;
    uint32_t dex_count = 0;
    uint32_t short_name_classes = 0;
    bool has_dynamic_loading = false;
    double max_entropy = 0.0;
    bool has_native_libs = false;
    bool has_native_danger = false; // dlsym, ptrace, mprotect
    std::string framework = "Native";

    std::vector<uint8_t> buffer;

    for (const auto& entry : entries) {
        // Check for DEX files
        if (entry.name.rfind("classes", 0) == 0 && entry.name.size() >= 11 &&
            entry.name.compare(entry.name.size() - 4, 4, ".dex") == 0) {
            dex_count++;

            if (archive.extractEntry(entry, buffer) && buffer.size() >= 0x70) {
                // Verify DEX magic "dex\n"
                if (std::memcmp(buffer.data(), "dex\n", 4) == 0) {
                    double ent = computeSampledEntropy(buffer.data(), buffer.size());
                    if (ent > max_entropy) max_entropy = ent;

                    // Parse DEX class defs count
                    uint32_t class_defs_size = 0;
                    std::memcpy(&class_defs_size, buffer.data() + 0x60, sizeof(class_defs_size));
                    total_classes += class_defs_size;

                    // Scan for dynamic class loading signatures
                    if (!has_dynamic_loading) {
                        if (containsSubsequence(buffer.data(), buffer.size(), "Ldalvik/system/DexClassLoader;") ||
                            containsSubsequence(buffer.data(), buffer.size(), "Ldalvik/system/InMemoryDexClassLoader;")) {
                            has_dynamic_loading = true;
                        }
                    }

                    // Aho-Corasick tracker scanning over DEX strings
                    g_tracker_ac.search(buffer.data(), buffer.size(), matched_trackers);
                }
            }
        }
        // Check for native libraries
        else if (entry.name.rfind("lib/", 0) == 0 &&
                 entry.name.compare(entry.name.size() - 3, 3, ".so") == 0) {
            has_native_libs = true;

            // Sample .so imports for anti-debug / dynamic code injection symbols
            if (!has_native_danger && archive.extractEntry(entry, buffer)) {
                if (containsSubsequence(buffer.data(), buffer.size(), "ptrace") ||
                    containsSubsequence(buffer.data(), buffer.size(), "mprotect")) {
                    has_native_danger = true;
                }
            }
        }
        // Framework detection
        else if (entry.name == "assets/flutter_assets/NOTICES.Z" || entry.name == "lib/arm64-v8a/libflutter.so") {
            framework = "Flutter";
        }
        else if (entry.name == "assets/index.android.bundle") {
            if (archive.extractEntry(entry, buffer) && buffer.size() >= 8) {
                // Hermes bytecode magic: 0x1F1903C103BC1FC6 (little-endian: C6 1F BC 03 C1 03 19 1F)
                const uint8_t hermes_magic[8] = {0xc6, 0x1f, 0xbc, 0x03, 0xc1, 0x03, 0x19, 0x1f};
                if (std::memcmp(buffer.data(), hermes_magic, 8) == 0) {
                    framework = "Hermes";
                }
            }
        }
        else if (entry.name == "assets/bin/Data/Managed/Metadata/global-metadata.dat") {
            if (archive.extractEntry(entry, buffer) && buffer.size() >= 4) {
                // Unity metadata magic: 0xFAB11BAF (little endian: AF 1B B1 FA)
                const uint8_t unity_magic[4] = {0xaf, 0x1b, 0xb1, 0xfa};
                if (std::memcmp(buffer.data(), unity_magic, 4) == 0) {
                    framework = "Unity";
                }
            }
        }
    }

    // Build feature vector
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

    // Evaluate risk score via LightGBM + Isotonic Calibration
    double riskScore = g_evaluator.evaluate(features);

    // Prepare JNI return objects
    jfloatArray jFeatures = env->NewFloatArray(features.size());
    if (jFeatures) {
        env->SetFloatArrayRegion(jFeatures, 0, features.size(), features.data());
    }

    // ArrayList<String> for matched trackers
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

    jclass resultClass = env->FindClass("org/privacyguard/android/scan/NativeScanResult");
    if (!resultClass) return nullptr;

    jmethodID resultCtor = env->GetMethodID(
        resultClass,
        "<init>",
        "([FDLjava/util/List;DZLjava/lang/String;)V"
    );
    if (!resultCtor) return nullptr;

    jobject resultObj = env->NewObject(
        resultClass,
        resultCtor,
        jFeatures,
        riskScore,
        jTrackersList,
        max_entropy,
        static_cast<jboolean>(has_dynamic_loading),
        jFramework
    );

    return resultObj;
}

} // extern "C"
