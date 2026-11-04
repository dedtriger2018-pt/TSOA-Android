#define _GNU_SOURCE

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

/*
 * TSOA Redirector V2
 *
 * Configuration:
 *   TSOA_ROOTFS    Absolute host path to the Android app's extracted RootFS.
 *                  Defaults to the package path used by this TSOA fork.
 *   TSOA_WINE_DIR  RootFS-relative Wine directory (e.g. /opt/wine or
 *                  /opt/installed-wine/wine-10.10-custom).
 *                  Defaults to /opt/wine.
 *
 * These are ordinary inherited environment variables.  BOX64_LD_PRELOAD and
 * BOX64_FORCE_LD_PRELOAD are configured separately in the container.
 */

static const char legacy_root[] =
    "/data/data/com.winlator/files/rootfs";
static const char legacy_build_wine[] =
    "/root/wine-build/wine-install-wow64";
static const char default_rootfs[] =
    "/data/user/0/com.naninovel.mobile/files/rootfs";
static const char default_wine_dir[] = "/opt/wine";

static char configured_rootfs[PATH_MAX];
static char configured_wine_dir[PATH_MAX];
static char configured_wine_abs[PATH_MAX];
static char configured_nls[PATH_MAX];
static char configured_bin[PATH_MAX];
static int configuration_valid;

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
        digits[count++] = (char)('0' + number % 10U);
        number /= 10U;
    } while (number > 0U && count < sizeof(digits));

    while (count > 0) write_all(&digits[--count], 1);
}

static void trace_redirect(const char *function,
                           const char *original,
                           const char *translated,
                           int result,
                           int saved_errno)
{
    static const char prefix[] = "[TSOA_REDIRECT_V2 ";
    static const char middle[] = "] ";
    static const char arrow[] = " -> ";
    static const char result_label[] = " rc=";
    static const char errno_label[] = " errno=";
    static const char newline[] = "\n";

    write_all(prefix, sizeof(prefix) - 1U);
    write_all(function, strlen(function));
    write_all(middle, sizeof(middle) - 1U);
    write_all(original, strnlen(original, PATH_MAX));
    write_all(arrow, sizeof(arrow) - 1U);
    write_all(translated, strnlen(translated, PATH_MAX));
    write_all(result_label, sizeof(result_label) - 1U);
    write_number(result);
    if (result < 0) {
        write_all(errno_label, sizeof(errno_label) - 1U);
        write_number(saved_errno);
    }
    write_all(newline, sizeof(newline) - 1U);
}

static int copy_string(char *destination, size_t capacity,
                       const char *source)
{
    size_t length;
    if (!destination || capacity == 0U || !source) return 0;
    length = strnlen(source, capacity);
    if (length == capacity) return 0;
    memcpy(destination, source, length + 1U);
    return 1;
}

static void trim_trailing_slashes(char *path)
{
    size_t length;
    if (!path) return;
    length = strlen(path);
    while (length > 1U && path[length - 1U] == '/') {
        path[--length] = '\0';
    }
}

static int has_path_prefix(const char *path, const char *prefix)
{
    size_t length;
    if (!path || !prefix) return 0;
    length = strlen(prefix);
    return strncmp(path, prefix, length) == 0 &&
           (path[length] == '\0' || path[length] == '/');
}

static int join_rootfs_path(char *output, size_t capacity,
                            const char *rootfs, const char *relative)
{
    int written;
    if (!output || !rootfs || !relative || relative[0] != '/') return 0;
    written = snprintf(output, capacity, "%s%s", rootfs, relative);
    return written >= 0 && (size_t)written < capacity;
}

static int has_dot_path_segment(const char *path)
{
    const char *cursor = path;
    while (cursor && *cursor) {
        const char *start;
        size_t length;
        while (*cursor == '/') cursor++;
        start = cursor;
        while (*cursor && *cursor != '/') cursor++;
        length = (size_t)(cursor - start);
        if ((length == 1U && start[0] == '.') ||
            (length == 2U && start[0] == '.' && start[1] == '.'))
            return 1;
    }
    return 0;
}

static int safe_root_relative_wine_dir(const char *path)
{
    return path && path[0] == '/' && !has_dot_path_segment(path);
}

static int safe_absolute_rootfs_path(const char *path)
{
    return path && path[0] == '/' && !has_dot_path_segment(path);
}

static void initialize_configuration(void)
{
    const char *root_env = getenv("TSOA_ROOTFS");
    const char *wine_env = getenv("TSOA_WINE_DIR");

    if (!root_env || !*root_env) root_env = default_rootfs;
    if (!wine_env || !*wine_env) wine_env = default_wine_dir;

    if (!safe_absolute_rootfs_path(root_env) ||
        !copy_string(configured_rootfs, sizeof(configured_rootfs), root_env)) {
        configuration_valid = 0;
        return;
    }
    trim_trailing_slashes(configured_rootfs);

    if (!safe_root_relative_wine_dir(wine_env) ||
        !copy_string(configured_wine_dir, sizeof(configured_wine_dir),
                     wine_env)) {
        configuration_valid = 0;
        return;
    }
    trim_trailing_slashes(configured_wine_dir);

    if (!join_rootfs_path(configured_wine_abs,
                          sizeof(configured_wine_abs),
                          configured_rootfs, configured_wine_dir)) {
        configuration_valid = 0;
        return;
    }

    if (snprintf(configured_nls, sizeof(configured_nls),
                 "%s/share/wine/nls", configured_wine_abs) >=
        (int)sizeof(configured_nls)) {
        configuration_valid = 0;
        return;
    }
    if (snprintf(configured_bin, sizeof(configured_bin),
                 "%s/bin", configured_wine_abs) >=
        (int)sizeof(configured_bin)) {
        configuration_valid = 0;
        return;
    }

    configuration_valid = 1;
}

static const char *replace_prefix(const char *path,
                                  const char *prefix,
                                  const char *replacement,
                                  char *buffer,
                                  size_t capacity)
{
    size_t prefix_length;
    size_t replacement_length;
    size_t suffix_length;

    if (!has_path_prefix(path, prefix)) return path;
    prefix_length = strlen(prefix);
    replacement_length = strlen(replacement);
    suffix_length = strlen(path + prefix_length);

    if (replacement_length + suffix_length + 1U > capacity) return path;
    memcpy(buffer, replacement, replacement_length);
    memcpy(buffer + replacement_length, path + prefix_length,
           suffix_length + 1U);
    return buffer;
}

/* Specific build/NLS paths must be tested before the generic old RootFS. */
static const char *redirect_path(const char *path,
                                 char *buffer,
                                 size_t capacity)
{
    char secondary[PATH_MAX];
    const char *result;
    char old_nls_local[PATH_MAX];
    char old_nls_usr[PATH_MAX];

    if (!path || !configuration_valid || capacity == 0U) return path;

    if (snprintf(old_nls_local, sizeof(old_nls_local),
                 "%s/usr/local/share/wine/nls", legacy_root) >=
            (int)sizeof(old_nls_local) ||
        snprintf(old_nls_usr, sizeof(old_nls_usr),
                 "%s/usr/share/wine/nls", legacy_root) >=
            (int)sizeof(old_nls_usr)) {
        return path;
    }

    result = replace_prefix(path, old_nls_local, configured_nls,
                            buffer, capacity);
    if (result != path) return result;

    result = replace_prefix(path, old_nls_usr, configured_nls,
                            buffer, capacity);
    if (result != path) return result;

    if (snprintf(secondary, sizeof(secondary),
                 "%s/share/wine/nls", legacy_build_wine) <
        (int)sizeof(secondary)) {
        result = replace_prefix(path, secondary, configured_nls,
                                buffer, capacity);
        if (result != path) return result;
    }

    if (snprintf(secondary, sizeof(secondary), "%s/bin", legacy_build_wine) <
        (int)sizeof(secondary)) {
        result = replace_prefix(path, secondary, configured_bin,
                                buffer, capacity);
        if (result != path) return result;
    }

    return replace_prefix(path, legacy_root, configured_rootfs,
                          buffer, capacity);
}

static int needs_open_mode(int flags)
{
    if ((flags & O_CREAT) != 0) return 1;
#ifdef O_TMPFILE
    if ((flags & O_TMPFILE) == O_TMPFILE) return 1;
#endif
    return 0;
}

__attribute__((constructor))
static void redirector_v2_init(void)
{
    initialize_configuration();
    if (configuration_valid) {
        static const char loaded[] = "[TSOA_REDIRECT_V2 loaded]\n";
        static const char root_label[] = "[TSOA_REDIRECT_V2 rootfs=";
        static const char wine_label[] = " wine=";
        static const char close[] = "]\n";
        write_all(loaded, sizeof(loaded) - 1U);
        write_all(root_label, sizeof(root_label) - 1U);
        write_all(configured_rootfs, strlen(configured_rootfs));
        write_all(wine_label, sizeof(wine_label) - 1U);
        write_all(configured_wine_dir, strlen(configured_wine_dir));
        write_all(close, sizeof(close) - 1U);
    } else {
        static const char failed[] =
            "[TSOA_REDIRECT_V2 configuration invalid; redirects disabled]\n";
        write_all(failed, sizeof(failed) - 1U);
    }
}

/* mkdir */
int mkdir(const char *path, mode_t mode)
{
    static int (*real_function)(const char *, mode_t);
    char buffer[PATH_MAX];
    const char *translated;
    int result, saved_errno;

    if (!real_function)
        real_function = (int (*)(const char *, mode_t))dlsym(RTLD_NEXT, "mkdir");
    if (!real_function) { errno = ENOSYS; return -1; }

    translated = redirect_path(path, buffer, sizeof(buffer));
    result = real_function(translated, mode);
    saved_errno = errno;
    if (translated != path)
        trace_redirect("mkdir", path, translated, result, saved_errno);
    errno = saved_errno;
    return result;
}

/* mkdirat (included for completeness even though this wineserver did not import it). */
int mkdirat(int dirfd, const char *path, mode_t mode)
{
    static int (*real_function)(int, const char *, mode_t);
    char buffer[PATH_MAX];
    const char *translated;
    int result, saved_errno;

    if (!real_function)
        real_function = (int (*)(int, const char *, mode_t))dlsym(RTLD_NEXT, "mkdirat");
    if (!real_function) { errno = ENOSYS; return -1; }

    translated = redirect_path(path, buffer, sizeof(buffer));
    result = real_function(dirfd, translated, mode);
    saved_errno = errno;
    if (translated != path)
        trace_redirect("mkdirat", path, translated, result, saved_errno);
    errno = saved_errno;
    return result;
}

/* open */
int open(const char *path, int flags, ...)
{
    static int (*real_function)(const char *, int, ...);
    char buffer[PATH_MAX];
    const char *translated;
    mode_t mode = 0;
    int result, saved_errno;

    if (!real_function)
        real_function = (int (*)(const char *, int, ...))dlsym(RTLD_NEXT, "open");
    if (!real_function) { errno = ENOSYS; return -1; }

    if (needs_open_mode(flags)) {
        va_list arguments;
        va_start(arguments, flags);
        mode = (mode_t)va_arg(arguments, int);
        va_end(arguments);
    }

    translated = redirect_path(path, buffer, sizeof(buffer));
    result = needs_open_mode(flags) ?
        real_function(translated, flags, mode) :
        real_function(translated, flags);
    saved_errno = errno;
    if (translated != path)
        trace_redirect("open", path, translated, result, saved_errno);
    errno = saved_errno;
    return result;
}

/* openat */
int openat(int dirfd, const char *path, int flags, ...)
{
    static int (*real_function)(int, const char *, int, ...);
    char buffer[PATH_MAX];
    const char *translated;
    mode_t mode = 0;
    int result, saved_errno;

    if (!real_function)
        real_function = (int (*)(int, const char *, int, ...))dlsym(RTLD_NEXT, "openat");
    if (!real_function) { errno = ENOSYS; return -1; }

    if (needs_open_mode(flags)) {
        va_list arguments;
        va_start(arguments, flags);
        mode = (mode_t)va_arg(arguments, int);
        va_end(arguments);
    }

    translated = redirect_path(path, buffer, sizeof(buffer));
    result = needs_open_mode(flags) ?
        real_function(dirfd, translated, flags, mode) :
        real_function(dirfd, translated, flags);
    saved_errno = errno;
    if (translated != path)
        trace_redirect("openat", path, translated, result, saved_errno);
    errno = saved_errno;
    return result;
}

/* fopen */
FILE *fopen(const char *path, const char *mode)
{
    static FILE *(*real_function)(const char *, const char *);
    char buffer[PATH_MAX];
    const char *translated;
    FILE *result;
    int saved_errno;

    if (!real_function)
        real_function = (FILE *(*)(const char *, const char *))dlsym(RTLD_NEXT, "fopen");
    if (!real_function) { errno = ENOSYS; return NULL; }

    translated = redirect_path(path, buffer, sizeof(buffer));
    result = real_function(translated, mode);
    saved_errno = errno;
    if (translated != path)
        trace_redirect("fopen", path, translated, result ? 0 : -1, saved_errno);
    errno = saved_errno;
    return result;
}

/* stat */
int stat(const char *path, struct stat *status)
{
    static int (*real_function)(const char *, struct stat *);
    char buffer[PATH_MAX];
    const char *translated;
    int result, saved_errno;

    if (!real_function)
        real_function = (int (*)(const char *, struct stat *))dlsym(RTLD_NEXT, "stat");
    if (!real_function) { errno = ENOSYS; return -1; }

    translated = redirect_path(path, buffer, sizeof(buffer));
    result = real_function(translated, status);
    saved_errno = errno;
    if (translated != path)
        trace_redirect("stat", path, translated, result, saved_errno);
    errno = saved_errno;
    return result;
}

/* lstat */
int lstat(const char *path, struct stat *status)
{
    static int (*real_function)(const char *, struct stat *);
    char buffer[PATH_MAX];
    const char *translated;
    int result, saved_errno;

    if (!real_function)
        real_function = (int (*)(const char *, struct stat *))dlsym(RTLD_NEXT, "lstat");
    if (!real_function) { errno = ENOSYS; return -1; }

    translated = redirect_path(path, buffer, sizeof(buffer));
    result = real_function(translated, status);
    saved_errno = errno;
    if (translated != path)
        trace_redirect("lstat", path, translated, result, saved_errno);
    errno = saved_errno;
    return result;
}

/* fstatat */
int fstatat(int dirfd, const char *path, struct stat *status, int flags)
{
    static int (*real_function)(int, const char *, struct stat *, int);
    char buffer[PATH_MAX];
    const char *translated;
    int result, saved_errno;

    if (!real_function)
        real_function = (int (*)(int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "fstatat");
    if (!real_function) { errno = ENOSYS; return -1; }

    translated = redirect_path(path, buffer, sizeof(buffer));
    result = real_function(dirfd, translated, status, flags);
    saved_errno = errno;
    if (translated != path)
        trace_redirect("fstatat", path, translated, result, saved_errno);
    errno = saved_errno;
    return result;
}

/* chmod */
int chmod(const char *path, mode_t mode)
{
    static int (*real_function)(const char *, mode_t);
    char buffer[PATH_MAX];
    const char *translated;
    int result, saved_errno;

    if (!real_function)
        real_function = (int (*)(const char *, mode_t))dlsym(RTLD_NEXT, "chmod");
    if (!real_function) { errno = ENOSYS; return -1; }

    translated = redirect_path(path, buffer, sizeof(buffer));
    result = real_function(translated, mode);
    saved_errno = errno;
    if (translated != path)
        trace_redirect("chmod", path, translated, result, saved_errno);
    errno = saved_errno;
    return result;
}

/* unlink */
int unlink(const char *path)
{
    static int (*real_function)(const char *);
    char buffer[PATH_MAX];
    const char *translated;
    int result, saved_errno;

    if (!real_function)
        real_function = (int (*)(const char *))dlsym(RTLD_NEXT, "unlink");
    if (!real_function) { errno = ENOSYS; return -1; }

    translated = redirect_path(path, buffer, sizeof(buffer));
    result = real_function(translated);
    saved_errno = errno;
    if (translated != path)
        trace_redirect("unlink", path, translated, result, saved_errno);
    errno = saved_errno;
    return result;
}

/* unlinkat */
int unlinkat(int dirfd, const char *path, int flags)
{
    static int (*real_function)(int, const char *, int);
    char buffer[PATH_MAX];
    const char *translated;
    int result, saved_errno;

    if (!real_function)
        real_function = (int (*)(int, const char *, int))dlsym(RTLD_NEXT, "unlinkat");
    if (!real_function) { errno = ENOSYS; return -1; }

    translated = redirect_path(path, buffer, sizeof(buffer));
    result = real_function(dirfd, translated, flags);
    saved_errno = errno;
    if (translated != path)
        trace_redirect("unlinkat", path, translated, result, saved_errno);
    errno = saved_errno;
    return result;
}

/* rename */
int rename(const char *old_path, const char *new_path)
{
    static int (*real_function)(const char *, const char *);
    char old_buffer[PATH_MAX], new_buffer[PATH_MAX];
    const char *translated_old, *translated_new;
    int result, saved_errno;

    if (!real_function)
        real_function = (int (*)(const char *, const char *))dlsym(RTLD_NEXT, "rename");
    if (!real_function) { errno = ENOSYS; return -1; }

    translated_old = redirect_path(old_path, old_buffer, sizeof(old_buffer));
    translated_new = redirect_path(new_path, new_buffer, sizeof(new_buffer));
    result = real_function(translated_old, translated_new);
    saved_errno = errno;
    if (translated_old != old_path)
        trace_redirect("rename(old)", old_path, translated_old, result, saved_errno);
    if (translated_new != new_path)
        trace_redirect("rename(new)", new_path, translated_new, result, saved_errno);
    errno = saved_errno;
    return result;
}

/* rmdir */
int rmdir(const char *path)
{
    static int (*real_function)(const char *);
    char buffer[PATH_MAX];
    const char *translated;
    int result, saved_errno;

    if (!real_function)
        real_function = (int (*)(const char *))dlsym(RTLD_NEXT, "rmdir");
    if (!real_function) { errno = ENOSYS; return -1; }

    translated = redirect_path(path, buffer, sizeof(buffer));
    result = real_function(translated);
    saved_errno = errno;
    if (translated != path)
        trace_redirect("rmdir", path, translated, result, saved_errno);
    errno = saved_errno;
    return result;
}

/* chdir */
int chdir(const char *path)
{
    static int (*real_function)(const char *);
    char buffer[PATH_MAX];
    const char *translated;
    int result, saved_errno;

    if (!real_function)
        real_function = (int (*)(const char *))dlsym(RTLD_NEXT, "chdir");
    if (!real_function) { errno = ENOSYS; return -1; }

    translated = redirect_path(path, buffer, sizeof(buffer));
    result = real_function(translated);
    saved_errno = errno;
    if (translated != path)
        trace_redirect("chdir", path, translated, result, saved_errno);
    errno = saved_errno;
    return result;
}

/* readlink: redirect the link pathname, never the link contents. */
ssize_t readlink(const char *path, char *buffer_out, size_t size)
{
    static ssize_t (*real_function)(const char *, char *, size_t);
    char buffer[PATH_MAX];
    const char *translated;
    ssize_t result;
    int saved_errno;

    if (!real_function)
        real_function = (ssize_t (*)(const char *, char *, size_t))dlsym(RTLD_NEXT, "readlink");
    if (!real_function) { errno = ENOSYS; return -1; }

    translated = redirect_path(path, buffer, sizeof(buffer));
    result = real_function(translated, buffer_out, size);
    saved_errno = errno;
    if (translated != path)
        trace_redirect("readlink", path, translated, (int)result, saved_errno);
    errno = saved_errno;
    return result;
}

/* realpath */
char *realpath(const char *path, char *resolved_path)
{
    static char *(*real_function)(const char *, char *);
    char buffer[PATH_MAX];
    const char *translated;
    char *result;
    int saved_errno;

    if (!real_function)
        real_function = (char *(*)(const char *, char *))dlsym(RTLD_NEXT, "realpath");
    if (!real_function) { errno = ENOSYS; return NULL; }

    translated = redirect_path(path, buffer, sizeof(buffer));
    result = real_function(translated, resolved_path);
    saved_errno = errno;
    if (translated != path)
        trace_redirect("realpath", path, translated, result ? 0 : -1, saved_errno);
    errno = saved_errno;
    return result;
}

/* AF_UNIX bind/connect: only absolute pathname sockets are rewritten.
 * Relative names such as "socket" are intentionally left untouched; after
 * the wineserver chdir() redirect they resolve in the corrected directory.
 */
static int call_unix_socket_path(
    const char *function_name,
    int (*real_function)(int, const struct sockaddr *, socklen_t),
    int fd, const struct sockaddr *address, socklen_t address_length)
{
    struct sockaddr_un copied;
    char path_buffer[PATH_MAX];
    const struct sockaddr_un *source;
    const char *translated;
    size_t offset = offsetof(struct sockaddr_un, sun_path);
    size_t available;
    size_t path_length;
    socklen_t translated_length;
    int result, saved_errno;

    if (!address || address->sa_family != AF_UNIX ||
        (size_t)address_length < offset + 1U) {
        return real_function(fd, address, address_length);
    }

    source = (const struct sockaddr_un *)address;
    available = (size_t)address_length - offset;
    if (available > sizeof(source->sun_path)) available = sizeof(source->sun_path);
    if (available == 0U || source->sun_path[0] == '\0') {
        return real_function(fd, address, address_length);
    }

    path_length = strnlen(source->sun_path, available);
    if (path_length == available) {
        return real_function(fd, address, address_length);
    }

    translated = redirect_path(source->sun_path, path_buffer,
                               sizeof(path_buffer));
    if (translated == source->sun_path) {
        return real_function(fd, address, address_length);
    }

    if (strlen(translated) + 1U > sizeof(copied.sun_path)) {
        errno = ENAMETOOLONG;
        return -1;
    }

    memset(&copied, 0, sizeof(copied));
    copied.sun_family = AF_UNIX;
    memcpy(copied.sun_path, translated, strlen(translated) + 1U);
    translated_length = (socklen_t)(offset + strlen(translated) + 1U);

    result = real_function(fd, (const struct sockaddr *)&copied,
                           translated_length);
    saved_errno = errno;
    trace_redirect(function_name, source->sun_path, translated,
                   result, saved_errno);
    errno = saved_errno;
    return result;
}

int bind(int fd, const struct sockaddr *address, socklen_t address_length)
{
    static int (*real_function)(int, const struct sockaddr *, socklen_t);
    if (!real_function)
        real_function = (int (*)(int, const struct sockaddr *, socklen_t))dlsym(RTLD_NEXT, "bind");
    if (!real_function) { errno = ENOSYS; return -1; }
    return call_unix_socket_path("bind", real_function, fd, address,
                                 address_length);
}

int connect(int fd, const struct sockaddr *address, socklen_t address_length)
{
    static int (*real_function)(int, const struct sockaddr *, socklen_t);
    if (!real_function)
        real_function = (int (*)(int, const struct sockaddr *, socklen_t))dlsym(RTLD_NEXT, "connect");
    if (!real_function) { errno = ENOSYS; return -1; }
    return call_unix_socket_path("connect", real_function, fd, address,
                                 address_length);
}
