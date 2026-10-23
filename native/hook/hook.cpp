#include <jni.h>
#include <lsplant.hpp>
#include <shadowhook.h>

#include <atomic>
#include <mutex>
#include <string>
#include <string_view>
#include <unordered_map>

namespace {

std::mutex stubs_lock;
std::unordered_map<void *, void *> stubs;
std::atomic<bool> ready{false};
void *art = nullptr;

void *inline_hook(void *target, void *hooker) {
    void *backup = nullptr;
    void *stub = shadowhook_hook_func_addr(target, hooker, &backup);
    if (stub == nullptr) {
        return nullptr;
    }
    std::lock_guard<std::mutex> guard(stubs_lock);
    stubs[target] = stub;
    return backup;
}

bool inline_unhook(void *target) {
    void *stub;
    {
        std::lock_guard<std::mutex> guard(stubs_lock);
        auto it = stubs.find(target);
        if (it == stubs.end()) {
            return false;
        }
        stub = it->second;
        stubs.erase(it);
    }
    return shadowhook_unhook(stub) == 0;
}

void *resolve(std::string_view name) {
    return shadowhook_dlsym(art, std::string(name).c_str());
}

void *resolve_prefix(std::string_view) {
    return nullptr;
}

bool start(JNIEnv *env) {
    if (shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false) != 0) {
        return false;
    }
    art = shadowhook_dlopen("libart.so");
    if (art == nullptr) {
        return false;
    }
    lsplant::InitInfo info;
    info.inline_hooker = inline_hook;
    info.inline_unhooker = inline_unhook;
    info.art_symbol_resolver = resolve;
    info.art_symbol_prefix_resolver = resolve_prefix;
    return lsplant::Init(env, info);
}

}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    ready = start(env);
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL Java_io_gpsz_zygote_LsNative_nativeReady(JNIEnv *, jclass) {
    return ready ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL Java_io_gpsz_zygote_LsNative_nativeHook(
        JNIEnv *env, jclass, jobject target, jobject hooker, jobject callback) {
    if (!ready) {
        return nullptr;
    }
    return lsplant::Hook(env, target, hooker, callback);
}

}
