/*
 * Single-precision kernels for the rv32emu bench: matrix multiply, an n-body step with
 * sqrtf/division, and Horner evaluation with explicit fmaf. Build with -ffp-contract=off so
 * every operation is exactly the one written; Workloads.FpKernels reproduces each kernel in
 * Java float arithmetic (Math.fma for fmaf, correctly rounded sqrt) and checks the checksums.
 */
#include <stdint.h>

#define RESULTS ((volatile uint32_t *)0x00FF8000u)

static uint32_t lcg_state;

static float lcgf(void) {
    lcg_state = lcg_state * 1664525u + 1013904223u;
    return (float)(lcg_state >> 8) * (1.0f / 16777216.0f) * 2.0f - 1.0f;
}

static uint32_t bits(float f) {
    uint32_t u;
    __builtin_memcpy(&u, &f, sizeof u);
    return u;
}

#define N 48
static float A[N][N], B[N][N], C[N][N];

static uint32_t sgemm(void) {
    for (int i = 0; i < N; i++) {
        for (int k = 0; k < N; k++) {
            A[i][k] = lcgf();
            B[i][k] = lcgf();
        }
    }
    for (int i = 0; i < N; i++) {
        for (int j = 0; j < N; j++) {
            float s = 0.0f;
            for (int k = 0; k < N; k++) {
                s = s + A[i][k] * B[k][j];
            }
            C[i][j] = s;
        }
    }
    uint32_t h = 0;
    for (int i = 0; i < N; i++) {
        for (int j = 0; j < N; j++) {
            h = h * 31u + bits(C[i][j]);
        }
    }
    return h;
}

#define NB 8
#define STEPS 600
#define DT 0.01f
static float px[NB], py[NB], pz[NB], vx[NB], vy[NB], vz[NB], m[NB];

static uint32_t nbody(void) {
    for (int i = 0; i < NB; i++) {
        px[i] = lcgf() * 10.0f;
        py[i] = lcgf() * 10.0f;
        pz[i] = lcgf() * 10.0f;
        vx[i] = lcgf() * 0.1f;
        vy[i] = lcgf() * 0.1f;
        vz[i] = lcgf() * 0.1f;
        m[i] = lcgf() + 1.5f;
    }
    for (int s = 0; s < STEPS; s++) {
        for (int i = 0; i < NB; i++) {
            for (int j = i + 1; j < NB; j++) {
                float dx = px[i] - px[j];
                float dy = py[i] - py[j];
                float dz = pz[i] - pz[j];
                float d2 = dx * dx + dy * dy + dz * dz + 0.01f;
                float d = __builtin_sqrtf(d2);
                float mag = DT / (d2 * d);
                vx[i] = vx[i] - dx * m[j] * mag;
                vy[i] = vy[i] - dy * m[j] * mag;
                vz[i] = vz[i] - dz * m[j] * mag;
                vx[j] = vx[j] + dx * m[i] * mag;
                vy[j] = vy[j] + dy * m[i] * mag;
                vz[j] = vz[j] + dz * m[i] * mag;
            }
        }
        for (int i = 0; i < NB; i++) {
            px[i] = px[i] + DT * vx[i];
            py[i] = py[i] + DT * vy[i];
            pz[i] = pz[i] + DT * vz[i];
        }
    }
    uint32_t h = 0;
    for (int i = 0; i < NB; i++) {
        h = h * 31u + bits(px[i]);
        h = h * 31u + bits(py[i]);
        h = h * 31u + bits(pz[i]);
        h = h * 31u + bits(vx[i]);
        h = h * 31u + bits(vy[i]);
        h = h * 31u + bits(vz[i]);
    }
    return h;
}

#define NP 1024
#define REPS 6
#define DEG 8
static float xs[NP], acc[NP], coef[DEG + 1];

static uint32_t horner(void) {
    for (int k = 0; k <= DEG; k++) {
        coef[k] = lcgf();
    }
    for (int i = 0; i < NP; i++) {
        xs[i] = lcgf();
        acc[i] = 0.0f;
    }
    for (int r = 0; r < REPS; r++) {
        for (int i = 0; i < NP; i++) {
            float x = xs[i];
            float y = coef[DEG];
            for (int k = DEG - 1; k >= 0; k--) {
                y = __builtin_fmaf(y, x, coef[k]);
            }
            acc[i] = __builtin_fmaf(acc[i], 0.5f, y);
        }
    }
    uint32_t h = 0;
    for (int i = 0; i < NP; i++) {
        h = h * 31u + bits(acc[i]);
    }
    return h;
}

int main(void) {
    lcg_state = 12345u;
    RESULTS[3] = sgemm();
    RESULTS[4] = nbody();
    RESULTS[5] = horner();
    return 0;
}
