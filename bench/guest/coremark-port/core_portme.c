/*
Copyright 2018 Embedded Microprocessor Benchmark Consortium (EEMBC)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Original Author: Shay Gal-on
Modified for the rv32emu bench: see the comment below.
*/
/*
 * CoreMark port layer for the rv32emu bench (derived from upstream barebones/core_portme.c,
 * Apache-2.0). Timing reads the cycle CSR. portable_fini exports the run's results to the
 * RESULTS block (see crt0.S) because core_main keeps them in locals.
 */
#include "coremark.h"
#include "core_portme.h"

#define RESULTS ((volatile ee_u32 *)0x00FF8000u)
#define EE_TICKS_PER_SEC 1000000u

#if VALIDATION_RUN
volatile ee_s32 seed1_volatile = 0x3415;
volatile ee_s32 seed2_volatile = 0x3415;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PERFORMANCE_RUN
volatile ee_s32 seed1_volatile = 0x0;
volatile ee_s32 seed2_volatile = 0x0;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PROFILE_RUN
volatile ee_s32 seed1_volatile = 0x8;
volatile ee_s32 seed2_volatile = 0x8;
volatile ee_s32 seed3_volatile = 0x8;
#endif
volatile ee_s32 seed4_volatile = ITERATIONS;
volatile ee_s32 seed5_volatile = 0;

ee_u32 default_num_contexts = 1;

static CORETIMETYPE start_time_val, stop_time_val;

static ee_u32 read_cycle(void) {
    ee_u32 value;
    __asm__ volatile("csrr %0, 0xc00" : "=r"(value));
    return value;
}

void start_time(void) {
    start_time_val = read_cycle();
}

void stop_time(void) {
    stop_time_val = read_cycle();
}

CORE_TICKS get_time(void) {
    return (CORE_TICKS)(stop_time_val - start_time_val);
}

secs_ret time_in_secs(CORE_TICKS ticks) {
    return (secs_ret)ticks / EE_TICKS_PER_SEC;
}

int ee_printf(const char *fmt, ...) {
    (void)fmt;
    return 0;
}

void portable_init(core_portable *p, int *argc, char *argv[]) {
    (void)argc;
    (void)argv;
    p->portable_id = 1;
}

void portable_fini(core_portable *p) {
    /* p is &results[0].port; recover the enclosing record. */
    core_results *r = (core_results *)((char *)p - offsetof(core_results, port));
    RESULTS[3] = r->crc;
    RESULTS[4] = r->crclist;
    RESULTS[5] = r->crcmatrix;
    RESULTS[6] = r->crcstate;
    RESULTS[7] = (ee_u32)(ee_s32)r->err;
    RESULTS[8] = r->iterations;
    RESULTS[9] = r->size;
    p->portable_id = 0;
}
