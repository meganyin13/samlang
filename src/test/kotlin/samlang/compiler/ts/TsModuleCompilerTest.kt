package samlang.compiler.ts

import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec
import samlang.ast.common.Type.Companion.unit
import samlang.ast.common.Type.FunctionType
import samlang.ast.common.Type.IdentifierType
import samlang.ast.common.TypeDefinition
import samlang.ast.common.TypeDefinitionType
import samlang.ast.ir.IrExpression
import samlang.ast.ir.IrStatement
import samlang.ast.lang.ClassDefinition
import samlang.ast.lang.Expression
import samlang.ast.lang.Module
import samlang.ast.ts.TsFunction
import samlang.ast.ts.TsModule
import samlang.ast.common.Range.Companion.DUMMY as dummyRange

class TsModuleCompilerTest : StringSpec() {

    private fun assertCorrectlyCompiled(classDefinitions: List<ClassDefinition>, tsModule: TsModule) {
        compileTsModule(module = Module(imports = emptyList(), classDefinitions = classDefinitions)) shouldBe tsModule
    }

    private fun assertCorrectlyCompiled(classDefinition: ClassDefinition, tsModule: TsModule) {
        compileTsModule(
            module = Module(imports = emptyList(), classDefinitions = listOf(classDefinition))
        ) shouldBe tsModule
    }

    init {
        "Empty module is correctly compiled." {
            assertCorrectlyCompiled(
                classDefinitions = emptyList(),
                tsModule = TsModule(imports = emptyList(), typeDefinitions = emptyList(), functions = emptyList())
            )
        }
        "Dummy module is correctly compiled." {
            assertCorrectlyCompiled(
                classDefinition = ClassDefinition(
                    range = dummyRange,
                    nameRange = dummyRange,
                    name = "Foo",
                    typeDefinition = DUMMY_TYPE_DEFINITION,
                    members = listOf()
                ),
                tsModule = TsModule(
                    imports = emptyList(), typeDefinitions = listOf(DUMMY_TYPE_DEFINITION), functions = emptyList()
                )
            )
        }
        "Simple functions are correctly compiled." {
            assertCorrectlyCompiled(
                classDefinition = ClassDefinition(
                    range = dummyRange,
                    nameRange = dummyRange,
                    name = "Test",
                    typeDefinition = DUMMY_TYPE_DEFINITION,
                    members = listOf(
                        ClassDefinition.MemberDefinition(
                            range = dummyRange,
                            isPublic = true,
                            isMethod = false,
                            nameRange = dummyRange,
                            name = "foo",
                            typeParameters = null,
                            type = FunctionType(argumentTypes = emptyList(), returnType = unit),
                            parameters = emptyList(),
                            body = THIS
                        ),
                        ClassDefinition.MemberDefinition(
                            range = dummyRange,
                            isPublic = false,
                            isMethod = false,
                            nameRange = dummyRange,
                            name = "bar",
                            typeParameters = null,
                            type = FunctionType(argumentTypes = emptyList(), returnType = unit),
                            parameters = emptyList(),
                            body = THIS
                        )
                    )
                ),
                tsModule = TsModule(
                    imports = emptyList(),
                    typeDefinitions = listOf(DUMMY_TYPE_DEFINITION),
                    functions = listOf(
                        TsFunction(
                            name = "Test\$foo",
                            shouldBeExported = true,
                            typeParameters = null,
                            parameters = emptyList(),
                            returnType = unit,
                            body = listOf(IrStatement.Return(expression = IR_THIS))
                        ),
                        TsFunction(
                            name = "Test\$bar",
                            shouldBeExported = false,
                            typeParameters = null,
                            parameters = emptyList(),
                            returnType = unit,
                            body = listOf(IrStatement.Return(expression = IR_THIS))
                        )
                    )
                )
            )
        }
        "Simple methods are correctly compiled." {
            assertCorrectlyCompiled(
                classDefinition = ClassDefinition(
                    range = dummyRange,
                    nameRange = dummyRange,
                    name = "Foo",
                    typeDefinition = DUMMY_TYPE_DEFINITION,
                    members = listOf(
                        ClassDefinition.MemberDefinition(
                            range = dummyRange,
                            isPublic = true,
                            isMethod = true,
                            nameRange = dummyRange,
                            name = "bar",
                            typeParameters = null,
                            type = FunctionType(argumentTypes = emptyList(), returnType = unit),
                            parameters = emptyList(),
                            body = THIS
                        )
                    )
                ),
                tsModule = TsModule(
                    imports = emptyList(),
                    typeDefinitions = listOf(DUMMY_TYPE_DEFINITION),
                    functions = listOf(
                        TsFunction(
                            name = "Foo\$bar",
                            shouldBeExported = true,
                            typeParameters = null,
                            parameters = listOf("_this" to IdentifierType(identifier = "Foo", typeArguments = null)),
                            returnType = unit,
                            body = listOf(IrStatement.Return(expression = IR_THIS))
                        )
                    )
                )
            )
        }
        "Complex methods are correctly compiled." {
            val typeDefinition = TypeDefinition(
                range = dummyRange,
                type = TypeDefinitionType.OBJECT,
                typeParameters = listOf("A", "B"),
                mappings = emptyMap()
            )
            assertCorrectlyCompiled(
                classDefinition = ClassDefinition(
                    range = dummyRange,
                    nameRange = dummyRange,
                    name = "Foo",
                    typeDefinition = typeDefinition,
                    members = listOf(
                        ClassDefinition.MemberDefinition(
                            range = dummyRange,
                            isPublic = true,
                            isMethod = true,
                            nameRange = dummyRange,
                            name = "bar",
                            typeParameters = listOf("C", "D"),
                            type = FunctionType(argumentTypes = emptyList(), returnType = unit),
                            parameters = emptyList(),
                            body = THIS
                        )
                    )
                ),
                tsModule = TsModule(
                    imports = emptyList(),
                    typeDefinitions = listOf(typeDefinition),
                    functions = listOf(
                        TsFunction(
                            name = "Foo\$bar",
                            shouldBeExported = true,
                            typeParameters = listOf("A", "B", "C", "D"),
                            parameters = listOf(
                                "_this" to IdentifierType(
                                    identifier = "Foo",
                                    typeArguments = listOf(
                                        IdentifierType(identifier = "A", typeArguments = null),
                                        IdentifierType(identifier = "B", typeArguments = null)
                                    )
                                )
                            ),
                            returnType = unit,
                            body = listOf(IrStatement.Return(expression = IR_THIS))
                        )
                    )
                )
            )
        }
    }

    companion object {
        private val THIS: Expression = Expression.This(range = dummyRange, type = unit)
        private val IR_THIS: IrExpression = IrExpression.Variable(name = "_this")
        private val DUMMY_TYPE_DEFINITION: TypeDefinition = TypeDefinition.ofDummy(range = dummyRange)
    }
}