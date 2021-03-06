package samlang.ast.hir

/** A collection of statements for common IR. */
sealed class HighIrStatement {

    abstract fun <T> accept(visitor: HighIrStatementVisitor<T>): T

    data class FunctionApplication(
        val functionExpression: HighIrExpression,
        val arguments: List<HighIrExpression>,
        val resultCollector: String
    ) : HighIrStatement() {
        override fun <T> accept(visitor: HighIrStatementVisitor<T>): T = visitor.visit(statement = this)
    }

    data class IfElse(
        val booleanExpression: HighIrExpression,
        val s1: List<HighIrStatement>,
        val s2: List<HighIrStatement>
    ) : HighIrStatement() {
        override fun <T> accept(visitor: HighIrStatementVisitor<T>): T = visitor.visit(statement = this)
    }

    data class LetDefinition(val name: String, val assignedExpression: HighIrExpression) : HighIrStatement() {
        override fun <T> accept(visitor: HighIrStatementVisitor<T>): T = visitor.visit(statement = this)
    }

    data class StructInitialization(
        val structVariableName: String,
        val expressionList: List<HighIrExpression>
    ) : HighIrStatement() {
        override fun <T> accept(visitor: HighIrStatementVisitor<T>): T = visitor.visit(statement = this)
    }

    data class Return(val expression: HighIrExpression?) : HighIrStatement() {
        override fun <T> accept(visitor: HighIrStatementVisitor<T>): T = visitor.visit(statement = this)
    }
}
