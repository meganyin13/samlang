package samlang.compiler.mir

import samlang.ast.common.BinaryOperator
import samlang.ast.common.BuiltInFunctionName
import samlang.ast.common.GlobalVariable
import samlang.ast.common.ModuleReference
import samlang.ast.common.UnaryOperator
import samlang.ast.hir.HighIrExpression
import samlang.ast.hir.HighIrExpression.Binary
import samlang.ast.hir.HighIrExpression.BuiltInFunctionApplication
import samlang.ast.hir.HighIrExpression.ClassMember
import samlang.ast.hir.HighIrExpression.ClosureApplication
import samlang.ast.hir.HighIrExpression.FieldAccess
import samlang.ast.hir.HighIrExpression.FunctionApplication
import samlang.ast.hir.HighIrExpression.Lambda
import samlang.ast.hir.HighIrExpression.Literal
import samlang.ast.hir.HighIrExpression.MethodAccess
import samlang.ast.hir.HighIrExpression.MethodApplication
import samlang.ast.hir.HighIrExpression.ObjectConstructor
import samlang.ast.hir.HighIrExpression.Ternary
import samlang.ast.hir.HighIrExpression.This
import samlang.ast.hir.HighIrExpression.TupleConstructor
import samlang.ast.hir.HighIrExpression.Unary
import samlang.ast.hir.HighIrExpression.UnitExpression
import samlang.ast.hir.HighIrExpression.Variable
import samlang.ast.hir.HighIrExpression.VariantConstructor
import samlang.ast.hir.HighIrExpressionVisitor
import samlang.ast.hir.HighIrPattern
import samlang.ast.hir.HighIrStatement
import samlang.ast.hir.HighIrStatement.Block
import samlang.ast.hir.HighIrStatement.ConstantDefinition
import samlang.ast.hir.HighIrStatement.IfElse
import samlang.ast.hir.HighIrStatement.LetDeclaration
import samlang.ast.hir.HighIrStatement.Match
import samlang.ast.hir.HighIrStatement.Return
import samlang.ast.hir.HighIrStatement.Throw
import samlang.ast.hir.HighIrStatement.VariableAssignment
import samlang.ast.hir.HighIrStatementVisitor
import samlang.ast.lang.Module
import samlang.ast.mir.MidIrExpression
import samlang.ast.mir.MidIrExpression.Companion.ADD
import samlang.ast.mir.MidIrExpression.Companion.CALL
import samlang.ast.mir.MidIrExpression.Companion.CONST
import samlang.ast.mir.MidIrExpression.Companion.EQ
import samlang.ast.mir.MidIrExpression.Companion.ESEQ
import samlang.ast.mir.MidIrExpression.Companion.MALLOC
import samlang.ast.mir.MidIrExpression.Companion.MEM
import samlang.ast.mir.MidIrExpression.Companion.NAME
import samlang.ast.mir.MidIrExpression.Companion.ONE
import samlang.ast.mir.MidIrExpression.Companion.OP
import samlang.ast.mir.MidIrExpression.Companion.SUB
import samlang.ast.mir.MidIrExpression.Companion.TEMP
import samlang.ast.mir.MidIrExpression.Companion.XOR
import samlang.ast.mir.MidIrExpression.Companion.ZERO
import samlang.ast.mir.MidIrOperator
import samlang.ast.mir.MidIrStatement
import samlang.ast.mir.MidIrStatement.Companion.CALL_FUNCTION
import samlang.ast.mir.MidIrStatement.Companion.CJUMP
import samlang.ast.mir.MidIrStatement.Companion.EXPR
import samlang.ast.mir.MidIrStatement.Companion.MOVE
import samlang.ast.mir.MidIrStatement.Companion.SEQ
import samlang.ast.mir.MidIrStatement.Jump
import samlang.ast.mir.MidIrStatement.Label
import samlang.checker.GlobalTypingContext

/** Generate non-canonical mid IR in the first pass */
internal class MidIrFirstPassGenerator(
    private val allocator: MidIrResourceAllocator,
    private val globalTypingContext: GlobalTypingContext,
    private val moduleReference: ModuleReference,
    private val module: Module
) {
    private val statementGenerator: StatementGenerator = StatementGenerator()
    private val expressionGenerator: ExpressionGenerator = ExpressionGenerator()

    private val globalVariableCollector: MutableSet<GlobalVariable> = LinkedHashSet()
    private val stringContentMapping: MutableMap<GlobalVariable, String> = hashMapOf()

    val globalVariables: Set<GlobalVariable> get() = globalVariableCollector
    val globalVariablesStringContentMapping: Map<GlobalVariable, String> get() = stringContentMapping

    fun translate(statement: HighIrStatement): MidIrStatement = statement.accept(visitor = statementGenerator)

    private fun translate(expression: HighIrExpression): MidIrExpression =
        expression.accept(visitor = expressionGenerator)

    private fun getFunctionName(className: String, functionName: String): String =
        NameEncoder.encodeFunctionName(
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

    private fun cJumpTranslate(
        expression: HighIrExpression,
        trueLabel: String,
        falseLabel: String,
        statementCollector: MutableList<MidIrStatement>
    ) {
        if (expression is Literal) {
            if ((expression.literal as samlang.ast.common.Literal.BoolLiteral).value) {
                statementCollector.add(Jump(trueLabel))
            } else {
                statementCollector.add(Jump(falseLabel))
            }
            return
        }
        if (expression is Binary) {
            val (_, e1, op, e2) = expression
            val freshLabel = allocator.allocateLabel()
            when (op) {
                BinaryOperator.AND -> {
                    cJumpTranslate(e1, freshLabel, falseLabel, statementCollector)
                    statementCollector.add(Label(freshLabel))
                    cJumpTranslate(e2, trueLabel, falseLabel, statementCollector)
                    return
                }
                BinaryOperator.OR -> {
                    cJumpTranslate(e1, trueLabel, freshLabel, statementCollector)
                    statementCollector.add(Label(freshLabel))
                    cJumpTranslate(e2, trueLabel, falseLabel, statementCollector)
                    return
                }
                else -> Unit
            }
        }
        statementCollector += CJUMP(
            condition = translate(expression = expression),
            label1 = trueLabel,
            label2 = falseLabel
        )
    }

    private inner class StatementGenerator : HighIrStatementVisitor<MidIrStatement> {
        override fun visit(statement: Throw): MidIrStatement = CALL_FUNCTION(
            functionName = NameEncoder.nameOfThrow,
            arguments = listOf(translate(expression = statement.expression)),
            returnCollector = null
        )

        override fun visit(statement: IfElse): MidIrStatement {
            val sequence = arrayListOf<MidIrStatement>()
            val ifBranchLabel = allocator.allocateLabelWithAnnotation(annotation = "TRUE_BRANCH")
            val elseBranchLabel = allocator.allocateLabelWithAnnotation(annotation = "FALSE_BRANCH")
            val endLabel = allocator.allocateLabelWithAnnotation(annotation = "IF_ELSE_END")
            cJumpTranslate(
                expression = statement.booleanExpression,
                trueLabel = ifBranchLabel,
                falseLabel = elseBranchLabel,
                statementCollector = sequence
            )
            sequence += Label(name = ifBranchLabel)
            sequence += translate(statement = Block(statements = statement.s1))
            sequence += Jump(label = endLabel)
            sequence += Label(name = elseBranchLabel)
            sequence += translate(statement = Block(statements = statement.s2))
            sequence += Label(name = endLabel)
            return SEQ(statements = sequence)
        }

        override fun visit(statement: Match): MidIrStatement {
            statement.matchingList
            val matchedTemp = allocator.getTemporaryByVariable(variableName = statement.variableForMatchedExpression)
            val finalAssignedVariable = statement.assignedTemporaryVariable
            val tagTemp = allocator.allocateTemp()
            val statements = arrayListOf<MidIrStatement>()
            statements += MOVE(destination = tagTemp, source = matchedTemp)
            val matchingList = statement.matchingList
            val matchBranchLabels = matchingList.map {
                allocator.allocateLabelWithAnnotation(annotation = "MATCH_BRANCH_${it.tagOrder}")
            }
            val endLabel = allocator.allocateLabelWithAnnotation(annotation = "MATCH_END")
            matchingList.forEachIndexed { index, variantPatternToStatement ->
                val dataVariable = variantPatternToStatement.dataVariable
                val currentLabel = matchBranchLabels[index]
                statements += CJUMP(
                    condition = EQ(tagTemp, CONST(variantPatternToStatement.tagOrder.toLong())),
                    label1 = currentLabel,
                    label2 = if (index < matchBranchLabels.size - 1) matchBranchLabels[index + 1] else endLabel
                )
                statements += Label(name = currentLabel)
                if (dataVariable != null) {
                    statements += MOVE(
                        destination = allocator.allocateTemp(variableName = dataVariable),
                        source = ADD(e1 = matchedTemp, e2 = CONST(value = 8))
                    )
                }
                variantPatternToStatement.statements.forEach { statements += translate(statement = it) }
                val finalAssignedExpression = variantPatternToStatement.finalExpression
                if (finalAssignedVariable == null) {
                    require(value = finalAssignedExpression == null)
                } else {
                    require(value = finalAssignedExpression != null)
                    statements += MOVE(
                        destination = allocator.getTemporaryByVariable(variableName = finalAssignedVariable),
                        source = translate(expression = finalAssignedExpression)
                    )
                }
                statements += Jump(label = endLabel)
            }
            return SEQ(statements = statements)
        }

        override fun visit(statement: LetDeclaration): MidIrStatement =
            MOVE(destination = TEMP(id = statement.name), source = ZERO)

        override fun visit(statement: VariableAssignment): MidIrStatement =
            MOVE(
                destination = allocator.getTemporaryByVariable(variableName = statement.name),
                source = translate(expression = statement.assignedExpression)
            )

        override fun visit(statement: ConstantDefinition): MidIrStatement {
            val assignedExpression = translate(expression = statement.assignedExpression)
            return when (val pattern = statement.pattern) {
                is HighIrPattern.ObjectPattern -> {
                    val temporary = allocator.allocateTemp()
                    val statements = arrayListOf<MidIrStatement>()
                    statements += MOVE(destination = temporary, source = assignedExpression)
                    pattern.destructedNames.forEach { (_, order, name) ->
                        if (name == null) {
                            return@forEach
                        }
                        val assignedTemporary = allocator.allocateTemp(variableName = name)
                        statements += MOVE(
                            destination = assignedTemporary,
                            source = MEM(
                                expression = ADD(e1 = temporary, e2 = CONST(value = order * 8L))
                            )
                        )
                    }
                    SEQ(statements = statements)
                }
                is HighIrPattern.TuplePattern -> {
                    val temporary = allocator.allocateTemp()
                    val statements = arrayListOf<MidIrStatement>()
                    statements += MOVE(destination = temporary, source = assignedExpression)
                    pattern.destructedNames.forEachIndexed { index, name ->
                        if (name == null) {
                            return@forEachIndexed
                        }
                        val assignedTemporary = allocator.allocateTemp(variableName = name)
                        statements += MOVE(
                            destination = assignedTemporary,
                            source = MEM(
                                expression = ADD(e1 = temporary, e2 = CONST(value = index * 8L))
                            )
                        )
                    }
                    SEQ(statements = statements)
                }
                is HighIrPattern.VariablePattern -> MOVE(
                    destination = allocator.getTemporaryByVariable(variableName = pattern.name),
                    source = assignedExpression
                )
                is HighIrPattern.WildCardPattern -> EXPR(expression = assignedExpression)
            }
        }

        override fun visit(statement: Return): MidIrStatement =
            MidIrStatement.Return(returnedExpression = statement.expression?.let { translate(expression = it) })

        override fun visit(statement: Block): MidIrStatement =
            SEQ(statements = statement.statements.map { translate(statement = it) })
    }

    private inner class ExpressionGenerator : HighIrExpressionVisitor<MidIrExpression> {
        override fun visit(expression: UnitExpression): MidIrExpression = CONST(value = 0)

        override fun visit(expression: Literal): MidIrExpression =
            when (val literal = expression.literal) {
                is samlang.ast.common.Literal.BoolLiteral -> CONST(value = if (literal.value) 1 else 0)
                is samlang.ast.common.Literal.IntLiteral -> CONST(value = literal.value)
                is samlang.ast.common.Literal.StringLiteral -> {
                    val (referenceVariable, contentVariable) =
                        allocator.allocateStringGlobalVariable(string = literal.value)
                    globalVariableCollector += referenceVariable
                    globalVariableCollector += contentVariable
                    stringContentMapping[contentVariable] = literal.value
                    NAME(name = referenceVariable.name)
                }
            }

        override fun visit(expression: Variable): MidIrExpression =
            allocator.getTemporaryByVariable(variableName = expression.name)

        override fun visit(expression: This): MidIrExpression = TEMP(id = "_this")

        override fun visit(expression: ClassMember): MidIrExpression {
            val name = getFunctionName(className = expression.className, functionName = expression.memberName)
            val closureTemporary = allocator.allocateTemp()
            val statements = listOf(
                MOVE(closureTemporary, MALLOC(CONST(value = 16L))),
                MOVE(destination = MEM(expression = closureTemporary), source = NAME(name = name)),
                MOVE(
                    destination = MEM(expression = ADD(e1 = closureTemporary, e2 = CONST(value = 8L))),
                    source = CONST(value = 0L)
                )
            )
            return ESEQ(SEQ(statements), closureTemporary)
        }

        override fun visit(expression: TupleConstructor): MidIrExpression {
            val tupleTemporary = allocator.allocateTemp()
            val statements = arrayListOf<MidIrStatement>()
            statements += MOVE(tupleTemporary, MALLOC(CONST(value = expression.expressionList.size * 8L)))
            expression.expressionList.forEachIndexed { index, argument ->
                statements += MOVE(
                    destination = MEM(expression = ADD(e1 = tupleTemporary, e2 = CONST(value = index * 8L))),
                    source = translate(expression = argument)
                )
            }
            return ESEQ(SEQ(statements), tupleTemporary)
        }

        override fun visit(expression: ObjectConstructor): MidIrExpression {
            val objectTemporary = allocator.allocateTemp()
            val statements = arrayListOf<MidIrStatement>()
            statements += MOVE(objectTemporary, MALLOC(CONST(value = expression.fieldDeclaration.size * 8L)))
            expression.fieldDeclaration.forEachIndexed { index, (_, fieldExpression) ->
                statements += MOVE(
                    destination = MEM(expression = ADD(e1 = objectTemporary, e2 = CONST(value = index * 8L))),
                    source = translate(expression = fieldExpression)
                )
            }
            return ESEQ(SEQ(statements), objectTemporary)
        }

        override fun visit(expression: VariantConstructor): MidIrExpression {
            val variantTemporary = allocator.allocateTemp()
            val statements = listOf(
                MOVE(variantTemporary, MALLOC(CONST(value = 16L))),
                MOVE(
                    destination = MEM(expression = variantTemporary),
                    source = CONST(value = expression.tagOrder.toLong())
                ),
                MOVE(
                    destination = MEM(expression = ADD(e1 = variantTemporary, e2 = CONST(value = 8L))),
                    source = translate(expression = expression.data)
                )
            )
            return ESEQ(SEQ(statements), variantTemporary)
        }

        override fun visit(expression: FieldAccess): MidIrExpression =
            MEM(
                expression = ADD(
                    e1 = translate(expression = expression.expression),
                    e2 = CONST(value = expression.fieldOrder * 8L)
                )
            )

        override fun visit(expression: MethodAccess): MidIrExpression {
            val name = getFunctionName(className = expression.className, functionName = expression.methodName)
            val closureTemporary = allocator.allocateTemp()
            val statements = listOf(
                MOVE(closureTemporary, MALLOC(CONST(value = 16L))),
                MOVE(destination = MEM(expression = closureTemporary), source = NAME(name = name)),
                MOVE(
                    destination = MEM(expression = ADD(e1 = closureTemporary, e2 = CONST(value = 8L))),
                    source = translate(expression = expression)
                )
            )
            return ESEQ(SEQ(statements), closureTemporary)
        }

        override fun visit(expression: Unary): MidIrExpression {
            val child = translate(expression = expression.expression)
            return when (expression.operator) {
                // xor(0, 1) = 1 ==> false -> true
                // xor(1, 1) = 0 ==> true -> false
                UnaryOperator.NOT -> XOR(e1 = child, e2 = ONE)
                UnaryOperator.NEG -> SUB(e1 = ZERO, e2 = child)
            }
        }

        override fun visit(expression: BuiltInFunctionApplication): MidIrExpression = CALL(
            functionExpr = NAME(
                name = when (expression.functionName) {
                    BuiltInFunctionName.STRING_TO_INT -> NameEncoder.nameOfStringToInt
                    BuiltInFunctionName.INT_TO_STRING -> NameEncoder.nameOfStringToInt
                    BuiltInFunctionName.PRINTLN -> NameEncoder.nameOfPrintln
                }
            ),
            args = listOf(translate(expression = expression.argument))
        )

        override fun visit(expression: FunctionApplication): MidIrExpression =
            CALL(
                functionExpr = NAME(
                    name = getFunctionName(className = expression.className, functionName = expression.functionName)
                ),
                args = expression.arguments.map { translate(expression = it) }
            )

        override fun visit(expression: MethodApplication): MidIrExpression {
            val name = getFunctionName(className = expression.className, functionName = expression.methodName)
            val arguments = arrayListOf(translate(expression = expression.objectExpression))
            expression.arguments.forEach { arguments += translate(expression = it) }
            return CALL(functionExpr = NAME(name = name), args = arguments)
        }

        override fun visit(expression: ClosureApplication): MidIrExpression {
            val closure = translate(expression = expression.functionExpression)
            val arguments = expression.arguments.map { translate(expression = it) }
            val contextTemp = allocator.allocateTemp()
            val collectorTemp = allocator.allocateTemp()
            val simpleCaseLabel = allocator.allocateLabelWithAnnotation(annotation = "CLOSURE_SIMPLE")
            val complexCaseLabel = allocator.allocateLabelWithAnnotation(annotation = "CLOSURE_COMPLEX")
            val endLabel = allocator.allocateLabelWithAnnotation(annotation = "CLOSURE_APP_END")
            val statements = listOf(
                MOVE(destination = contextTemp, source = MEM(ADD(e1 = closure, e2 = CONST(value = 8)))),
                CJUMP(condition = EQ(e1 = contextTemp, e2 = ZERO), label1 = simpleCaseLabel, label2 = complexCaseLabel),
                Label(name = simpleCaseLabel),
                // No context (context is null)
                CALL_FUNCTION(
                    expression = MEM(expression = closure),
                    arguments = arguments,
                    returnCollector = collectorTemp
                ),
                Jump(label = endLabel),
                Label(name = complexCaseLabel),
                CALL_FUNCTION(
                    expression = MEM(expression = closure),
                    arguments = arrayListOf<MidIrExpression>(contextTemp).apply { addAll(arguments) },
                    returnCollector = collectorTemp
                ),
                Label(name = endLabel)
            )
            return ESEQ(statement = SEQ(statements = statements), expression = collectorTemp)
        }

        override fun visit(expression: Binary): MidIrExpression {
            val operator = when (expression.operator) {
                BinaryOperator.MUL -> MidIrOperator.MUL
                BinaryOperator.DIV -> MidIrOperator.DIV
                BinaryOperator.MOD -> MidIrOperator.MOD
                BinaryOperator.PLUS -> MidIrOperator.ADD
                BinaryOperator.MINUS -> MidIrOperator.SUB
                BinaryOperator.LT -> MidIrOperator.LT
                BinaryOperator.LE -> MidIrOperator.LE
                BinaryOperator.GT -> MidIrOperator.GT
                BinaryOperator.GE -> MidIrOperator.GE
                BinaryOperator.EQ -> MidIrOperator.EQ
                BinaryOperator.NE -> MidIrOperator.NE
                BinaryOperator.AND -> {
                    val expr1Label = allocator.allocateLabel()
                    val expr2Label = allocator.allocateLabel()
                    val valueTemp = allocator.allocateTemp()
                    val finalLabel = allocator.allocateLabel()
                    val sequence = arrayListOf<MidIrStatement>()
                    sequence += MOVE(valueTemp, ZERO)
                    cJumpTranslate(expression.e1, expr1Label, finalLabel, sequence)
                    sequence += Label(name = expr1Label)
                    cJumpTranslate(expression.e2, expr2Label, finalLabel, sequence)
                    sequence += Label(name = expr2Label)
                    sequence += MOVE(valueTemp, ONE)
                    sequence += Label(name = finalLabel)
                    return ESEQ(statement = SEQ(sequence), expression = valueTemp)
                }
                BinaryOperator.OR -> {
                    val expr1Label = allocator.allocateLabel()
                    val expr2Label = allocator.allocateLabel()
                    val valueTemp = allocator.allocateTemp()
                    val finalLabel = allocator.allocateLabel()
                    val sequence = arrayListOf<MidIrStatement>()
                    sequence += MOVE(valueTemp, ONE)
                    cJumpTranslate(expression.e1, finalLabel, expr1Label, sequence)
                    sequence += Label(name = expr1Label)
                    cJumpTranslate(expression.e2, finalLabel, expr2Label, sequence)
                    sequence += Label(name = expr2Label)
                    sequence += MOVE(valueTemp, ZERO)
                    sequence += Label(name = finalLabel)
                    return ESEQ(statement = SEQ(sequence), expression = valueTemp)
                }
            }
            val e1 = translate(expression = expression.e1)
            val e2 = translate(expression = expression.e2)
            return OP(op = operator, e1 = e1, e2 = e2)
        }

        override fun visit(expression: Ternary): MidIrExpression {
            val temporaryForTernary = allocator.allocateTemp().id
            return ESEQ(
                statement = SEQ(
                    translate(
                        statement = IfElse(
                            booleanExpression = expression.boolExpression,
                            s1 = listOf(
                                VariableAssignment(
                                    name = temporaryForTernary,
                                    assignedExpression = expression.e1
                                )
                            ),
                            s2 = listOf(
                                VariableAssignment(
                                    name = temporaryForTernary,
                                    assignedExpression = expression.e2
                                )
                            )
                        )
                    )
                ),
                expression = TEMP(id = temporaryForTernary)
            )
        }

        override fun visit(expression: Lambda): MidIrExpression {
            TODO(reason = "NOT_IMPLEMENTED")
        }
    }
}
