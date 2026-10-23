#include <android/log.h>
#include <jni.h>
#include <lsplant.hpp>
#include <shadowhook.h>

#include <android/api-level.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <vector>

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

void note(const char *what, const std::string &name, int err) {
    __android_log_print(ANDROID_LOG_ERROR, "gpsz_hook", "%s %s errno=%d %s", what, name.c_str(), err,
                        shadowhook_to_errmsg(err));
}

void *inline_hook(void *target, void *hooker) {
    void *backup = nullptr;
    void *stub = shadowhook_hook_func_addr(target, hooker, &backup);
    if (stub == nullptr) {
        char buf[32];
        snprintf(buf, sizeof(buf), "%p", target);
        note("inline_hook gagal", buf, shadowhook_get_errno());
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
    std::string n(name);
    void *p = shadowhook_dlsym(art, n.c_str());
    if (p == nullptr) {
        p = shadowhook_dlsym_symtab(art, n.c_str());
    }
    if (p == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "gpsz_hook", "simbol tidak ada %s", std::string(name).c_str());
    }
    return p;
}

void *resolve_prefix(std::string_view prefix) {
    __android_log_print(ANDROID_LOG_ERROR, "gpsz_hook", "prefix diminta %s", std::string(prefix).c_str());
    return nullptr;
}

void log_art(const char *fmt, const char *a, long b, long c) {
    __android_log_print(ANDROID_LOG_ERROR, "gpsz_hook", fmt, a, b, c);
}

std::string art_path() {
    std::string found;
    dl_iterate_phdr(
            [](dl_phdr_info *info, size_t, void *data) {
                if (info->dlpi_name != nullptr && strstr(info->dlpi_name, "libart.so") != nullptr &&
                    strstr(info->dlpi_name, "libartbase") == nullptr) {
                    static_cast<std::string *>(data)->assign(info->dlpi_name);
                    return 1;
                }
                return 0;
            },
            &found);
    return found;
}

void inspect_art() {
    std::string path = art_path();
    log_art("libart path=%s len=%ld %ld", path.c_str(), static_cast<long>(path.size()), 0);
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        log_art("open libart gagal %s errno=%ld %ld", path.c_str(), errno, 0);
        return;
    }
    struct stat st;
    Elf64_Ehdr eh;
    if (fstat(fd, &st) != 0 || pread(fd, &eh, sizeof(eh), 0) != static_cast<ssize_t>(sizeof(eh))) {
        log_art("baca ehdr gagal %s errno=%ld %ld", path.c_str(), errno, 0);
        close(fd);
        return;
    }
    std::vector<Elf64_Shdr> sh(eh.e_shnum);
    if (pread(fd, sh.data(), sh.size() * sizeof(Elf64_Shdr), static_cast<off_t>(eh.e_shoff)) !=
        static_cast<ssize_t>(sh.size() * sizeof(Elf64_Shdr))) {
        log_art("baca shdr gagal %s shnum=%ld size=%ld", path.c_str(), eh.e_shnum, st.st_size);
        close(fd);
        return;
    }
    std::vector<char> names(sh[eh.e_shstrndx].sh_size);
    pread(fd, names.data(), names.size(), static_cast<off_t>(sh[eh.e_shstrndx].sh_offset));
    for (const auto &h : sh) {
        const char *n = names.data() + h.sh_name;
        if (strcmp(n, ".symtab") == 0 || strcmp(n, ".gnu_debugdata") == 0 ||
            strcmp(n, ".dynsym") == 0) {
            log_art("section %s size=%ld type=%ld", n, static_cast<long>(h.sh_size), h.sh_type);
        }
    }
    close(fd);
}

void probe_art() {
    const char *dyn = "JNI_GetCreatedJavaVMs";
    const char *tab = "_ZN3art9ArtMethod16UnregisterNativeEv";
    const char *fix = "_ZN3art11ClassLinker22FixupStaticTrampolinesEPNS_6ThreadENS_6ObjPtrINS_6mirror5ClassEEE";
    log_art("probe dynsym %s=%ld %ld", dyn, reinterpret_cast<long>(shadowhook_dlsym_dynsym(art, dyn)), 0);
    log_art("probe symtab %s=%ld %ld", tab, reinterpret_cast<long>(shadowhook_dlsym_symtab(art, tab)), 0);
    log_art("probe symtab %s=%ld %ld", fix, reinterpret_cast<long>(shadowhook_dlsym_symtab(art, fix)), 0);
}

bool start(JNIEnv *env) {
    if (shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false) != 0) {
        return false;
    }
    art = shadowhook_dlopen("libart.so");
    if (art == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "gpsz_hook", "dlopen libart gagal");
        return false;
    }
    inspect_art();
    probe_art();
    lsplant::InitInfo info;
    info.inline_hooker = inline_hook;
    info.inline_unhooker = inline_unhook;
    info.art_symbol_resolver = resolve;
    info.art_symbol_prefix_resolver = resolve_prefix;
    bool ok = lsplant::Init(env, info);
    __android_log_print(ANDROID_LOG_ERROR, "gpsz_hook", "lsplant::Init=%d sdk=%d", ok ? 1 : 0, android_get_device_api_level());
    return ok;
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
