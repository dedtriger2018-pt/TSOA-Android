#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <stddef.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

typedef int (*mkdir_fn)(const char *, mode_t);
typedef int (*mkdirat_fn)(int, const char *, mode_t);

static const char old_root[] =
    "/data/data/com.winlator/files/rootfs";

static const char new_root[] =
    "/data/user/0/com.naninovel.mobile/files/rootfs";

static void write_all(const char *data, size_t length)
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

static void write_number(int value)
{
    char digits[16];
    size_t count = 0;
    unsigned int number;

    if (value < 0) {
        write_all("-", 1);
        number = (unsigned int)(-(value + 1)) + 1U;
    } else {
        number = (unsigned int)value;
    }

    do {
        digits[count++] = (char)('0' + number % 10);
        number /= 10;
    } while (number > 0);

    while (count > 0)
        write_all(&digits[--count], 1);
}

static const char *redirect_path(
    const char *path,
    char *buffer,
    size_t capacity)
{
    if (!path) return path;

    const size_t old_length = sizeof(old_root) - 1;
    const size_t new_length = sizeof(new_root) - 1;

    if (strncmp(path, old_root, old_length) != 0)
        return path;

    if (path[old_length] != '\0' &&
        path[old_length] != '/')
        return path;

    size_t suffix_length =
        strnlen(path + old_length, capacity);

    if (suffix_length == capacity)
        return path;

    if (new_length + suffix_length + 1 > capacity)
        return path;

    memcpy(buffer, new_root, new_length);
    memcpy(buffer + new_length, path + old_length,
           suffix_length);

    buffer[new_length + suffix_length] = '\0';

    return buffer;
}

static void trace_redirect(
    const char *function,
    const char *original,
    const char *translated,
    int result,
    int saved_errno)
{
    const char prefix[] = "[TSOA_REDIRECT ";
    const char middle[] = "] ";
    const char arrow[] = " -> ";
    const char result_label[] = " result=";
    const char errno_label[] = " errno=";
    const char newline[] = "\n";

    write_all(prefix, sizeof(prefix) - 1);
    write_all(function, strlen(function));
    write_all(middle, sizeof(middle) - 1);
    write_all(original, strnlen(original, PATH_MAX));
    write_all(arrow, sizeof(arrow) - 1);
    write_all(translated, strnlen(translated, PATH_MAX));
    write_all(result_label, sizeof(result_label) - 1);
    write_number(result);

    if (result < 0) {
        write_all(errno_label, sizeof(errno_label) - 1);
        write_number(saved_errno);
    }

    write_all(newline, 1);
}

__attribute__((constructor))
static void redirector_init(void)
{
    const char message[] = "[TSOA_REDIRECT loaded]\n";
    write_all(message, sizeof(message) - 1);
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

    char buffer[PATH_MAX];

    const char *translated =
        redirect_path(path, buffer, sizeof(buffer));

    int result = real_mkdir(translated, mode);
    int saved_errno = errno;

    if (translated != path)
        trace_redirect("mkdir", path, translated,
                       result, saved_errno);

    errno = saved_errno;
    return result;
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

    char buffer[PATH_MAX];

    const char *translated =
        redirect_path(path, buffer, sizeof(buffer));

    int result = real_mkdirat(dirfd, translated, mode);
    int saved_errno = errno;

    if (translated != path)
        trace_redirect("mkdirat", path, translated,
                       result, saved_errno);

    errno = saved_errno;
    return result;
}
