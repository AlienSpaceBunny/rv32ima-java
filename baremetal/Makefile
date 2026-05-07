PROJECT:=baremetal
TARGETS:=$(PROJECT).elf $(PROJECT).bin $(PROJECT).debug.txt

all : $(TARGETS)

#PREFIX:=riscv64-unknown-elf-
#CFLAGS:=-I/usr/include

CC:=clang
OBJCOPY:=llvm-objcopy
OBJDUMP:=llvm-objdump

CFLAGS:=--target=riscv32-none-elf -march=rv32ima_zicsr -mabi=ilp32
CFLAGS+=-fno-stack-protector -ffreestanding -fuse-ld=lld
CFLAGS+=-fdata-sections -ffunction-sections -fno-PIC -fno-plt -mcmodel=medany
CFLAGS+=-g -Os -static
LDFLAGS:= -T flatfile.lds -nostdlib -Wl,--gc-sections -Wl,--no-relax

$(PROJECT).elf : $(PROJECT).c $(PROJECT).S
	$(CC) -o $@ $^ $(CFLAGS) $(LDFLAGS)

$(PROJECT).debug.txt : $(PROJECT).elf
	$(OBJDUMP) -t $^ > $@
	$(OBJDUMP) -S $^ >> $@

$(PROJECT).bin : $(PROJECT).elf
	$(OBJCOPY) $^ -O binary $@

test : $(PROJECT).bin
	../mini-rv32ima/mini-rv32ima -f $<

clean :
	rm -rf $(TARGETS)

