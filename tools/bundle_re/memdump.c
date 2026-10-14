#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/uio.h>
int main(int argc, char** argv) {
    if (argc < 3) { fprintf(stderr, "usage: %s pid replist out\n", argv[0]); return 1; }
    int pid = atoi(argv[1]);
    char mpath[64]; snprintf(mpath, sizeof mpath, "/proc/%d/mem", pid);
    int mem = open(mpath, O_RDONLY);
    if (mem < 0) { perror("open mem"); return 1; }
    FILE* lf = fopen(argv[2], "r");
    FILE* out = fopen(argv[3], "wb");
    if (!lf || !out) { perror("files"); return 1; }
    unsigned long long off = 0;
    char S[64], LEN[64];
    static char buf[1 << 22];
    while (fscanf(lf, "%63s %63s", S, LEN) == 2) {
        unsigned long long s = strtoull(S, 0, 10), len = strtoull(LEN, 0, 10);
        if (s > 0x700000000000ULL) continue;  /* 跳内核/异常地址 */
        unsigned long long p = 0;
        while (p < len) {
            size_t chunk = len - p > sizeof buf ? sizeof buf : (size_t)(len - p);
            struct iovec local = { buf, chunk };
            struct iovec remote = { (void*)(s + p), chunk };
            ssize_t r = process_vm_readv(pid, &local, 1, &remote, 1, 0);
            if ((size_t)r != chunk) {
                /* 页级回退 */
                size_t done = r > 0 ? (size_t)r : 0;
                fwrite(buf, 1, done, out);
                while (done < chunk) {
                    struct iovec l1 = { buf, 4096 };
                    struct iovec r1 = { (void*)(s + p + done), 4096 };
                    ssize_t r2 = process_vm_readv(pid, &l1, 1, &r1, 1, 0);
                    if (r2 <= 0) r2 = 0;
                    if ((size_t)r2 < 4096) memset(buf + r2, 0, 4096 - (size_t)r2);
                    fwrite(buf, 1, 4096, out);
                    done += 4096;
                }
            } else {
                fwrite(buf, 1, chunk, out);
            }
            p += chunk;
        }
        printf("%llu %llu %llu\n", off, s, len);
        fflush(stdout);
        off += len;
    }
    fclose(out); fclose(lf); close(mem);
    return 0;
}
