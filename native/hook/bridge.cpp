#include <jni.h>
#include <lsplant.hpp>

#include <string>
#include <string_view>

namespace {

using HookFn = void *(*)(void *, void *);
using UnhookFn = bool (*)(void *);
using ResolveFn = void *(*)(const char *);

HookFn g_hook = nullptr;
UnhookFn g_unhook = nullptr;
ResolveFn g_resolve = nullptr;
ResolveFn g_prefix = nullptr;

}

extern "C" {

bool gpsz_ls_init(JNIEnv *env, HookFn hook, UnhookFn unhook, ResolveFn resolve, ResolveFn prefix) {
    g_hook = hook;
    g_unhook = unhook;
    g_resolve = resolve;
    g_prefix = prefix;
    lsplant::InitInfo info;
    info.inline_hooker = [](void *target, void *hooker) -> void * { return g_hook(target, hooker); };
    info.inline_unhooker = [](void *target) -> bool { return g_unhook(target); };
    info.art_symbol_resolver = [](std::string_view name) -> void * {
        return g_resolve(std::string(name).c_str());
    };
    info.art_symbol_prefix_resolver = [](std::string_view prefix) -> void * {
        return g_prefix(std::string(prefix).c_str());
    };
    return lsplant::Init(env, info);
}

jobject gpsz_ls_hook(JNIEnv *env, jobject target, jobject hooker, jobject callback) {
    return lsplant::Hook(env, target, hooker, callback);
}

}
