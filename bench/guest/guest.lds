/* Flat image linked at guest address 0; see bench/guest/README.md for the memory map. */
ENTRY(_start)
SECTIONS
{
    . = 0x0;
    .text : { *(.text.start) *(.text .text.*) }
    .rodata : { *(.rodata .rodata.* .srodata .srodata.*) }
    .data : { *(.data .data.* .sdata .sdata.*) }
    . = ALIGN(4);
    __bss_start = .;
    .bss : { *(.bss .bss.* .sbss .sbss.* COMMON) }
    . = ALIGN(4);
    __bss_end = .;
    __stack_top = 0x00FF0000;
    ASSERT(__bss_end <= 0x00100000, "guest image overlaps the bench data regions")
}
