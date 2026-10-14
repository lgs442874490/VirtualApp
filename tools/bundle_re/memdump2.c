#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/uio.h>

int main(int argc, char** argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s pid\n", argv[0]); return 1; }
    int pid = atoi(argv[1]);
    char mpath[64], rpath[64];
    snprintf(mpath, sizeof mpath, "/proc/%d/mem", pid);
    snprintf(rpath, sizeof rpath, "/proc/%d/maps", pid);
    int mem = open(mpath, O_RDONLY);
    FILE* maps = fopen(rpath, "r");
    FILE* out = fopen("heap_native.bin", "wb");
    if (mem < 0 || !maps || !out) { perror("open"); return 1; }
    char line[512];
    static char buf[1 << 22];
    unsigned long long off = 0;
    int regions = 0;
    while (fgets(line, sizeof line, maps)) {
        unsigned long long s, e;
        char perms[8];
        if (sscanf(line, "%llx-%llx %7s", &s, &e, perms) != 3) continue;
        if (perms[0] != 'r' || perms[1] != 'w') continue;
        unsigned long long len = e - s;
        if (len < 262144) continue;
        if (len > 2000000000ULL) len = 2000000000ULL;
        unsigned long long p = 0;
        while (p < len) {
            size_t chunk = len - p > (4u << 20) ? (4u << 20) : (size_t)(len - p);
            struct iovec local = { buf, chunk };
            struct iovec remote = { (void*)(s + p), chunk };
            ssize_t r = process_vm_readv(pid, &local, 1, &remote, 1, 0);
            if ((size_t)r != chunk) {
                size_t done = r > 0 ? (size_t)r : 0;
                fwrite(buf, 1, done, out);
                while (done < chunk) {
                    size_t pg = chunk - done > 4096 ? 4096 : chunk - done;
                    struct iovec l1 = { buf, pg };
                    struct iovec r1 = { (void*)(s + p + done), pg };
                    ssize_t r2 = process_vm_readv(pid, &l1, 1, &r1, 1, 0);
                    if (r2 <= 0) r2 = 0;
                    if ((size_t)r2 < pg) memset(buf + r2, 0, pg - (size_t)r2);
                    fwrite(buf, 1, pg, out);
                    done += pg;
                }
            } else {
                fwrite(buf, 1, chunk, out);
            }
            p += chunk;
        }
        printf("region %d: %#llx len=%llu -> fileoff %llu\n", regions++, s, len, off);
        fflush(stdout);
        off += len;
    }
    printf("TOTAL %llu bytes, %d regions\n", off, regions);
    fclose(out); fclose(maps); close(mem);
    return 0;
}
