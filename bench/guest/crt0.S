/*
 * Minimal start-up for bench guest programs: set the stack, zero .bss, call main(), then
 * publish a completion record at RESULTS and stop in WFI. The harness checks the record and
 * that the hart stopped exactly after this WFI.
 */
    .equ RESULTS, 0x00FF8000
    .equ MAGIC, 0xC0DEC0DE

    .section .text.start, "ax"
    .globl _start
_start:
    la sp, __stack_top
    la t0, __bss_start
    la t1, __bss_end
1:  bgeu t0, t1, 2f
    sw zero, 0(t0)
    addi t0, t0, 4
    j 1b
2:  call main
    li t0, RESULTS
    sw a0, 4(t0)            /* main's return value */
    la t1, done
    sw t1, 8(t0)            /* address of the terminating WFI */
    li t2, MAGIC
    sw t2, 0(t0)
done:
    wfi
    j done
