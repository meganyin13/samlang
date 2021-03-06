package samlang.compiler.hir

import samlang.ast.common.BinaryOperator
import samlang.ast.common.BuiltInFunctionName
import samlang.ast.common.IrNameEncoder
import samlang.ast.common.IrOperator
import samlang.ast.common.ModuleReference
import samlang.ast.common.Type
import samlang.ast.common.UnaryOperator
import samlang.ast.hir.HighIrExpression
import samlang.ast.hir.HighIrExpression.Binary
import samlang.ast.hir.HighIrExpression.IndexAccess
import samlang.ast.hir.HighIrExpression.Variable
import samlang.ast.hir.HighIrFunction
import samlang.ast.hir.HighIrStatement
import samlang.ast.hir.HighIrStatement.FunctionApplication
import samlang.ast.hir.HighIrStatement.IfElse
import samlang.ast.hir.HighIrStatement.LetDefinition
import samlang.ast.hir.HighIrStatement.Return
import samlang.ast.hir.HighIrStatement.StructInitialization
import samlang.ast.lang.Expression
import samlang.ast.lang.ExpressionVisitor
import samlang.ast.lang.Module
import samlang.ast.lang.Pattern
import samlang.ast.lang.Statement

internal fun lowerExpression(
    moduleReference: ModuleReference,
    module: Module,
    encodedFunctionName: String,
    expression: Expression
): LoweringResultWithCollectedLambdas {
    val visitor = ExpressionLoweringVisitor(moduleReference, encodedFunctionName, module)
    val result = expression.accept(visitor = visitor, context = Unit)
    return LoweringResultWithCollectedLambdas(
        syntheticFunctions = visitor.syntheticFunctions,
        statements = result.statements,
        expression = result.expression
    )
}

internal data class LoweringResult(val statements: List<HighIrStatement>, val expression: HighIrExpression)

internal data class LoweringResultWithCollectedLambdas(
    val syntheticFunctions: List<HighIrFunction>,
    val statements: List<HighIrStatement>,
    val expression: HighIrExpression
)

private fun HighIrExpression.asLoweringResult(statements: List<HighIrStatement> = emptyList()): LoweringResult =
    LoweringResult(statements = statements, expression = this)

private fun List<HighIrStatement>.asLoweringResult(): LoweringResult =
    LoweringResult(statements = this, expression = HighIrExpression.ZERO)

private class ExpressionLoweringVisitor(
    private val moduleReference: ModuleReference,
    private val encodedFunctionName: String,
    private val module: Module
) : ExpressionVisitor<Unit, LoweringResult> {

    val syntheticFunctions: MutableList<HighIrFunction> = mutableListOf()

    private var nextTemporaryVariableId: Int = 0
    private var nextSyntheticFunctionId: Int = 0

    private fun allocateTemporaryVariable(): String {
        val variableName = "_t$nextTemporaryVariableId"
        nextTemporaryVariableId++
        return variableName
    }

    private fun allocateSyntheticFunctionName(): String {
        val functionName = getFunctionName(
            className = encodedFunctionName,
            functionName = "_SYNTHETIC_$nextSyntheticFunctionId"
        )
        nextSyntheticFunctionId++
        return functionName
    }

    private fun Expression.lower(): LoweringResult = accept(visitor = this@ExpressionLoweringVisitor, context = Unit)

    private fun Expression.getLoweredAndAddStatements(statements: MutableList<HighIrStatement>): HighIrExpression {
        val result = this.lower()
        statements.addAll(elements = result.statements)
        return result.expression
    }

    private fun getFunctionName(className: String, functionName: String): String =
        IrNameEncoder.encodeFunctionName(
            moduleReference = getModuleOfClass(className = className),
            className = className,
            functionName = functionName
        )

    private fun getModuleOfClass(className: String): ModuleReference = module
        .imports
        .mapNotNull { oneImport ->
            if (oneImport.importedMembers.any { it.first == className }) oneImport.importedModule else null
        }
        .firstOrNull()
        ?: this.moduleReference

    override fun visit(expression: Expression.Literal, context: Unit): LoweringResult {
        val loweredLiteral = when (val literal = expression.literal) {
            is samlang.ast.common.Literal.BoolLiteral -> HighIrExpression.IntLiteral(value = if (literal.value) 1 else 0)
            is samlang.ast.common.Literal.IntLiteral -> HighIrExpression.IntLiteral(value = literal.value)
            is samlang.ast.common.Literal.StringLiteral -> HighIrExpression.StringLiteral(value = literal.value)
        }
        return loweredLiteral.asLoweringResult()
    }

    override fun visit(expression: Expression.This, context: Unit): LoweringResult =
        Variable(name = "this").asLoweringResult()

    override fun visit(expression: Expression.Variable, context: Unit): LoweringResult =
        Variable(name = expression.name).asLoweringResult()

    override fun visit(expression: Expression.ClassMember, context: Unit): LoweringResult {
        val structVariableName = allocateTemporaryVariable()
        return LoweringResult(
            statements = listOf(
                StructInitialization(
                    structVariableName = structVariableName,
                    expressionList = listOf(
                        HighIrExpression.Name(this.getFunctionName(expression.className, expression.memberName)),
                        HighIrExpression.ZERO
                    )
                )
            ),
            expression = Variable(structVariableName)
        )
    }

    override fun visit(expression: Expression.TupleConstructor, context: Unit): LoweringResult {
        val loweredStatements = mutableListOf<HighIrStatement>()
        val loweredExpressionList = expression.expressionList.map {
            it.getLoweredAndAddStatements(statements = loweredStatements)
        }
        val tupleVariableName = allocateTemporaryVariable()
        loweredStatements += StructInitialization(
            structVariableName = tupleVariableName,
            expressionList = loweredExpressionList
        )
        return LoweringResult(statements = loweredStatements, expression = Variable(tupleVariableName))
    }

    override fun visit(expression: Expression.ObjectConstructor, context: Unit): LoweringResult {
        val loweredStatements = mutableListOf<HighIrStatement>()
        val loweredFields = expression.fieldDeclarations.map { fieldConstructor ->
            when (fieldConstructor) {
                is Expression.ObjectConstructor.FieldConstructor.Field -> {
                    val result = fieldConstructor.expression.lower()
                    loweredStatements.addAll(elements = result.statements)
                    val loweredFieldExpression = result.expression
                    loweredFieldExpression
                }
                is Expression.ObjectConstructor.FieldConstructor.FieldShorthand -> {
                    val result = Expression.Variable(
                        range = fieldConstructor.range,
                        type = fieldConstructor.type,
                        name = fieldConstructor.name
                    ).lower()
                    loweredStatements.addAll(elements = result.statements)
                    val loweredFieldExpression = result.expression
                    loweredFieldExpression
                }
            }
        }
        val objectName = allocateTemporaryVariable()
        loweredStatements += StructInitialization(
            structVariableName = objectName,
            expressionList = loweredFields
        )
        return LoweringResult(statements = loweredStatements, expression = Variable(objectName))
    }

    override fun visit(expression: Expression.VariantConstructor, context: Unit): LoweringResult {
        val result = expression.data.lower()
        val variantName = allocateTemporaryVariable()
        val loweredStatements = result.statements + StructInitialization(
            structVariableName = variantName,
            expressionList = listOf(
                HighIrExpression.IntLiteral(value = expression.tagOrder.toLong()),
                result.expression
            )
        )
        return LoweringResult(statements = loweredStatements, expression = Variable(variantName))
    }

    override fun visit(expression: Expression.FieldAccess, context: Unit): LoweringResult {
        val result = expression.expression.lower()
        return IndexAccess(
            expression = result.expression,
            index = expression.fieldOrder
        ).asLoweringResult(statements = result.statements)
    }

    override fun visit(expression: Expression.MethodAccess, context: Unit): LoweringResult {
        val result = expression.expression.lower()
        val closureName = allocateTemporaryVariable()
        val loweredStatements = result.statements + StructInitialization(
            structVariableName = closureName,
            expressionList = listOf(
                HighIrExpression.Name(
                    name = this.getFunctionName(
                        className = (expression.expression.type as Type.IdentifierType).identifier,
                        functionName = expression.methodName
                    )
                ),
                result.expression
            )
        )
        return LoweringResult(statements = loweredStatements, expression = Variable(closureName))
    }

    override fun visit(expression: Expression.Unary, context: Unit): LoweringResult {
        val result = expression.expression.lower()
        return when (expression.operator) {
            UnaryOperator.NOT -> Binary(
                operator = IrOperator.XOR,
                e1 = result.expression,
                e2 = HighIrExpression.IntLiteral(value = 1L)
            ).asLoweringResult(statements = result.statements)
            UnaryOperator.NEG -> Binary(
                operator = IrOperator.SUB,
                e1 = HighIrExpression.ZERO,
                e2 = result.expression
            ).asLoweringResult(statements = result.statements)
        }
    }

    override fun visit(expression: Expression.Panic, context: Unit): LoweringResult {
        val loweredStatements = mutableListOf<HighIrStatement>()
        val result = expression.expression.lower()
        loweredStatements += result.statements
        loweredStatements += FunctionApplication(
            functionExpression = HighIrExpression.Name(IrNameEncoder.nameOfThrow),
            arguments = listOf(result.expression),
            resultCollector = allocateTemporaryVariable()
        )
        return LoweringResult(statements = loweredStatements, expression = HighIrExpression.ZERO)
    }

    override fun visit(expression: Expression.BuiltInFunctionCall, context: Unit): LoweringResult {
        val loweredStatements = mutableListOf<HighIrStatement>()
        val (statements, argument) = expression.argumentExpression.lower()
        loweredStatements += statements
        val returnCollector = allocateTemporaryVariable()
        val name = when (expression.functionName) {
            BuiltInFunctionName.INT_TO_STRING -> IrNameEncoder.nameOfIntToString
            BuiltInFunctionName.STRING_TO_INT -> IrNameEncoder.nameOfStringToInt
            BuiltInFunctionName.PRINTLN -> IrNameEncoder.nameOfPrintln
        }
        loweredStatements += FunctionApplication(
            functionExpression = HighIrExpression.Name(name),
            arguments = listOf(argument),
            resultCollector = returnCollector
        )
        return LoweringResult(statements = loweredStatements, expression = Variable(name = returnCollector))
    }

    override fun visit(expression: Expression.FunctionApplication, context: Unit): LoweringResult {
        val loweredStatements = mutableListOf<HighIrStatement>()
        val functionExpression = expression.functionExpression
        // This indirection is necessary.
        // We want to force a function call to fall into a statement.
        // In this way, the final expression can be safely ignored and side effect of function still preserved.
        val temporary = allocateTemporaryVariable()
        loweredStatements += when (functionExpression) {
            is Expression.ClassMember -> FunctionApplication(
                functionExpression = HighIrExpression.Name(
                    this.getFunctionName(functionExpression.className, functionExpression.memberName)
                ),
                arguments = expression.arguments.map { argument ->
                    argument.getLoweredAndAddStatements(statements = loweredStatements)
                },
                resultCollector = temporary
            )
            is Expression.MethodAccess -> FunctionApplication(
                functionExpression = HighIrExpression.Name(
                    this.getFunctionName(
                        className = (functionExpression.expression.type as Type.IdentifierType).identifier,
                        functionName = functionExpression.methodName
                    )
                ),
                arguments = listOf(
                    functionExpression.expression.getLoweredAndAddStatements(statements = loweredStatements),
                    *expression.arguments
                        .map { it.getLoweredAndAddStatements(statements = loweredStatements) }
                        .toTypedArray()
                ),
                resultCollector = temporary
            )
            else -> {
                /**
                 * Closure ABI:
                 * {
                 *    __length__: 2
                 *    [0]: reference to the function
                 *    [1]: context
                 * }
                 *
                 * If context is NULL (0), then it will directly call the function like functionExpr(...restArguments).
                 * If context is NONNULL, then it will call functionExpr(context, ...restArguments);
                 */
                val loweredFunctionExpression = functionExpression.getLoweredAndAddStatements(statements = loweredStatements)
                val functionArguments = expression.arguments.map { it.getLoweredAndAddStatements(statements = loweredStatements) }

                val closureTemporary = allocateTemporaryVariable()
                val contextTemporary = allocateTemporaryVariable()
                loweredStatements += LetDefinition(closureTemporary, loweredFunctionExpression)
                loweredStatements += LetDefinition(contextTemporary, IndexAccess(expression = Variable(closureTemporary), index = 1))
                IfElse(
                    booleanExpression = Binary(
                        operator = IrOperator.EQ,
                        e1 = Variable(contextTemporary),
                        e2 = HighIrExpression.ZERO
                    ),
                    s1 = listOf(
                        FunctionApplication(
                            functionExpression = IndexAccess(expression = Variable(closureTemporary), index = 0),
                            arguments = functionArguments,
                            resultCollector = temporary
                        )
                    ),
                    s2 = listOf(
                        FunctionApplication(
                            functionExpression = IndexAccess(expression = Variable(closureTemporary), index = 0),
                            arguments = listOf(Variable(contextTemporary), *functionArguments.toTypedArray()),
                            resultCollector = temporary
                        )
                    )
                )
            }
        }
        return LoweringResult(statements = loweredStatements, expression = Variable(name = temporary))
    }

    private fun shortCircuitBehaviorPreservingBoolExpressionLowering(expression: Expression): LoweringResult {
        if (expression is Expression.Literal) {
            val literal = expression.literal
            if (literal is samlang.ast.common.Literal.BoolLiteral) {
                return if (literal.value) {
                    HighIrExpression.IntLiteral(value = 1L).asLoweringResult()
                } else {
                    HighIrExpression.ZERO.asLoweringResult()
                }
            }
        }
        if (expression !is Expression.Binary) {
            return expression.lower()
        }
        val operator = expression.operator
        val e1 = expression.e1
        val e2 = expression.e2
        return when (operator) {
            BinaryOperator.AND -> {
                val temp = allocateTemporaryVariable()
                val e1Result = shortCircuitBehaviorPreservingBoolExpressionLowering(expression = e1)
                val e2Result = shortCircuitBehaviorPreservingBoolExpressionLowering(expression = e2)
                LoweringResult(
                    statements = listOf(
                        *e1Result.statements.toTypedArray(),
                        IfElse(
                            booleanExpression = e1Result.expression,
                            s1 = listOf(
                                *e2Result.statements.toTypedArray(),
                                LetDefinition(name = temp, assignedExpression = e2Result.expression)
                            ),
                            s2 = listOf(LetDefinition(name = temp, assignedExpression = HighIrExpression.ZERO))
                        )
                    ),
                    expression = Variable(name = temp)
                )
            }
            BinaryOperator.OR -> {
                val temp = allocateTemporaryVariable()
                val e1Result = shortCircuitBehaviorPreservingBoolExpressionLowering(expression = e1)
                val e2Result = shortCircuitBehaviorPreservingBoolExpressionLowering(expression = e2)
                LoweringResult(
                    statements = listOf(
                        *e1Result.statements.toTypedArray(),
                        IfElse(
                            booleanExpression = e1Result.expression,
                            s1 = listOf(
                                LetDefinition(name = temp, assignedExpression = HighIrExpression.IntLiteral(value = 1L))
                            ),
                            s2 = listOf(
                                *e2Result.statements.toTypedArray(),
                                LetDefinition(name = temp, assignedExpression = e2Result.expression)
                            )
                        )
                    ),
                    expression = Variable(name = temp)
                )
            }
            BinaryOperator.CONCAT -> {
                val loweredStatements = mutableListOf<HighIrStatement>()
                val loweredE1 = expression.e1.getLoweredAndAddStatements(statements = loweredStatements)
                val loweredE2 = expression.e2.getLoweredAndAddStatements(statements = loweredStatements)
                val collector = allocateTemporaryVariable()
                loweredStatements += FunctionApplication(
                    functionExpression = HighIrExpression.Name(IrNameEncoder.nameOfStringConcat),
                    arguments = listOf(loweredE1, loweredE2),
                    resultCollector = collector
                )
                LoweringResult(statements = loweredStatements, expression = Variable(collector))
            }
            else -> {
                val loweredStatements = mutableListOf<HighIrStatement>()
                val loweredE1 = expression.e1.getLoweredAndAddStatements(statements = loweredStatements)
                val loweredE2 = expression.e2.getLoweredAndAddStatements(statements = loweredStatements)
                val irOperator = when (expression.operator) {
                    BinaryOperator.MUL -> IrOperator.MUL
                    BinaryOperator.DIV -> IrOperator.DIV
                    BinaryOperator.MOD -> IrOperator.MOD
                    BinaryOperator.PLUS -> IrOperator.ADD
                    BinaryOperator.MINUS -> IrOperator.SUB
                    BinaryOperator.LT -> IrOperator.LT
                    BinaryOperator.LE -> IrOperator.LE
                    BinaryOperator.GT -> IrOperator.GT
                    BinaryOperator.GE -> IrOperator.GE
                    BinaryOperator.EQ -> IrOperator.EQ
                    BinaryOperator.NE -> IrOperator.NE
                    BinaryOperator.AND -> error(message = "AND SHOULD BE GONE!")
                    BinaryOperator.OR -> error(message = "OR SHOULD BE GONE!")
                    BinaryOperator.CONCAT -> error(message = "CONCAT SHOULD BE GONE!")
                }
                Binary(operator = irOperator, e1 = loweredE1, e2 = loweredE2)
                    .asLoweringResult(statements = loweredStatements)
            }
        }
    }

    override fun visit(expression: Expression.Binary, context: Unit): LoweringResult =
        shortCircuitBehaviorPreservingBoolExpressionLowering(expression)

    override fun visit(expression: Expression.IfElse, context: Unit): LoweringResult {
        val loweredStatements = mutableListOf<HighIrStatement>()
        val boolExpression = expression.boolExpression
            .getLoweredAndAddStatements(statements = loweredStatements)
        val e1LoweringResult = expression.e1.lower()
        val e2LoweringResult = expression.e2.lower()
        val variableForIfElseAssign = allocateTemporaryVariable()
        val loweredS1 = e1LoweringResult.statements.plus(
            element = LetDefinition(
                name = variableForIfElseAssign,
                assignedExpression = e1LoweringResult.expression
            )
        )
        val loweredS2 = e2LoweringResult.statements.plus(
            element = LetDefinition(
                name = variableForIfElseAssign,
                assignedExpression = e2LoweringResult.expression
            )
        )
        loweredStatements += IfElse(booleanExpression = boolExpression, s1 = loweredS1, s2 = loweredS2)
        return LoweringResult(
            statements = loweredStatements,
            expression = Variable(name = variableForIfElseAssign)
        )
    }

    override fun visit(expression: Expression.Match, context: Unit): LoweringResult {
        val loweredStatements = mutableListOf<HighIrStatement>()
        val matchedExpression = expression.matchedExpression
            .getLoweredAndAddStatements(statements = loweredStatements)
        val variableForMatchedExpression = allocateTemporaryVariable()
        val variableForTag = allocateTemporaryVariable()
        val temporaryVariable = allocateTemporaryVariable()
        loweredStatements += LetDefinition(
            name = variableForMatchedExpression,
            assignedExpression = matchedExpression
        )
        loweredStatements += LetDefinition(
            name = variableForTag,
            assignedExpression = IndexAccess(expression = Variable(variableForMatchedExpression), index = 0)
        )
        val loweredMatchingList = expression.matchingList.map { patternToExpression ->
            val localStatements = mutableListOf<HighIrStatement>()
            val dataVariable = patternToExpression.dataVariable
            if (dataVariable != null) {
                localStatements += LetDefinition(
                    name = dataVariable,
                    assignedExpression = IndexAccess(expression = Variable(variableForMatchedExpression), index = 1)
                )
            }
            val result = patternToExpression.expression.lower()
            localStatements += result.statements
            localStatements += LetDefinition(name = temporaryVariable, assignedExpression = result.expression)
            patternToExpression.tagOrder to localStatements
        }
        var ifElse = IfElse(
            booleanExpression = Binary(
                operator = IrOperator.EQ,
                e1 = Variable(variableForTag),
                e2 = HighIrExpression.IntLiteral(value = loweredMatchingList.last().first.toLong())
            ),
            s1 = loweredMatchingList.last().second,
            s2 = emptyList()
        )
        for (i in (loweredMatchingList.size - 1) downTo 0) {
            val (tagOrder, localStatements) = loweredMatchingList[i]
            ifElse = IfElse(
                booleanExpression = Binary(
                    operator = IrOperator.EQ,
                    e1 = Variable(variableForTag),
                    e2 = HighIrExpression.IntLiteral(value = tagOrder.toLong())
                ),
                s1 = localStatements,
                s2 = listOf(ifElse)
            )
        }
        loweredStatements += ifElse
        return Variable(name = temporaryVariable).asLoweringResult(statements = loweredStatements)
    }

    override fun visit(expression: Expression.Lambda, context: Unit): LoweringResult {
        val syntheticLambda = createSyntheticLambdaFunction(expression)
        syntheticFunctions += syntheticLambda

        val closureName = allocateTemporaryVariable()
        val loweredStatements = mutableListOf<HighIrStatement>()
        val closureContext = if (expression.captured.isNotEmpty()) {
            val contextName = allocateTemporaryVariable()
            loweredStatements += StructInitialization(
                structVariableName = contextName,
                expressionList = expression.captured.keys.map { Variable(it) }
            )
            Variable(contextName)
        } else {
            HighIrExpression.IntLiteral(value = 1L) // A dummy value that is not zero
        }
        loweredStatements += StructInitialization(
            structVariableName = closureName,
            expressionList = listOf(HighIrExpression.Name(syntheticLambda.name), closureContext)
        )
        return LoweringResult(statements = loweredStatements, expression = Variable(closureName))
    }

    private fun createSyntheticLambdaFunction(expression: Expression.Lambda): HighIrFunction {
        val loweringResult = expression.body.lower()
        val lambdaStatements = mutableListOf<HighIrStatement>()
        expression.captured.keys.forEachIndexed { index, variable ->
            lambdaStatements += LetDefinition(
                name = variable,
                assignedExpression = IndexAccess(expression = Variable(name = "_context"), index = index)
            )
        }
        lambdaStatements.addAll(elements = loweringResult.statements)
        val hasReturn = expression.type.returnType != Type.unit
        if (hasReturn) {
            lambdaStatements += Return(expression = loweringResult.expression)
        }
        return HighIrFunction(
            name = allocateSyntheticFunctionName(),
            parameters = listOf("_context", *expression.parameters.map { it.first }.toTypedArray()),
            hasReturn = hasReturn,
            body = lambdaStatements
        )
    }

    override fun visit(expression: Expression.StatementBlockExpression, context: Unit): LoweringResult {
        val block = expression.block
        val loweredScopedStatements = mutableListOf<HighIrStatement>()
        for (statement in block.statements) {
            when (statement) {
                is Statement.Val -> {
                    val loweredAssignedExpression = statement.assignedExpression
                        .getLoweredAndAddStatements(statements = loweredScopedStatements)
                    when (val pattern = statement.pattern) {
                        is Pattern.TuplePattern -> {
                            val variableForDestructedExpression = allocateTemporaryVariable()
                            loweredScopedStatements += LetDefinition(
                                name = variableForDestructedExpression,
                                assignedExpression = loweredAssignedExpression
                            )
                            pattern.destructedNames.forEachIndexed { index, (name) ->
                                if (name != null) {
                                    loweredScopedStatements += LetDefinition(
                                        name = name,
                                        assignedExpression = IndexAccess(
                                            expression = Variable(name = variableForDestructedExpression),
                                            index = index
                                        )
                                    )
                                }
                            }
                        }
                        is Pattern.ObjectPattern -> {
                            val variableForDestructedExpression = allocateTemporaryVariable()
                            loweredScopedStatements += LetDefinition(
                                name = variableForDestructedExpression,
                                assignedExpression = loweredAssignedExpression
                            )
                            pattern.destructedNames.forEach { (name, order, renamed, _) ->
                                loweredScopedStatements += LetDefinition(
                                    name = renamed ?: name,
                                    assignedExpression = IndexAccess(
                                        expression = Variable(name = variableForDestructedExpression),
                                        index = order
                                    )
                                )
                            }
                        }
                        is Pattern.VariablePattern -> {
                            loweredScopedStatements += LetDefinition(
                                name = pattern.name,
                                assignedExpression = loweredAssignedExpression
                            )
                        }
                        is Pattern.WildCardPattern -> {
                            loweredScopedStatements += LetDefinition(
                                name = allocateTemporaryVariable(),
                                assignedExpression = loweredAssignedExpression
                            )
                        }
                    }
                }
            }
        }
        val finalExpression = block.expression
            ?: return loweredScopedStatements.asLoweringResult()
        val loweredFinalExpression = finalExpression.getLoweredAndAddStatements(statements = loweredScopedStatements)
        return LoweringResult(statements = loweredScopedStatements, expression = loweredFinalExpression)
    }
}
