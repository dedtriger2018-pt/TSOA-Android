#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

typedef int (*mkdir_fn)(const char *, mode_t);
typedef int (*mkdirat_fn)(int, const char *, mode_t);

/* Write all available bytes, handling partial writes and EINTR. */
static void probe_write(const char *data, size_t length)
{
    while (length > 0) {
        ssize_t result = write(STDERR_FILENO, data, length);

        if (result < 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (result == 0) break;

        data += result;
        length -= (size_t)result;
    }
}

static void trace_path(const char *function, const char *path)
{
    if (!path) return;

    if (!strstr(path, "com.winlator") &&
        !strstr(path, ".wine-")) return;

    const char prefix[] = "[TSOA_PROBE ";
    const char middle[] = "] ";
    const char newline[] = "\n";

    probe_write(prefix, sizeof(prefix) - 1);
    probe_write(function, strlen(function));
    probe_write(middle, sizeof(middle) - 1);
    probe_write(path, strnlen(path, 1024));
    probe_write(newline, 1);
}

__attribute__((constructor))
static void probe_init(void)
{
    const char msg[] = "[TSOA_PROBE loaded]\n";
    probe_write(msg, sizeof(msg) - 1);
}

int mkdir(const char *path, mode_t mode)
{
    static mkdir_fn real_mkdir;

    if (!real_mkdir)
        real_mkdir = (mkdir_fn)dlsym(RTLD_NEXT, "mkdir");

    if (!real_mkdir) {
        errno = ENOSYS;
        return -1;
    }

    trace_path("mkdir", path);
    return real_mkdir(path, mode);
}

int mkdirat(int dirfd, const char *path, mode_t mode)
{
    static mkdirat_fn real_mkdirat;

    if (!real_mkdirat)
        real_mkdirat = (mkdirat_fn)dlsym(RTLD_NEXT, "mkdirat");

    if (!real_mkdirat) {
        errno = ENOSYS;
        return -1;
    }

    trace_path("mkdirat", path);
    return real_mkdirat(dirfd, path, mode);
}
