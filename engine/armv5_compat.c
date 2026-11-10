/* the armv5te slice of android 2.3 phones with an armv6 core. the compiler-rt of the ndk is built for
   armv7: its barrier is missing for armv5te, and the 64 bit atomics it does have use ldrexd, which an
   ARM11 before the K revision does not run. the three the engine calls are written here, so the
   linker never takes them from compiler-rt. the linux kernel maps a compare and swap routine and a
   barrier routine at fixed addresses of every arm process, which is all they need.

   they are defined through assembler names because the compiler refuses a definition of a builtin */
#if defined(__arm__) && __ARM_ARCH < 6

typedef unsigned long long u64;

#define KUSER_CMPXCHG ((int (*)(int, int, volatile int *))0xffff0fc0)
#define KUSER_BARRIER ((void (*)(void))0xffff0fa0)

__asm__(
    ".text\n"
    ".arm\n"
    ".global __sync_synchronize\n"
    ".type __sync_synchronize, %function\n"
    "__sync_synchronize:\n"
    "    ldr ip, 1f\n"
    "    bx ip\n"
    "1:  .word 0xffff0fa0\n"
);

static volatile int g_lock;

static void take(void) {
    while (KUSER_CMPXCHG(0, 1, &g_lock) != 0) {
        /* another thread holds it for the few instructions of one add */
    }
}

static void give(void) {
    KUSER_BARRIER();
    g_lock = 0;
}

#define RMW(name, type, op)                                      \
    type senko_##name(volatile type *p, type v) {                \
        take();                                                  \
        type old = *p;                                           \
        *p = old op v;                                           \
        give();                                                  \
        return old;                                              \
    }

RMW(sync_add_8, u64, +)
RMW(sync_sub_8, u64, -)
RMW(sync_or_8, u64, |)
RMW(sync_and_8, u64, &)
RMW(sync_add_4, unsigned, +)
RMW(sync_sub_4, unsigned, -)
RMW(sync_or_4, unsigned, |)
RMW(sync_and_4, unsigned, &)

#define ALIAS(builtin, impl) \
    __asm__(".global " #builtin "\n.type " #builtin ", %function\n.set " #builtin ", " #impl "\n")

ALIAS(__sync_fetch_and_add_8, senko_sync_add_8);
ALIAS(__sync_fetch_and_sub_8, senko_sync_sub_8);
ALIAS(__sync_fetch_and_or_8, senko_sync_or_8);
ALIAS(__sync_fetch_and_and_8, senko_sync_and_8);
ALIAS(__sync_fetch_and_add_4, senko_sync_add_4);
ALIAS(__sync_fetch_and_sub_4, senko_sync_sub_4);
ALIAS(__sync_fetch_and_or_4, senko_sync_or_4);
ALIAS(__sync_fetch_and_and_4, senko_sync_and_4);

/* the c11 forms openssl's reference counters call. same lock, and the memory order argument is
   ignored because the lock is a full barrier */
#define ARMW(name, type, op)                                              \
    type senko_atomic_##name(volatile type *p, type v, int order) {      \
        (void)order;                                                     \
        return senko_##name(p, v);                                       \
    }

ARMW(sync_add_8, u64, +)
ARMW(sync_sub_8, u64, -)
ARMW(sync_or_8, u64, |)
ARMW(sync_and_8, u64, &)
ARMW(sync_add_4, unsigned, +)
ARMW(sync_sub_4, unsigned, -)

#define ALOAD(name, type)                                    \
    type senko_atomic_load_##name(const volatile type *p, int order) { \
        (void)order;                                         \
        take();                                              \
        type v = *p;                                         \
        give();                                              \
        return v;                                            \
    }                                                        \
    void senko_atomic_store_##name(volatile type *p, type v, int order) { \
        (void)order;                                         \
        take();                                              \
        *p = v;                                              \
        give();                                              \
    }

ALOAD(8, u64)
ALOAD(4, unsigned)

void senko_atomic_store_any(unsigned size, volatile void *p, void *val, int order) {
    volatile unsigned char *dst = p;
    const unsigned char *src = val;
    (void)order;
    take();
    for (unsigned i = 0; i < size; ++i) dst[i] = src[i];
    give();
}

int senko_atomic_is_lock_free(unsigned size, volatile void *p) {
    (void)p;
    /* 8 bytes are not lock free here: the lock is how they are made atomic */
    return size <= 4;
}

ALIAS(__atomic_fetch_add_8, senko_atomic_sync_add_8);
ALIAS(__atomic_fetch_sub_8, senko_atomic_sync_sub_8);
ALIAS(__atomic_fetch_or_8, senko_atomic_sync_or_8);
ALIAS(__atomic_fetch_and_8, senko_atomic_sync_and_8);
ALIAS(__atomic_fetch_add_4, senko_atomic_sync_add_4);
ALIAS(__atomic_fetch_sub_4, senko_atomic_sync_sub_4);
ALIAS(__atomic_load_8, senko_atomic_load_8);
ALIAS(__atomic_store_8, senko_atomic_store_8);
ALIAS(__atomic_load_4, senko_atomic_load_4);
ALIAS(__atomic_store_4, senko_atomic_store_4);
ALIAS(__atomic_store, senko_atomic_store_any);
ALIAS(__atomic_is_lock_free, senko_atomic_is_lock_free);

#endif
