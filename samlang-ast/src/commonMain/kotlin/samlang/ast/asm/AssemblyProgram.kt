package samlang.ast.asm

import samlang.ast.common.GlobalVariable

data class AssemblyProgram(
    val globalVariables: List<GlobalVariable>,
    val publicFunctions: List<String>,
    val instructions: List<AssemblyInstruction>
)
