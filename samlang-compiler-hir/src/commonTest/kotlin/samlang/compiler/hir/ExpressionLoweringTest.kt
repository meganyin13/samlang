package samlang.compiler.hir

import kotlin.test.Test
import kotlin.test.assertEquals
import samlang.ast.common.ModuleReference
import samlang.ast.common.Range.Companion.DUMMY as dummyRange
import samlang.ast.common.Type
import samlang.ast.common.Type.Companion.id
import samlang.ast.common.Type.Companion.unit
import samlang.ast.hir.HighIrExpression
import samlang.ast.lang.Expression
import samlang.ast.lang.Module

class ExpressionLoweringTest {
    private fun assertCorrectlyLowered(expression: Expression, expectedExpression: HighIrExpression) {
        assertEquals(
            expected = LoweringResultWithCollectedLambdas(
                syntheticFunctions = emptyList(),
                statements = emptyList(),
                expression = expectedExpression
            ),
            actual = lowerExpression(
                moduleReference = ModuleReference.ROOT,
                module = Module(imports = emptyList(), classDefinitions = emptyList()),
                encodedFunctionName = "",
                expression = expression
            )
        )
    }

    @Test
    fun expressionOnlyLoweringWorks01() {
        assertCorrectlyLowered(
            expression = Expression.Variable(range = dummyRange, type = unit, name = "foo"),
            expectedExpression = HighIrExpression.Variable(name = "foo")
        )
    }

    @Test
    fun expressionOnlyLoweringWorks02() {
        assertCorrectlyLowered(
            expression = Expression.This(range = dummyRange, type = DUMMY_IDENTIFIER_TYPE),
            expectedExpression = IR_THIS
        )
    }

    @Test
    fun expressionOnlyLoweringWorks03() {
        assertCorrectlyLowered(
            expression = Expression.FieldAccess(
                range = dummyRange, type = unit, expression = THIS, fieldName = "foo", fieldOrder = 0
            ),
            expectedExpression = HighIrExpression.IndexAccess(expression = IR_THIS, index = 0)
        )
    }

    companion object {
        private val DUMMY_IDENTIFIER_TYPE: Type.IdentifierType = id(identifier = "Dummy")
        private val THIS: Expression = Expression.This(range = dummyRange, type = DUMMY_IDENTIFIER_TYPE)
        private val IR_THIS: HighIrExpression = HighIrExpression.Variable(name = "this")
    }
}
