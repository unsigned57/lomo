#include <stdatomic.h>

static inline bool boltffi_atomic_u8_cas(uint8_t* state, uint8_t expected, uint8_t desired) {
    return atomic_compare_exchange_strong_explicit((_Atomic uint8_t*)state, &expected, desired, memory_order_acq_rel, memory_order_acquire);
}

static inline uint64_t boltffi_atomic_u64_exchange(uint64_t* slot, uint64_t value) {
    return atomic_exchange_explicit((_Atomic uint64_t*)slot, value, memory_order_acq_rel);
}

static inline bool boltffi_atomic_u64_cas(uint64_t* slot, uint64_t expected, uint64_t desired) {
    return atomic_compare_exchange_strong_explicit((_Atomic uint64_t*)slot, &expected, desired, memory_order_acq_rel, memory_order_acquire);
}

static inline uint64_t boltffi_atomic_u64_load(uint64_t* slot) {
    return atomic_load_explicit((_Atomic uint64_t*)slot, memory_order_acquire);
}

