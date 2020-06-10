package samlang.compiler.asm.tiling

import samlang.ast.asm.AssemblyInstruction
import samlang.ast.asm.AssemblyInstruction.Companion.COMMENT
import samlang.ast.asm.AssemblyInstruction.Companion.MOVE
import samlang.ast.mir.MidIrExpression.Mem
import samlang.ast.mir.MidIrStatement.MoveMem

internal object TileGenericMoveMem : IrStatementTile<MoveMem> {
    override fun getTilingResult(node: MoveMem, dpTiling: DpTiling): StatementTilingResult {
        val irMem = Mem(expression = node.memLocation, immutable = false)
        val (memLocInstructions, memLoc) = MemTilingHelper.tileMem(irMem, dpTiling)
        val instructions = mutableListOf<AssemblyInstruction>()
        // first add mem loc instructions
        instructions += COMMENT(comment = "GenericMoveMem: $node")
        instructions += memLocInstructions
        val srcTilingResult = dpTiling.tileConstOrReg(node.source)
        instructions += srcTilingResult.instructions
        instructions += MOVE(memLoc, srcTilingResult.constOrReg)
        return StatementTilingResult(instructions)
    }
}
