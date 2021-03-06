package samlang.checker

import samlang.ast.common.BinaryOperator.AND
import samlang.ast.common.BinaryOperator.CONCAT
import samlang.ast.common.BinaryOperator.DIV
import samlang.ast.common.BinaryOperator.EQ
import samlang.ast.common.BinaryOperator.GE
import samlang.ast.common.BinaryOperator.GT
import samlang.ast.common.BinaryOperator.LE
import samlang.ast.common.BinaryOperator.LT
import samlang.ast.common.BinaryOperator.MINUS
import samlang.ast.common.BinaryOperator.MOD
import samlang.ast.common.BinaryOperator.MUL
import samlang.ast.common.BinaryOperator.NE
import samlang.ast.common.BinaryOperator.OR
import samlang.ast.common.BinaryOperator.PLUS
import samlang.ast.common.Type
import samlang.ast.common.Type.FunctionType
import samlang.ast.common.Type.IdentifierType
import samlang.ast.common.Type.TupleType
import samlang.ast.common.UnaryOperator
import samlang.ast.lang.Expression
import samlang.ast.lang.Expression.Binary
import samlang.ast.lang.Expression.BuiltInFunctionCall
import samlang.ast.lang.Expression.ClassMember
import samlang.ast.lang.Expression.FieldAccess
import samlang.ast.lang.Expression.FunctionApplication
import samlang.ast.lang.Expression.IfElse
import samlang.ast.lang.Expression.Lambda
import samlang.ast.lang.Expression.Literal
import samlang.ast.lang.Expression.Match
import samlang.ast.lang.Expression.MethodAccess
import samlang.ast.lang.Expression.ObjectConstructor
import samlang.ast.lang.Expression.Panic
import samlang.ast.lang.Expression.This
import samlang.ast.lang.Expression.TupleConstructor
import samlang.ast.lang.Expression.Unary
import samlang.ast.lang.Expression.Variable
import samlang.ast.lang.Expression.VariantConstructor
import samlang.ast.lang.ExpressionVisitor
import samlang.ast.lang.Statement

internal fun fixExpressionType(
    expression: Expression,
    expectedType: Type,
    resolution: ReadOnlyTypeResolution
): Expression = expression.accept(visitor = TypeFixerVisitor(resolution = resolution), context = expectedType)

private class TypeFixerVisitor(private val resolution: ReadOnlyTypeResolution) : ExpressionVisitor<Type, Expression> {

    private fun Expression.tryFixType(expectedType: Type = this.type): Expression =
        this.accept(visitor = this@TypeFixerVisitor, context = expectedType)

    private fun Type.fixSelf(expectedType: Type?): Type {
        val resolvedPotentiallyUndecidedType = resolution.resolveType(unresolvedType = this)
        val resolvedType =
            if (UndecidedTypeCollector.collectUndecidedTypeIndices(resolvedPotentiallyUndecidedType).isNotEmpty()) {
                TypeResolver.resolveType(type = resolvedPotentiallyUndecidedType) { Type.unit }
            } else {
                resolvedPotentiallyUndecidedType
            }
        if (expectedType == null) {
            return resolvedType
        }
        if (resolvedType != expectedType) {
            error(message = "resolvedType($resolvedType) should be consistent with expectedType($expectedType)!")
        }
        return expectedType
    }

    private fun Expression.getFixedSelfType(expectedType: Type?): Type =
        type.fixSelf(expectedType = expectedType)

    private fun <T1, T2> List<T1>.checkedZip(other: List<T2>): List<Pair<T1, T2>> {
        if (size != other.size) {
            blameTypeChecker()
        }
        return zip(other = other)
    }

    private fun blameTypeChecker(): Nothing = error(message = "Slack type checker!")

    override fun visit(expression: Literal, context: Type): Expression =
        expression.copy(type = expression.getFixedSelfType(expectedType = context))

    override fun visit(expression: This, context: Type): Expression =
        expression.copy(type = expression.getFixedSelfType(expectedType = context))

    override fun visit(expression: Variable, context: Type): Expression =
        expression.copy(type = expression.getFixedSelfType(expectedType = context))

    override fun visit(expression: ClassMember, context: Type): Expression =
        expression.copy(
            type = expression.getFixedSelfType(expectedType = context),
            typeArguments = expression.typeArguments.map { typeArgument -> typeArgument.fixSelf(expectedType = null) }
        )

    override fun visit(expression: TupleConstructor, context: Type): Expression {
        val newType = expression.type.fixSelf(expectedType = context) as TupleType
        return expression.copy(
            type = newType,
            expressionList = expression.expressionList.zip(newType.mappings).map { (expression, type) ->
                expression.tryFixType(expectedType = type)
            }
        )
    }

    override fun visit(expression: ObjectConstructor, context: Type): Expression {
        val newType = expression.type.fixSelf(expectedType = context) as IdentifierType
        val newDeclarations = expression.fieldDeclarations.map { dec ->
            val betterType = dec.type.fixSelf(expectedType = null)
            when (dec) {
                is ObjectConstructor.FieldConstructor.Field -> dec.copy(
                    type = betterType,
                    expression = dec.expression.tryFixType(expectedType = betterType)
                )
                is ObjectConstructor.FieldConstructor.FieldShorthand -> dec.copy(type = betterType)
            }
        }
        return expression.copy(type = newType, fieldDeclarations = newDeclarations)
    }

    override fun visit(expression: VariantConstructor, context: Type): Expression {
        val newType = expression.getFixedSelfType(expectedType = context) as IdentifierType
        val data = expression.data.let { it.tryFixType(expectedType = it.type.fixSelf(expectedType = null)) }
        return expression.copy(type = newType, data = data)
    }

    override fun visit(expression: FieldAccess, context: Type): Expression = expression.copy(
        type = expression.getFixedSelfType(expectedType = context),
        expression = expression.expression.tryFixType(
            expectedType = expression.expression.getFixedSelfType(expectedType = null)
        )
    )

    override fun visit(expression: MethodAccess, context: Type): Expression = expression.copy(
        type = expression.getFixedSelfType(expectedType = context) as FunctionType,
        expression = expression.expression.tryFixType(
            expectedType = expression.expression.getFixedSelfType(expectedType = null)
        ),
        methodName = expression.methodName
    )

    override fun visit(expression: Unary, context: Type): Expression = expression.copy(
        type = expression.getFixedSelfType(expectedType = context),
        operator = expression.operator,
        expression = when (expression.operator) {
            UnaryOperator.NEG -> expression.expression.tryFixType()
            UnaryOperator.NOT -> expression.expression.tryFixType()
        }
    )

    override fun visit(expression: Panic, context: Type): Expression = expression.copy(
        type = expression.getFixedSelfType(expectedType = context),
        expression = expression.expression.tryFixType()
    )

    override fun visit(expression: BuiltInFunctionCall, context: Type): Expression = expression.copy(
        type = expression.getFixedSelfType(expectedType = context),
        argumentExpression = expression.argumentExpression.tryFixType()
    )

    override fun visit(expression: FunctionApplication, context: Type): Expression {
        val funExprType = expression.functionExpression.getFixedSelfType(expectedType = null) as FunctionType
        if (context != funExprType.returnType) {
            error(message = "Return type (${funExprType.returnType}$ mismatches with context ($context).")
        }
        return expression.copy(
            type = expression.getFixedSelfType(expectedType = context),
            functionExpression = expression.functionExpression.tryFixType(expectedType = funExprType),
            arguments = expression.arguments.checkedZip(other = funExprType.argumentTypes).map { (e, t) ->
                e.tryFixType(expectedType = t)
            }
        )
    }

    override fun visit(expression: Binary, context: Type): Expression {
        val (newE1, newE2) = when (expression.operator) {
            MUL, DIV, MOD, PLUS, MINUS, LT, LE, GT, GE, AND, OR, CONCAT -> {
                expression.e1.tryFixType() to expression.e2.tryFixType()
            }
            NE, EQ -> {
                val t1 = expression.e1.getFixedSelfType(expectedType = null)
                val t2 = expression.e1.getFixedSelfType(expectedType = null)
                if (t1 != t2) {
                    error(message = "Comparing non-equal types: $t1, $t2.")
                }
                val newE1 = expression.e1.tryFixType(expectedType = t1)
                val newE2 = expression.e2.tryFixType(expectedType = t1)
                newE1 to newE2
            }
        }
        return expression.copy(type = expression.getFixedSelfType(expectedType = context), e1 = newE1, e2 = newE2)
    }

    override fun visit(expression: IfElse, context: Type): Expression = expression.copy(
        type = expression.getFixedSelfType(expectedType = context),
        boolExpression = expression.boolExpression.tryFixType(),
        e1 = expression.e1.tryFixType(expectedType = context),
        e2 = expression.e2.tryFixType(expectedType = context)
    )

    override fun visit(expression: Match, context: Type): Expression {
        val matchedExpressionType =
            expression.matchedExpression.getFixedSelfType(expectedType = null) as IdentifierType
        return expression.copy(
            type = expression.getFixedSelfType(expectedType = context),
            matchedExpression = expression.matchedExpression.tryFixType(expectedType = matchedExpressionType),
            matchingList = expression.matchingList.map { (range, tag, tagOrder, dataVar, expression) ->
                Match.VariantPatternToExpr(
                    range = range,
                    tag = tag,
                    tagOrder = tagOrder,
                    dataVariable = dataVar,
                    expression = expression.tryFixType(expectedType = context)
                )
            }
        )
    }

    override fun visit(expression: Lambda, context: Type): Expression {
        val newType = expression.getFixedSelfType(expectedType = context) as FunctionType
        return expression.copy(
            type = newType,
            parameters = expression.parameters.checkedZip(other = newType.argumentTypes).map { (pAndOriginalT, t) ->
                val (parameter, originalT) = pAndOriginalT
                parameter to originalT.fixSelf(expectedType = t)
            },
            captured = expression.captured.mapValues { (_, type) -> type.fixSelf(expectedType = null) },
            body = expression.body.tryFixType(expectedType = newType.returnType)
        )
    }

    override fun visit(expression: Expression.StatementBlockExpression, context: Type): Expression {
        val block = expression.block
        if (block.expression == null && context != Type.unit) {
            error(message = "block.expression == null && context == $context")
        }
        val fixedStatements = block.statements.map { statement ->
            when (statement) {
                is Statement.Val -> {
                    val fixedAssignedExpression = statement.assignedExpression.run {
                        tryFixType(expectedType = type.fixSelf(expectedType = null))
                    }
                    statement.copy(
                        typeAnnotation = fixedAssignedExpression.type,
                        assignedExpression = fixedAssignedExpression
                    )
                }
            }
        }
        val fixedExpression = block.expression?.tryFixType(expectedType = context)
        val fixedBlock = block.copy(statements = fixedStatements, expression = fixedExpression)
        return expression.copy(type = context, block = fixedBlock)
    }
}
