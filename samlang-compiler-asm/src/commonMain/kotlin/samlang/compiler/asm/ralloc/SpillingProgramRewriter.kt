package samlang.compiler.asm.ralloc

import samlang.ast.asm.AssemblyArg
import samlang.ast.asm.AssemblyArgs.CONST
import samlang.ast.asm.AssemblyArgs.MEM
import samlang.ast.asm.AssemblyArgs.Mem
import samlang.ast.asm.AssemblyArgs.Mem.MultipleOf
import samlang.ast.asm.AssemblyArgs.RBP
import samlang.ast.asm.AssemblyArgs.Reg
import samlang.ast.asm.AssemblyInstruction
import samlang.ast.asm.AssemblyInstruction.AlBinaryOpMemDest
import samlang.ast.asm.AssemblyInstruction.AlBinaryOpRegDest
import samlang.ast.asm.AssemblyInstruction.AlUnaryOp
import samlang.ast.asm.AssemblyInstruction.CallAddress
import samlang.ast.asm.AssemblyInstruction.CmpConstOrReg
import samlang.ast.asm.AssemblyInstruction.CmpMem
import samlang.ast.asm.AssemblyInstruction.Companion.BIN_OP
import samlang.ast.asm.AssemblyInstruction.Companion.CALL
import samlang.ast.asm.AssemblyInstruction.Companion.CMP
import samlang.ast.asm.AssemblyInstruction.Companion.IDIV
import samlang.ast.asm.AssemblyInstruction.Companion.IMUL
import samlang.ast.asm.AssemblyInstruction.Companion.JUMP
import samlang.ast.asm.AssemblyInstruction.Companion.LEA
import samlang.ast.asm.AssemblyInstruction.Companion.MOVE
import samlang.ast.asm.AssemblyInstruction.Companion.POP
import samlang.ast.asm.AssemblyInstruction.Companion.PUSH
import samlang.ast.asm.AssemblyInstruction.Companion.SHIFT
import samlang.ast.asm.AssemblyInstruction.Companion.UN_OP
import samlang.ast.asm.AssemblyInstruction.Cqo
import samlang.ast.asm.AssemblyInstruction.IDiv
import samlang.ast.asm.AssemblyInstruction.IMulOneArg
import samlang.ast.asm.AssemblyInstruction.IMulThreeArgs
import samlang.ast.asm.AssemblyInstruction.IMulTwoArgs
import samlang.ast.asm.AssemblyInstruction.JumpAddress
import samlang.ast.asm.AssemblyInstruction.JumpLabel
import samlang.ast.asm.AssemblyInstruction.LoadEffectiveAddress
import samlang.ast.asm.AssemblyInstruction.MoveFromLong
import samlang.ast.asm.AssemblyInstruction.MoveToMem
import samlang.ast.asm.AssemblyInstruction.MoveToReg
import samlang.ast.asm.AssemblyInstruction.Pop
import samlang.ast.asm.AssemblyInstruction.Push
import samlang.ast.asm.AssemblyInstruction.SetOnFlag
import samlang.ast.asm.AssemblyInstruction.Shift
import samlang.ast.asm.AssemblyInstructionVisitor
import samlang.ast.asm.ConstOrReg
import samlang.ast.asm.FunctionContext
import samlang.ast.asm.RegOrMem

/**
 * The program rewriter after spilling temporaries into stack.
 *
 * @param context the function context to aid program rewriting.
 * @param oldInstructions old instructions to be rewritten.
 * @param spilledVars the spilled vars to put onto the stack.
 * @param numberOfSpilledVarsSoFar number of spilled vars so far, before spilling the new ones.
 */
internal class SpillingProgramRewriter(
    private val context: FunctionContext,
    oldInstructions: List<AssemblyInstruction>,
    spilledVars: Set<String>,
    numberOfSpilledVarsSoFar: Int
) {
    /** The generated mappings for spilled vars. */
    private val spilledVarMappings: MutableMap<String, Mem> = mutableMapOf()
    /** The collector of new temps. */
    private val newTemps: MutableList<String> = mutableListOf()
    /** The collector for new instructions. */
    private val newInstructions: MutableList<AssemblyInstruction> = mutableListOf()

    init {
        var memId = 1 + numberOfSpilledVarsSoFar
        for (abstractRegId in spilledVars) {
            val mem = MEM(RBP, CONST(value = -memId * 8))
            memId++
            spilledVarMappings[abstractRegId] = mem
        }
        val visitor = ProgramRewriterVisitor()
        for (oldInstruction in oldInstructions) {
            oldInstruction.accept(visitor)
        }
    }

    /** @return the mappings of spilled vars. */
    fun getSpilledVarMappings(): Map<String, Mem> = spilledVarMappings

    /** @return generated new temps. */
    fun getNewTemps(): List<String> = newTemps

    /** @return generated new instructions. */
    fun getNewInstructions(): List<AssemblyInstruction> = newInstructions

    private fun getExpectedRegOrMem(reg: Reg): RegOrMem = spilledVarMappings[reg.id] ?: reg

    private fun nextReg(): Reg {
        val tempReg = context.nextReg()
        newTemps += tempReg.id
        return tempReg
    }

    private fun transformReg(reg: Reg): Reg =
        getExpectedRegOrMem(reg).matchRegOrMem(
            regF = { it },
            memF = { mem ->
                val tempReg = nextReg()
                newInstructions += MOVE(tempReg, mem)
                tempReg
            }
        )

    private fun transformMem(mem: Mem): Mem {
        var baseReg = mem.baseReg
        if (baseReg != null) {
            baseReg = transformReg(baseReg)
        }
        var multipleOf = mem.multipleOf
        if (multipleOf != null) {
            val multipleOfBaseReg = transformReg(multipleOf.baseReg)
            multipleOf = MultipleOf(multipleOfBaseReg, multipleOf.multipliedConstant)
        }
        return Mem(baseReg = baseReg, multipleOf = multipleOf, displacement = mem.displacement)
    }

    private fun transformRegOrMem(regOrMem: RegOrMem): RegOrMem =
        regOrMem.matchRegOrMem(
            regF = { reg -> getExpectedRegOrMem(reg) },
            memF = { mem -> transformMem(mem) }
        )

    private fun transformConstOrReg(constOrReg: ConstOrReg): ConstOrReg =
        constOrReg.matchConstOrReg(
            constF = { it },
            regF = { reg -> transformReg(reg) }
        )

    private fun transformArg(arg: AssemblyArg): AssemblyArg =
        arg.match(
            constF = { it },
            regF = { reg -> getExpectedRegOrMem(reg) },
            memF = { mem -> transformMem(mem) }
        )

    private fun transformRegDest(dest: Reg, instructionAdder: (Reg) -> Unit) {
        getExpectedRegOrMem(dest).matchRegOrMem(
            regF = { regDest: Reg -> instructionAdder(regDest) },
            memF = { memDest: Mem ->
                val tempReg = nextReg()
                instructionAdder(tempReg)
                newInstructions += MOVE(memDest, tempReg)
            }
        )
    }

    private inner class ProgramRewriterVisitor : AssemblyInstructionVisitor {
        override fun visit(node: MoveFromLong) {
            transformRegDest(dest = node.dest) { dest -> newInstructions += MOVE(dest, node.value) }
        }

        override fun visit(node: MoveToMem) {
            newInstructions += MOVE(transformMem(node.dest), transformConstOrReg(node.src))
        }

        override fun visit(node: MoveToReg) {
            val transformedSrc = transformArg(node.src)
            val expectedDest = getExpectedRegOrMem(node.dest)
            expectedDest.matchRegOrMem(
                regF = { regDest -> newInstructions += MOVE(regDest, transformedSrc) },
                memF = { memDest: Mem ->
                    transformedSrc.matchConstOrRegVsMem(
                        constOrRegF = { constOrRegSrc ->
                            newInstructions += MOVE(memDest, constOrRegSrc)
                        },
                        memF = { memSrc ->
                            val tempReg = nextReg()
                            newInstructions += MOVE(tempReg, memSrc)
                            newInstructions += MOVE(memDest, tempReg)
                        }
                    )
                }
            )
        }

        override fun visit(node: LoadEffectiveAddress) {
            transformRegDest(dest = node.dest) { dest ->
                newInstructions.add(LEA(dest, transformMem(node.mem)))
            }
        }

        override fun visit(node: CmpConstOrReg) {
            newInstructions += CMP(
                minuend = transformRegOrMem(regOrMem = node.minuend),
                subtrahend = transformConstOrReg(constOrReg = node.subtrahend)
            )
        }

        override fun visit(node: CmpMem) {
            newInstructions += CMP(
                minuend = transformReg(reg = node.minuend),
                subtrahend = transformMem(mem = node.subtrahend)
            )
        }

        override fun visit(node: SetOnFlag) {
            if (!RegisterAllocationConstants.PRE_COLORED_REGS.contains(node.reg.id)) {
                throw Error()
            }
            newInstructions += node
        }

        override fun visit(node: JumpLabel) {
            newInstructions += node
        }

        override fun visit(node: JumpAddress) {
            newInstructions += JUMP(node.type, transformArg(node.arg))
        }

        override fun visit(node: CallAddress) {
            newInstructions += CALL(transformArg(node.address))
        }

        override fun visit(node: AssemblyInstruction.Return) {
            newInstructions += node
        }

        override fun visit(node: AlBinaryOpMemDest) {
            newInstructions += BIN_OP(
                type = node.type,
                dest = transformMem(node.dest),
                src = transformConstOrReg(node.src)
            )
        }

        override fun visit(node: AlBinaryOpRegDest) {
            val transformedSrc = transformArg(node.src)
            val expectedDest = getExpectedRegOrMem(node.dest)
            val type = node.type
            expectedDest.matchRegOrMem(
                regF = { regDest -> newInstructions += BIN_OP(type, regDest, transformedSrc) },
                memF = { memDest ->
                    transformedSrc.matchConstOrRegVsMem(
                        constOrRegF = { constOrRegSrc ->
                            newInstructions += BIN_OP(type, memDest, constOrRegSrc)
                        },
                        memF = { memSrc ->
                            val tempReg = nextReg()
                            newInstructions += MOVE(tempReg, memDest)
                            newInstructions += BIN_OP(type, tempReg, memSrc)
                            newInstructions += MOVE(memDest, tempReg)
                        }
                    )
                }
            )
        }

        override fun visit(node: IMulOneArg) {
            newInstructions += IMUL(transformRegOrMem(node.arg))
        }

        override fun visit(node: IMulTwoArgs) {
            val transformedSrc = transformRegOrMem(node.src)
            val expectedDest = getExpectedRegOrMem(node.dest)
            expectedDest.matchRegOrMem<Any?>(
                { regDest: Reg? ->
                    newInstructions.add(IMUL(regDest!!, transformedSrc))
                    null
                }
            ) { memDest: Mem? ->
                val tempReg = nextReg()
                newInstructions.add(MOVE(tempReg, memDest!!))
                newInstructions.add(IMUL(tempReg, transformedSrc))
                newInstructions.add(MOVE(memDest, tempReg))
                null
            }
        }

        override fun visit(node: IMulThreeArgs) {
            transformRegDest(dest = node.dest) { dest ->
                newInstructions += IMUL(dest, transformRegOrMem(node.src), node.immediate)
            }
        }

        override fun visit(node: Cqo) {
            newInstructions += node
        }

        override fun visit(node: IDiv) {
            newInstructions += IDIV(transformRegOrMem(node.divisor))
        }

        override fun visit(node: AlUnaryOp) {
            val type = node.type
            node.dest.matchRegOrMem(
                regF = { regDest ->
                    newInstructions += UN_OP(type, getExpectedRegOrMem(regDest))
                },
                memF = { memDest ->
                    newInstructions += UN_OP(type, transformMem(memDest))
                }
            )
        }

        override fun visit(node: Shift) {
            val type = node.type
            val count = node.count
            node.dest.matchRegOrMem(
                regF = { regDest ->
                    newInstructions += SHIFT(type, getExpectedRegOrMem(regDest), count)
                },
                memF = { memDest ->
                    newInstructions += SHIFT(type, transformMem(memDest), count)
                }
            )
        }

        override fun visit(node: Push) {
            newInstructions += PUSH(transformArg(node.arg))
        }

        override fun visit(node: Pop) {
            node.arg.matchRegOrMem(
                regF = { regDest ->
                    transformRegDest(dest = regDest) { dest -> newInstructions += POP(dest) }
                },
                memF = { memDest -> newInstructions += POP(transformMem(memDest)) }
            )
        }

        override fun visit(node: AssemblyInstruction.Label) {
            newInstructions += node
        }

        override fun visit(node: AssemblyInstruction.Comment) {
            newInstructions += node
        }
    }
}
