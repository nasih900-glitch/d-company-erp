#define _GNU_SOURCE
#include <dlfcn.h>
#include <elf.h>
#include <limits.h>
#include <link.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define RUNTIME "/usr/lib/libz.so.1.4"

static int soname_verified;

static int in_load(const struct dl_phdr_info *info, uintptr_t address, size_t length) {
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *p = &info->dlpi_phdr[i];
        uintptr_t start = info->dlpi_addr + p->p_vaddr;
        if (p->p_type == PT_LOAD && address >= start &&
            length <= p->p_memsz && address - start <= p->p_memsz - length)
            return 1;
    }
    return 0;
}

static int check_soname(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size; (void)data;
    char resolved[PATH_MAX];
    if (!info->dlpi_name || !realpath(info->dlpi_name, resolved) || strcmp(resolved, RUNTIME))
        return 0;
    const ElfW(Dyn) *dynamic = NULL;
    size_t count = 0, strings_size = 0, name = SIZE_MAX;
    uintptr_t strings = 0;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *p = &info->dlpi_phdr[i];
        if (p->p_type == PT_DYNAMIC) {
            dynamic = (const ElfW(Dyn) *)(info->dlpi_addr + p->p_vaddr);
            count = p->p_memsz / sizeof(*dynamic);
        }
    }
    if (!dynamic || !in_load(info, (uintptr_t)dynamic, count * sizeof(*dynamic))) return 1;
    for (size_t i = 0; i < count && dynamic[i].d_tag != DT_NULL; i++) {
        if (dynamic[i].d_tag == DT_STRTAB) strings = dynamic[i].d_un.d_ptr;
        if (dynamic[i].d_tag == DT_STRSZ) strings_size = dynamic[i].d_un.d_val;
        if (dynamic[i].d_tag == DT_SONAME) name = dynamic[i].d_un.d_val;
    }
    /* musl keeps relative dynamic pointers; glibc may relocate them. */
    if (!in_load(info, strings, strings_size)) strings += info->dlpi_addr;
    if (!strings_size || name >= strings_size || !in_load(info, strings, strings_size)) return 1;
    const char *soname = (const char *)strings + name;
    if (strings_size - name >= sizeof("libz.so.1") &&
        memcmp(soname, "libz.so.1", sizeof("libz.so.1")) == 0)
        soname_verified = 1;
    return 1;
}

int main(void) {
    void *library = dlopen("libz.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!library) return 10;
    const char *(*version)(void) = (const char *(*)(void))dlsym(library, "zlibVersion");
    Dl_info identity;
    char resolved[PATH_MAX];
    if (!version || strcmp(version(), "1.4") || !dladdr((void *)version, &identity) ||
        !realpath(identity.dli_fname, resolved) || strcmp(resolved, RUNTIME)) return 11;
    dl_iterate_phdr(check_soname, NULL);
    if (!soname_verified) return 12;
    /* Exercise the genuine mapped implementation, including binary zero bytes. */
    int (*compress_bytes)(unsigned char *, unsigned long *, const unsigned char *, unsigned long) =
        (int (*)(unsigned char *, unsigned long *, const unsigned char *, unsigned long))dlsym(library, "compress");
    int (*uncompress_bytes)(unsigned char *, unsigned long *, const unsigned char *, unsigned long) =
        (int (*)(unsigned char *, unsigned long *, const unsigned char *, unsigned long))dlsym(library, "uncompress");
    unsigned char original[256], compressed[1024], restored[256];
    unsigned long compressed_size = sizeof(compressed), restored_size = sizeof(restored);
    for (size_t i = 0; i < sizeof(original); i++) original[i] = (unsigned char)(i * 17U + i / 3U);
    if (!compress_bytes || !uncompress_bytes ||
        compress_bytes(compressed, &compressed_size, original, sizeof(original)) != 0 ||
        uncompress_bytes(restored, &restored_size, compressed, compressed_size) != 0 ||
        restored_size != sizeof(original) || memcmp(original, restored, sizeof(original))) return 15;
    FILE *maps = fopen("/proc/self/maps", "r");
    if (!maps) return 13;
    char line[8192];
    int mapped = 0;
    while (fgets(line, sizeof(line), maps))
        if (strstr(line, " " RUNTIME "\n")) mapped = 1;
    fclose(maps);
    if (!mapped) return 14;
    puts("runtime_version=1.4\nruntime_path=" RUNTIME "\nsoname=libz.so.1\nproc_map=" RUNTIME);
    dlclose(library);
    return 0;
}
