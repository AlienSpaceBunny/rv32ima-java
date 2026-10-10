# Builds the bench guest images (flat binaries linked at 0) into ../src/main/resources/...
# Requires clang/lld with the riscv32 target. CoreMark sources come from fetch-coremark.sh.
#   make COREMARK_DIR=/path/to/coremark      (default: ../out/ext/coremark)
COREMARK_DIR ?= ../out/ext/coremark
OUT := ../src/main/resources/com/alienspacebunny/bench/guest
BUILD := ../out/guest-build

CC := clang
OBJCOPY := llvm-objcopy
# C builds match V-32's AP profile; the no-C builds isolate compressed-instruction costs.
MARCH_C := rv32imafc_zicsr_zba_zbb
MARCH_NOC := rv32imaf_zicsr_zba_zbb
CFLAGS := --target=riscv32-none-elf -mabi=ilp32f -O2 -ffreestanding \
	-fno-stack-protector -fno-PIC -mcmodel=medany -fno-math-errno -ffp-contract=off \
	-ffunction-sections -fdata-sections
LDFLAGS := -fuse-ld=lld -nostdlib -static -T guest.lds -Wl,--gc-sections -Wl,--no-relax

CM_SRC := $(addprefix $(COREMARK_DIR)/,core_list_join.c core_main.c core_matrix.c core_state.c core_util.c) \
	coremark-port/core_portme.c
CM_FLAGS := -I$(COREMARK_DIR) -Icoremark-port -DITERATIONS=10 \
	'-DCOMPILER_FLAGS="-O2 -ffp-contract=off"'
COMMON := crt0.S support.c

IMAGES := coremark-c coremark-noc fpkernels-c fpkernels-noc
all: $(addprefix $(OUT)/,$(addsuffix .bin,$(IMAGES)))

$(BUILD)/coremark-%.elf: $(CM_SRC) $(COMMON) guest.lds coremark-port/core_portme.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -march=$(if $(filter c,$*),$(MARCH_C),$(MARCH_NOC)) $(CM_FLAGS) $(LDFLAGS) -o $@ $(COMMON) $(CM_SRC)

$(BUILD)/fpkernels-%.elf: fpkernels.c $(COMMON) guest.lds
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -march=$(if $(filter c,$*),$(MARCH_C),$(MARCH_NOC)) $(LDFLAGS) -o $@ $(COMMON) fpkernels.c

$(OUT)/%.bin: $(BUILD)/%.elf
	@mkdir -p $(OUT)
	$(OBJCOPY) -O binary $< $@
	$(CC) --version | head -n 1 > $(BUILD)/toolchain.txt

.SECONDARY:
clean:
	rm -f $(BUILD)/*.elf $(OUT)/*.bin
