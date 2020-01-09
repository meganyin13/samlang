package samlang.ast.mir

import samlang.ast.mir.MidIrExpression.Mem
import samlang.ast.mir.MidIrExpression.Temporary

sealed class MidIrStatement {
    abstract fun <C, T> accept(visitor: MidIrStatementVisitor<C, T>, context: C): T

    @Canonical
    data class MoveTemp(val tempId: String, val source: MidIrExpression) : MidIrStatement() {
        override fun toString(): String = "$tempId = $source;"

        override fun <C, T> accept(visitor: MidIrStatementVisitor<C, T>, context: C): T =
            visitor.visit(node = this, context = context)
    }

    @Canonical
    data class MoveMem(val memLocation: MidIrExpression, val source: MidIrExpression) : MidIrStatement() {
        override fun toString(): String = "mem[$memLocation] = $source;"

        override fun <C, T> accept(visitor: MidIrStatementVisitor<C, T>, context: C): T =
            visitor.visit(node = this, context = context)
    }

    data class IgnoreExpression(val expression: MidIrExpression) : MidIrStatement() {
        override fun toString(): String = "EXPR($expression);"

        override fun <C, T> accept(visitor: MidIrStatementVisitor<C, T>, context: C): T =
            visitor.visit(node = this, context = context)
    }

    @Canonical
    data class MultipleReturnCall(
        val functionExpr: MidIrExpression,
        val arguments: List<MidIrExpression>,
        val returnCollector: Temporary?
    ) : MidIrStatement() {
        override fun toString(): String {
            val argumentString = arguments.joinToString(separator = ", ")
            val returnTempString = returnCollector?.id ?: "_"
            return "$returnTempString = $functionExpr($argumentString);"
        }

        override fun <C, T> accept(visitor: MidIrStatementVisitor<C, T>, context: C): T =
            visitor.visit(node = this, context = context)
    }

    data class Sequence(val statements: List<MidIrStatement>) : MidIrStatement() {
        override fun toString(): String = "SEQ($statements);"

        override fun <C, T> accept(visitor: MidIrStatementVisitor<C, T>, context: C): T =
            visitor.visit(node = this, context = context)
    }

    @Canonical
    data class Jump(val label: String) : MidIrStatement() {
        override fun toString(): String = "jump $label;"

        override fun <C, T> accept(visitor: MidIrStatementVisitor<C, T>, context: C): T =
            visitor.visit(node = this, context = context)
    }

    data class ConditionalJump(
        val condition: MidIrExpression,
        val label1: String,
        val label2: String
    ) : MidIrStatement() {
        override fun toString(): String = "CJUMP($condition, $label1, $label2);"

        override fun <C, T> accept(visitor: MidIrStatementVisitor<C, T>, context: C): T =
            visitor.visit(node = this, context = context)
    }

    @Canonical
    data class ConditionalJumpFallThrough(
        val condition: MidIrExpression,
        val label1: String
    ) : MidIrStatement() {
        override fun toString(): String = "if ($condition) goto $label1;"

        override fun <C, T> accept(visitor: MidIrStatementVisitor<C, T>, context: C): T =
            visitor.visit(node = this, context = context)
    }

    @Canonical
    data class Label(val name: String) : MidIrStatement() {
        override fun toString(): String = "$name:"

        override fun <C, T> accept(visitor: MidIrStatementVisitor<C, T>, context: C): T =
            visitor.visit(node = this, context = context)
    }

    @Canonical
    data class Return(val returnedExpressions: List<MidIrExpression> = emptyList()) : MidIrStatement() {
        override fun toString(): String {
            if (returnedExpressions.isEmpty()) {
                return "return;"
            }
            return returnedExpressions
                .joinToString(separator = ", ", prefix = "return ", postfix = ";")
        }

        override fun <C, T> accept(visitor: MidIrStatementVisitor<C, T>, context: C): T =
            visitor.visit(node = this, context = context)
    }

    @Suppress(names = ["FunctionName"])
    companion object {
        @JvmStatic
        fun MOVE(destination: Temporary, source: MidIrExpression): MoveTemp =
            MoveTemp(tempId = destination.id, source = source)

        @JvmStatic
        fun MOVE(destination: Mem, source: MidIrExpression): MoveMem =
            MoveMem(memLocation = destination.expression, source = source)

        @JvmStatic
        fun EXPR(expression: MidIrExpression): IgnoreExpression =
            IgnoreExpression(expression = expression)

        @JvmStatic
        fun MULTIPLE_RETURN_CALL(
            functionName: String,
            arguments: List<MidIrExpression>,
            returnCollector: Temporary?
        ): MultipleReturnCall = MultipleReturnCall(
            functionExpr = MidIrExpression.Name(name = functionName),
            arguments = arguments,
            returnCollector = returnCollector
        )

        @JvmStatic
        fun MULTIPLE_RETURN_CALL(
            expression: MidIrExpression,
            arguments: List<MidIrExpression>,
            returnCollector: Temporary?
        ): MultipleReturnCall = MultipleReturnCall(
            functionExpr = expression,
            arguments = arguments,
            returnCollector = returnCollector
        )

        @JvmStatic
        fun SEQ(statements: List<MidIrStatement>): Sequence = Sequence(statements = statements)

        @JvmStatic
        fun SEQ(vararg statements: MidIrStatement): Sequence =
            Sequence(statements = listOf(*statements))

        @JvmStatic
        fun CJUMP(condition: MidIrExpression, label1: String, label2: String): ConditionalJump =
            ConditionalJump(condition = condition, label1 = label1, label2 = label2)

        @JvmStatic
        fun CJUMP_FALLTHROUGH(condition: MidIrExpression, label1: String): ConditionalJumpFallThrough =
            ConditionalJumpFallThrough(condition = condition, label1 = label1)
    }
}