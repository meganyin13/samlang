package samlang.ast.hir

import samlang.ast.common.BinaryOperator
import samlang.ast.common.BuiltInFunctionName
import samlang.ast.common.Type
import samlang.ast.common.UnaryOperator

/** A collection of expressions for common IR. */
sealed class HighIrExpression {

    abstract val type: Type

    abstract fun <T> accept(visitor: HighIrExpressionVisitor<T>): T

    object UnitExpression : HighIrExpression() {
        override val type: Type get() = Type.unit
        override fun toString(): String = "unit"
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class Literal(
        override val type: Type,
        val literal: samlang.ast.common.Literal
    ) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class Variable(override val type: Type, val name: String) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class This(override val type: Type) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class ClassMember(
        override val type: Type,
        val typeArguments: List<Type>,
        val className: String,
        val memberName: String
    ) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class TupleConstructor(
        override val type: Type,
        val expressionList: List<HighIrExpression>
    ) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class ObjectConstructor(
        override val type: Type.IdentifierType,
        val fieldDeclaration: List<Pair<String, HighIrExpression>>
    ) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class VariantConstructor(
        override val type: Type.IdentifierType,
        val tag: String,
        val tagOrder: Int,
        val data: HighIrExpression
    ) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class FieldAccess(
        override val type: Type,
        val expression: HighIrExpression,
        val fieldName: String,
        val fieldOrder: Int
    ) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class MethodAccess(
        override val type: Type,
        val expression: HighIrExpression,
        val className: String,
        val methodName: String
    ) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class Unary(
        override val type: Type,
        val operator: UnaryOperator,
        val expression: HighIrExpression
    ) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class BuiltInFunctionApplication(
        override val type: Type,
        val functionName: BuiltInFunctionName,
        val argument: HighIrExpression
    ) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class FunctionApplication(
        override val type: Type,
        val className: String,
        val functionName: String,
        val typeArguments: List<Type>,
        val arguments: List<HighIrExpression>
    ) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class MethodApplication(
        override val type: Type,
        val objectExpression: HighIrExpression,
        val className: String,
        val methodName: String,
        val arguments: List<HighIrExpression>
    ) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class ClosureApplication(
        override val type: Type,
        val functionExpression: HighIrExpression,
        val arguments: List<HighIrExpression>
    ) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class Binary(
        override val type: Type,
        val e1: HighIrExpression,
        val operator: BinaryOperator,
        val e2: HighIrExpression
    ) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class Ternary(
        override val type: Type,
        val boolExpression: HighIrExpression,
        val e1: HighIrExpression,
        val e2: HighIrExpression
    ) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    data class Lambda(
        override val type: Type.FunctionType,
        val parameters: List<Pair<String, Type>>,
        val captured: Map<String, Type>,
        val body: List<HighIrStatement>
    ) : HighIrExpression() {
        override fun <T> accept(visitor: HighIrExpressionVisitor<T>): T = visitor.visit(expression = this)
    }

    companion object {
        val TRUE: Literal = Literal(type = Type.bool, literal = samlang.ast.common.Literal.TRUE)
        val FALSE: Literal = Literal(type = Type.bool, literal = samlang.ast.common.Literal.FALSE)

        fun literal(value: Long): Literal =
            Literal(type = Type.int, literal = samlang.ast.common.Literal.of(value = value))

        fun literal(value: String): Literal =
            Literal(type = Type.string, literal = samlang.ast.common.Literal.of(value = value))
    }
}
