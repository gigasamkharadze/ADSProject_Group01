#!/usr/bin/env python3
"""
RISC-V Binary Generator for BTB Loop Benchmark (Assignment 06, Task 6.4/6.5)

Generates a small countdown loop whose closing branch (BNE) is executed
repeatedly at the SAME PC, which is exactly the pattern a Branch Target
Buffer is meant to exploit: after the first (cold-miss) iteration, the BTB
should predict "taken" for every remaining loop iteration and only
mispredict once, on the final iteration where the loop actually exits.

The Assignment 05 core has two latent quirks in its Decode stage (both
preserved here, not fixed, since they are out of scope for this assignment
-- it only asks for changes to IF/EX):

  1. For B-type instructions, Decode always overwrites operandB with the
     branch immediate, so a branch's rs2 operand is only correct if it gets
     restored by the EX stage's inline forwarding logic, which requires rs2
     to have been written by one of the immediately preceding 1-2
     instructions. Hence x2 (the BNE's rs2, hardwired to the constant 0) is
     re-written with "addi x2, x0, 0" immediately before every single BNE,
     to force that forwarding path on every iteration.

  2. Decode sets io.rs2 := instr[24:20] unconditionally for every
     instruction, including I-type ones, where those bits are actually the
     low 5 bits of the immediate rather than a real register index. If that
     value happens to equal the index of a register written 1-2
     instructions earlier, EX's forwarding logic spuriously "forwards" that
     register's value into operandB, clobbering the correct immediate.
     All I-type immediates below are therefore chosen as multiples of 32
     (imm[4:0] == 0), so the spurious rs2 always resolves to x0, which the
     forwarding logic explicitly excludes (rs2 =/= 0 guard) -- avoiding the
     collision entirely without touching Decode stage logic.
"""

OPCODE_I      = 0x13  # ADDI
OPCODE_R      = 0x33  # ADD, SUB, etc.
OPCODE_BRANCH = 0x63
FUNCT3_ADD    = 0x0
FUNCT7_ADD    = 0x00
FUNCT7_SUB    = 0x20

def addi(rd, rs1, imm):
    imm = imm & 0xFFF
    return (imm << 20 | rs1 << 15 | FUNCT3_ADD << 12 | rd << 7 | OPCODE_I) & 0xFFFFFFFF

def rtype(funct7, rd, rs1, rs2, funct3=0x0):
    return (funct7 << 25 | rs2 << 20 | rs1 << 15 | funct3 << 12 | rd << 7 | OPCODE_R) & 0xFFFFFFFF

def sub(rd, rs1, rs2): return rtype(FUNCT7_SUB, rd, rs1, rs2)

def branch(funct3, rs1, rs2, imm):
    imm = imm & 0x1FFE
    inst = (((imm >> 12) & 1) << 31 |
            ((imm >> 5)  & 0x3F) << 25 |
            (rs2 & 0x1F) << 20 |
            (rs1 & 0x1F) << 15 |
            (funct3 & 0x7) << 12 |
            ((imm >> 1) & 0xF) << 8 |
            ((imm >> 11) & 1) << 7 |
            OPCODE_BRANCH)
    return inst & 0xFFFFFFFF

def bne(rs1, rs2, imm): return branch(0b001, rs1, rs2, imm)

def write(filename, instructions):
    with open(filename, 'w') as f:
        for inst in instructions:
            f.write(f"{inst:08x}\n")

# ──────────────────────────────────────────────────────────────
# BTB loop benchmark: 5-iteration countdown loop
#
#  0: addi x1, x0, 160   # x1 = 160 (loop counter)
#  4: addi x4, x0, 32    # x4 = 32  (decrement constant)
#  8: LOOP:
#  8: sub  x1, x1, x4    # x1 -= 32
# 12: addi x2, x0, 0     # x2 = 0 (refreshed every iteration so BNE's rs2
#                        #         gets restored via EX forwarding, cf. docstring)
# 16: bne  x1, x2, -8    # branch back to LOOP (PC 8) while x1 != 0
# 20: addi x5, x0, 96    # marker instruction: only reached once the loop exits
#
# The BNE at PC 16 is executed 5 times: taken on iterations
# x1=160->128->96->64->32 (4 times) and not-taken on the 5th, when x1 reaches
# 0 and the loop exits. 160/32/224 are all multiples of 32 to sidestep the
# spurious-rs2-forwarding quirk described above. The marker value (224) is
# deliberately chosen to never collide with any intermediate value that
# passes through the WB stage during the loop (160/128/96/64/32/0), since
# check_res transiently mirrors whatever result is in WB each cycle and a
# collision would make the loop-exit check fire early regardless of BTB
# behaviour, masking any real difference between the two configurations.
# ──────────────────────────────────────────────────────────────
write('src/test/programs/BinaryFile_btb_loop', [
    addi(1, 0, 160),   # PC  0
    addi(4, 0, 32),    # PC  4
    sub(1, 1, 4),      # PC  8  (LOOP)
    addi(2, 0, 0),     # PC 12
    bne(1, 2, -8),     # PC 16
    addi(5, 0, 224),   # PC 20  (marker: loop exited)
])

print("BTB loop benchmark binary generated successfully!")
