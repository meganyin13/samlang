package samlang.interpreter

import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec
import samlang.checker.ProgramTypeChecker
import samlang.checker.TypeCheckingContext
import samlang.parser.ProgramBuilder
import samlang.programs.ProgramCollections
import samlang.programs.TestProgramType

class InterpreterTest : StringSpec() {

    private data class TestCase(val id: String, val code: String, val expectedResult: Value)

    private val expectations: Map<String, Value> = mapOf(
        "simple-no-ctx" to Value.UnitValue,
        "simple-no-ctx-annotated" to Value.UnitValue,
        "hello-world" to Value.StringValue(value = "Hello World!")
    )

    private val testCases: List<TestCase> = ProgramCollections.testPrograms
        .filter { it.type == TestProgramType.GOOD }
        .mapNotNull { (_, id, code) ->
            val exp = expectations[id] ?: return@mapNotNull null
            TestCase(id, code, exp)
        }

    init {
        for ((id, code, expectedValue) in testCases) {
            "interpreter expected value: $id" {
                val rawProgram = ProgramBuilder.buildProgramFromText(text = code)
                val checkedProgram = ProgramTypeChecker.typeCheck(program = rawProgram, ctx = TypeCheckingContext.EMPTY)
                val v = ProgramInterpreter.eval(program = checkedProgram)
                v shouldBe expectedValue
            }
        }
    }

}
