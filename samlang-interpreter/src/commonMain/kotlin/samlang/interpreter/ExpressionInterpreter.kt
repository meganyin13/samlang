package samlang.interpreter

import kotlinx.collections.immutable.plus
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
import samlang.ast.common.BuiltInFunctionName
import samlang.ast.common.Literal.BoolLiteral
import samlang.ast.common.Literal.IntLiteral
import samlang.ast.common.Literal.StringLiteral
import samlang.ast.common.Type
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
import samlang.ast.lang.Expression.StatementBlockExpression
import samlang.ast.lang.Expression.This
import samlang.ast.lang.Expression.TupleConstructor
import samlang.ast.lang.Expression.Unary
import samlang.ast.lang.Expression.Variable
import samlang.ast.lang.Expression.VariantConstructor
import samlang.ast.lang.ExpressionVisitor
import samlang.ast.lang.Pattern
import samlang.ast.lang.Statement

internal class ExpressionInterpreter : ExpressionVisitor<InterpretationContext, Value> {
    private val printedCollector: StringBuilder = StringBuilder()

    val printed: String get() = printedCollector.toString()

    fun eval(expression: Expression, context: InterpretationContext): Value =
        expression.accept(visitor = this, context = context)

    private fun blameTypeChecker(message: String = ""): Nothing = error(message = message)

    override fun visit(expression: Literal, context: InterpretationContext): Value = when (val l = expression.literal) {
        is IntLiteral -> Value.IntValue(value = l.value)
        is StringLiteral -> Value.StringValue(value = l.value)
        is BoolLiteral -> Value.BoolValue(value = l.value)
    }

    override fun visit(expression: This, context: InterpretationContext): Value =
        context.localValues["this"] ?: blameTypeChecker(message = "Missing `this`")

    override fun visit(expression: Variable, context: InterpretationContext): Value =
        context.localValues[expression.name] ?: blameTypeChecker(message = "Missing variable: $expression.")

    override fun visit(expression: ClassMember, context: InterpretationContext): Value =
        context.classes[expression.className]?.functions?.get(key = expression.memberName) ?: blameTypeChecker()

    override fun visit(expression: TupleConstructor, context: InterpretationContext): Value.TupleValue =
        Value.TupleValue(tupleContent = expression.expressionList.map { eval(expression = it, context = context) })

    override fun visit(expression: ObjectConstructor, context: InterpretationContext): Value.ObjectValue {
        val objectContent = mutableMapOf<String, Value>()
        expression.fieldDeclarations.forEach { declaration ->
            when (declaration) {
                is ObjectConstructor.FieldConstructor.Field -> {
                    objectContent[declaration.name] = eval(expression = declaration.expression, context = context)
                }
                is ObjectConstructor.FieldConstructor.FieldShorthand -> {
                    objectContent[declaration.name] = eval(
                        expression = Variable(
                            range = declaration.range,
                            type = declaration.type,
                            name = declaration.name
                        ),
                        context = context
                    )
                }
            }
        }
        return Value.ObjectValue(objectContent = objectContent)
    }

    override fun visit(expression: VariantConstructor, context: InterpretationContext): Value.VariantValue =
        Value.VariantValue(tag = expression.tag, data = eval(expression = expression.data, context = context))

    override fun visit(expression: FieldAccess, context: InterpretationContext): Value {
        val thisValue = eval(expression = expression.expression, context = context) as Value.ObjectValue
        return thisValue.objectContent[expression.fieldName] ?: blameTypeChecker()
    }

    override fun visit(expression: MethodAccess, context: InterpretationContext): Value.FunctionValue {
        val (id, _) = expression.expression.type as Type.IdentifierType
        val thisValue = eval(expression = expression.expression, context = context)
        val methodValue = context.classes[id]?.methods?.get(key = expression.methodName) ?: blameTypeChecker()
        val newCtx = context.copy(localValues = context.localValues.plus(pair = "this" to thisValue))
        methodValue.context = newCtx
        return methodValue
    }

    override fun visit(expression: Unary, context: InterpretationContext): Value {
        val v = eval(expression = expression.expression, context = context)
        return when (expression.operator) {
            UnaryOperator.NEG -> {
                v as Value.IntValue
                Value.IntValue(value = -v.value)
            }
            UnaryOperator.NOT -> {
                v as Value.BoolValue
                Value.BoolValue(value = !v.value)
            }
        }
    }

    override fun visit(expression: Panic, context: InterpretationContext): Nothing =
        throw PanicException(
            reason = (eval(
                expression = expression.expression,
                context = context
            ) as Value.StringValue).value
        )

    override fun visit(expression: BuiltInFunctionCall, context: InterpretationContext): Value {
        val argumentValue = eval(expression = expression.argumentExpression, context = context)
        return when (expression.functionName) {
            BuiltInFunctionName.STRING_TO_INT -> {
                val value = (argumentValue as Value.StringValue).value
                value.toLongOrNull()
                    ?.let { Value.IntValue(value = it) }
                    ?: throw PanicException(reason = "Cannot convert `$value` to int.")
            }
            BuiltInFunctionName.INT_TO_STRING -> Value.StringValue(
                value = (argumentValue as Value.IntValue).value.toString()
            )
            BuiltInFunctionName.PRINTLN -> {
                printedCollector.append((argumentValue as Value.StringValue).value).append('\n')
                Value.UnitValue
            }
        }
    }

    override fun visit(expression: FunctionApplication, context: InterpretationContext): Value {
        val (args, body, ctx) = eval(
            expression = expression.functionExpression,
            context = context
        ) as Value.FunctionValue
        val argValues = expression.arguments.map { eval(expression = it, context = context) }
        val bodyContext = ctx.copy(localValues = ctx.localValues.plus(pairs = args.zip(argValues)))
        return eval(expression = body, context = bodyContext)
    }

    override fun visit(expression: Binary, context: InterpretationContext): Value {
        return when (expression.operator) {
            MUL -> {
                val v1 = eval(expression = expression.e1, context = context) as Value.IntValue
                val v2 = eval(expression = expression.e2, context = context) as Value.IntValue
                Value.IntValue(value = v1.value * v2.value)
            }
            DIV -> {
                val v1 = eval(expression = expression.e1, context = context) as Value.IntValue
                val v2 = eval(expression = expression.e2, context = context) as Value.IntValue
                if (v2.value == 0L) {
                    throw PanicException(reason = "Division by zero!")
                }
                Value.IntValue(value = v1.value / v2.value)
            }
            MOD -> {
                val v1 = eval(expression = expression.e1, context = context) as Value.IntValue
                val v2 = eval(expression = expression.e2, context = context) as Value.IntValue
                if (v2.value == 0L) {
                    throw PanicException(reason = "Mod by zero!")
                }
                Value.IntValue(value = v1.value % v2.value)
            }
            PLUS -> {
                val v1 = eval(expression = expression.e1, context = context) as Value.IntValue
                val v2 = eval(expression = expression.e2, context = context) as Value.IntValue
                Value.IntValue(value = v1.value + v2.value)
            }
            MINUS -> {
                val v1 = eval(expression = expression.e1, context = context) as Value.IntValue
                val v2 = eval(expression = expression.e2, context = context) as Value.IntValue
                Value.IntValue(value = v1.value - v2.value)
            }
            LT -> {
                val v1 = eval(expression = expression.e1, context = context) as Value.IntValue
                val v2 = eval(expression = expression.e2, context = context) as Value.IntValue
                Value.BoolValue(value = v1.value < v2.value)
            }
            LE -> {
                val v1 = eval(expression = expression.e1, context = context) as Value.IntValue
                val v2 = eval(expression = expression.e2, context = context) as Value.IntValue
                Value.BoolValue(value = v1.value <= v2.value)
            }
            GT -> {
                val v1 = eval(expression = expression.e1, context = context) as Value.IntValue
                val v2 = eval(expression = expression.e2, context = context) as Value.IntValue
                Value.BoolValue(value = v1.value > v2.value)
            }
            GE -> {
                val v1 = eval(expression = expression.e1, context = context) as Value.IntValue
                val v2 = eval(expression = expression.e2, context = context) as Value.IntValue
                Value.BoolValue(value = v1.value >= v2.value)
            }
            EQ -> {
                val v1 = eval(expression = expression.e1, context = context)
                val v2 = eval(expression = expression.e2, context = context)
                if (v1 is Value.FunctionValue || v2 is Value.FunctionValue) {
                    throw PanicException(reason = "Cannot compare functions!")
                }
                Value.BoolValue(value = v1 == v2)
            }
            NE -> {
                val v1 = eval(expression = expression.e1, context = context)
                val v2 = eval(expression = expression.e2, context = context)
                if (v1 is Value.FunctionValue || v2 is Value.FunctionValue) {
                    throw PanicException(reason = "Cannot compare functions!")
                }
                Value.BoolValue(value = v1 != v2)
            }
            AND -> {
                val v1 = eval(expression = expression.e1, context = context) as Value.BoolValue
                if (!v1.value) Value.BoolValue(value = false) else eval(expression = expression.e2, context = context)
            }
            OR -> {
                val v1 = eval(expression = expression.e1, context = context) as Value.BoolValue
                if (v1.value) Value.BoolValue(value = true) else eval(expression = expression.e2, context = context)
            }
            CONCAT -> {
                val v1 = eval(expression = expression.e1, context = context) as Value.StringValue
                val v2 = eval(expression = expression.e2, context = context) as Value.StringValue
                return Value.StringValue(value = v1.value + v2.value)
            }
        }
    }

    override fun visit(expression: IfElse, context: InterpretationContext): Value = eval(
        expression = if ((eval(
                expression = expression.boolExpression,
                context = context
            ) as Value.BoolValue).value
        ) expression.e1 else expression.e2,
        context = context
    )

    override fun visit(expression: Match, context: InterpretationContext): Value {
        val matchedValue = eval(expression = expression.matchedExpression, context = context) as Value.VariantValue
        val matchedPattern = expression.matchingList.find { it.tag == matchedValue.tag } ?: blameTypeChecker()
        val ctx = matchedPattern.dataVariable?.let { variable ->
            context.copy(localValues = context.localValues.plus(pair = variable to matchedValue.data))
        } ?: context
        return eval(expression = matchedPattern.expression, context = ctx)
    }

    override fun visit(expression: Lambda, context: InterpretationContext): Value.FunctionValue = Value.FunctionValue(
        arguments = expression.parameters.map { it.first },
        body = expression.body,
        context = context
    )

    override fun visit(expression: StatementBlockExpression, context: InterpretationContext): Value {
        val block = expression.block
        var currentContext = context
        for (statement in block.statements) {
            when (statement) {
                is Statement.Val -> {
                    val assignedValue = eval(expression = statement.assignedExpression, context = currentContext)
                    currentContext = when (val p = statement.pattern) {
                        is Pattern.TuplePattern -> {
                            val tupleValues = (assignedValue as Value.TupleValue).tupleContent
                            val additionalMappings =
                                p.destructedNames.zip(tupleValues).mapNotNull { (nameWithRange, value) ->
                                    nameWithRange.first?.let { it to value }
                                }
                            currentContext.copy(localValues = currentContext.localValues + additionalMappings)
                        }
                        is Pattern.ObjectPattern -> {
                            val objectValueMappings = (assignedValue as Value.ObjectValue).objectContent
                            val additionalMappings = p.destructedNames.map { (original, _, renamed) ->
                                val v = objectValueMappings[original] ?: blameTypeChecker()
                                (renamed ?: original) to v
                            }
                            currentContext.copy(localValues = currentContext.localValues + additionalMappings)
                        }
                        is Pattern.VariablePattern -> currentContext.copy(
                            localValues = currentContext.localValues + (p.name to assignedValue)
                        )
                        is Pattern.WildCardPattern -> currentContext
                    }
                }
            }
        }
        val finalExpression = block.expression
        return if (finalExpression == null) {
            Value.UnitValue
        } else {
            eval(expression = finalExpression, context = currentContext)
        }
    }
}
