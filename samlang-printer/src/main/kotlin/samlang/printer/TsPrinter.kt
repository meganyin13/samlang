package samlang.printer

import java.io.OutputStream
import java.io.PrintStream
import samlang.ast.common.BinaryOperator
import samlang.ast.common.ModuleMembersImport
import samlang.ast.common.ModuleReference
import samlang.ast.common.Type
import samlang.ast.common.TypeDefinition
import samlang.ast.common.TypeDefinitionType
import samlang.ast.common.TypeVisitor
import samlang.ast.ir.IrExpression
import samlang.ast.ir.IrExpression.Binary
import samlang.ast.ir.IrExpression.ClassMember
import samlang.ast.ir.IrExpression.FieldAccess
import samlang.ast.ir.IrExpression.FunctionApplication
import samlang.ast.ir.IrExpression.Lambda
import samlang.ast.ir.IrExpression.Literal
import samlang.ast.ir.IrExpression.MethodAccess
import samlang.ast.ir.IrExpression.ObjectConstructor
import samlang.ast.ir.IrExpression.Ternary
import samlang.ast.ir.IrExpression.This
import samlang.ast.ir.IrExpression.TupleConstructor
import samlang.ast.ir.IrExpression.Unary
import samlang.ast.ir.IrExpression.Variable
import samlang.ast.ir.IrExpression.VariantConstructor
import samlang.ast.ir.IrExpressionVisitor
import samlang.ast.ir.IrPattern
import samlang.ast.ir.IrStatement.ConstantDefinition
import samlang.ast.ir.IrStatement.IfElse
import samlang.ast.ir.IrStatement.LetDeclaration
import samlang.ast.ir.IrStatement.Match
import samlang.ast.ir.IrStatement.Return
import samlang.ast.ir.IrStatement.Throw
import samlang.ast.ir.IrStatement.VariableAssignment
import samlang.ast.ir.IrStatementVisitor
import samlang.ast.ts.TsFunction
import samlang.ast.ts.TsModule
import samlang.ast.ts.TsModuleFolder
import samlang.util.IndentedPrinter

fun printTsIndexModule(stream: OutputStream, tsModuleFolder: TsModuleFolder) {
    // use 2-space
    val indentedPrinter = IndentedPrinter(printStream = PrintStream(stream), indentationSymbol = "  ")
    // With type does not matter.
    TsPrinter(printer = indentedPrinter, withType = true).printIndexModule(tsModuleFolder = tsModuleFolder)
}

fun printTsModule(stream: OutputStream, tsModule: TsModule, withType: Boolean) {
    // use 2-space
    val indentedPrinter = IndentedPrinter(printStream = PrintStream(stream), indentationSymbol = "  ")
    TsPrinter(printer = indentedPrinter, withType = withType).printModule(tsModule = tsModule)
}

fun printTsIndexModule(tsModuleFolder: TsModuleFolder): String =
    printToStream { stream -> printTsIndexModule(stream = stream, tsModuleFolder = tsModuleFolder) }

fun printTsModule(tsModule: TsModule, withType: Boolean): String =
    printToStream { stream -> printTsModule(stream = stream, tsModule = tsModule, withType = withType) }

private class TsPrinter(private val printer: IndentedPrinter, private val withType: Boolean) {

    private val typeToStringConverter: TsTypeToStringConverter = TsTypeToStringConverter()

    fun printIndexModule(tsModuleFolder: TsModuleFolder) {
        val names = arrayListOf<String>()
        names.addAll(tsModuleFolder.subModules.map { it.typeName })
        if (names.isNotEmpty()) {
            // Insert additional _ before module part to avoid folder-module name conflicts.
            names.forEach { name ->
                printer.printWithBreak(x = "import * as $name from './_$name';")
                if (withType) {
                    printer.printWithBreak(x = "import { T_$name } from './_$name';")
                }
            }
            printer.println()
        }
        if (withType) {
            names.addAll(tsModuleFolder.subModules.map { "T_${it.typeName}" })
        }
        printer.printWithBreak(x = names.joinToString(separator = ", ", prefix = "export { ", postfix = " };"))
    }

    fun printModule(tsModule: TsModule) {
        if (tsModule.imports.isNotEmpty()) {
            tsModule.imports.forEach { printImport(moduleName = tsModule.typeName, import = it) }
            printer.println()
        }
        if (withType) {
            printTypeDefinition(name = tsModule.typeName, typeDefinition = tsModule.typeDefinition)
        }
        // Helper for wildcard assign to deal with tricky JS syntax
        if (withType) {
            printer.printWithBreak(x = "let _: any = undefined;")
        } else {
            printer.printWithBreak(x = "let _ = undefined;")
        }
        printer.println()
        tsModule.functions.forEach { printFunction(moduleName = tsModule.typeName, tsFunction = it) }
        val exports = tsModule.functions.asSequence().filter { it.shouldBeExported }.map { it.name }
        printer.printWithBreak(x = exports.joinToString(separator = ", ", prefix = "export { ", postfix = " };"))
    }

    private fun printImport(moduleName: String, import: ModuleMembersImport) {
        val (_, importedMembers, importedModule, _) = import
        if (importedModule == ModuleReference.ROOT) {
            val actualImportedMembers =
                importedMembers.asSequence().map { it.first }.filter { it != moduleName }.toList()
            val importedMemberString = actualImportedMembers.joinToString(separator = ", ")
            printer.printWithBreak(x = "import { $importedMemberString } from '.';")
            if (withType) {
                val importedTypeString = actualImportedMembers.joinToString(separator = ", ") { "T_$it" }
                printer.printWithBreak(x = "import { $importedTypeString } from '.';")
            }
        } else {
            val importedMemberString = importedMembers.joinToString(separator = ", ") { it.first }
            val importedModuleString = importedModule.parts.joinToString(separator = "/")
            printer.printWithBreak(x = "import { $importedMemberString } from '/$importedModuleString';")
            if (withType) {
                val importedTypeString = importedMembers.joinToString(separator = ", ") { "T_${it.first}" }
                printer.printWithBreak(x = "import { $importedTypeString } from '/$importedModuleString';")
            }
        }
    }

    private fun printTypeDefinition(name: String, typeDefinition: TypeDefinition) {
        val typeParameterString = typeDefinition
            .typeParameters
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = ", ", prefix = "<", postfix = ">") { "T_$it" }
            ?: ""
        when (typeDefinition.type) {
            TypeDefinitionType.OBJECT -> {
                printer.printWithBreak(x = "export type T_$name$typeParameterString = {")
                printer.indented {
                    typeDefinition.mappings.forEach { (field, type) ->
                        printWithBreak(x = "readonly $field: ${type.toTsTypeString()};")
                    }
                }
                printer.printWithBreak(x = "};")
            }
            TypeDefinitionType.VARIANT -> {
                printer.printWithBreak(x = "export type T_$name$typeParameterString =")
                printer.indented {
                    typeDefinition.mappings.forEach { (tag, type) ->
                        printWithBreak(x = """| { readonly _type: "$tag"; readonly data: ${type.toTsTypeString()} }""")
                    }
                }
            }
        }
        printer.println()
    }

    private fun printFunction(moduleName: String, tsFunction: TsFunction) {
        if (withType) {
            val (_, name, typeParameters, parameters, returnType) = tsFunction
            val typeParameterString = typeParameters
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = ", ", prefix = "<", postfix = ">") { "T_$it" }
                ?: ""
            val parameterString =
                parameters.joinToString(separator = ", ") { (name, type) -> "$name: ${type.toTsTypeString()}" }
            printer.printWithBreak(
                x = "function $name$typeParameterString($parameterString): ${returnType.toTsTypeString()} {"
            )
        } else {
            val parameterString = tsFunction.parameters.joinToString(separator = ", ") { it.first }
            printer.printWithBreak(x = "function ${tsFunction.name}($parameterString) {")
        }
        printer.indented {
            tsFunction.body.forEach {
                it.accept(visitor = TsStatementPrinter(moduleName = moduleName))
            }
        }
        printer.printWithBreak(x = "}")
        printer.println()
    }

    private fun Type.toTsTypeString(): String = this.accept(visitor = typeToStringConverter, context = Unit)

    private inner class TsTypeToStringConverter : TypeVisitor<Unit, String> {
        override fun visit(type: Type.PrimitiveType, context: Unit): String = when (type.name) {
            Type.PrimitiveTypeName.UNIT -> "void"
            Type.PrimitiveTypeName.BOOL -> "boolean"
            Type.PrimitiveTypeName.INT -> "number"
            Type.PrimitiveTypeName.STRING -> "string"
        }

        override fun visit(type: Type.IdentifierType, context: Unit): String = type.typeArguments
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = ", ", prefix = "T_${type.identifier}<", postfix = ">") { it.toTsTypeString() }
            ?: "T_${type.identifier}"

        override fun visit(type: Type.TupleType, context: Unit): String =
            type.mappings.joinToString(separator = ", ", prefix = "[", postfix = "]") { it.toTsTypeString() }

        override fun visit(type: Type.FunctionType, context: Unit): String {
            val parameters = type.argumentTypes.joinToString(separator = ", ", prefix = "(", postfix = ")") {
                it.toTsTypeString()
            }
            return "$parameters => ${type.returnType.toTsTypeString()}"
        }

        override fun visit(type: Type.UndecidedType, context: Unit): String =
            error(message = "There should be no undecided type at this point!")
    }

    private inner class TsStatementPrinter(private val moduleName: String) : IrStatementVisitor<Unit> {
        private val expressionPrinter: TsExpressionPrinter = TsExpressionPrinter()

        private fun printExpression(expression: IrExpression): Unit =
            expression.accept(visitor = expressionPrinter)

        override fun visit(statement: Throw) {
            printer.printlnWithoutFurtherIndentation {
                printer.printWithoutBreak(x = "throw new Error(")
                printExpression(expression = statement.expression)
                printer.printWithoutBreak(x = ");")
            }
        }

        override fun visit(statement: IfElse) {
            printer.printlnWithoutFurtherIndentation {
                printWithoutBreak(x = "if (")
                printExpression(expression = statement.booleanExpression)
                printWithoutBreak(x = ") {")
            }
            printer.indented {
                statement.s1.forEach { it.accept(this@TsStatementPrinter) }
            }
            printer.printWithBreak(x = "} else {")
            printer.indented {
                statement.s2.forEach { it.accept(this@TsStatementPrinter) }
            }
            printer.printWithBreak(x = "}")
        }

        override fun visit(statement: Match) {
            val (type, assignedTemporaryVariable, matchedVariable, _, matchingList) = statement
            if (assignedTemporaryVariable != null) {
                if (withType) {
                    printer.printWithBreak(x = "let $assignedTemporaryVariable: ${type.toTsTypeString()};")
                } else {
                    printer.printWithBreak(x = "let $assignedTemporaryVariable;")
                }
            }
            printer.printWithBreak(x = "switch ($matchedVariable._type) {")
            printer.indented {
                matchingList.forEach { (tag, dataVariable, statements, finalExpression) ->
                    printWithBreak(x = "case \"$tag\": {")
                    printer.indented {
                        if (dataVariable != null) {
                            printWithBreak(x = "const $dataVariable = $matchedVariable.data;")
                        }
                        statements.forEach { it.accept(this@TsStatementPrinter) }
                        if (finalExpression != IrExpression.UNIT) {
                            printlnWithoutFurtherIndentation {
                                printWithoutBreak(x = "$assignedTemporaryVariable = ")
                                printExpression(expression = finalExpression)
                                printWithoutBreak(x = ";")
                            }
                        }
                        printWithBreak(x = "break;")
                    }
                    printWithBreak(x = "}")
                }
                printWithBreak(x = "default:")
                printer.indented {
                    printWithBreak(x = "throw new Error('Impossible!');")
                }
            }
            printer.printWithBreak(x = "}")
        }

        override fun visit(statement: LetDeclaration) {
            if (withType) {
                printer.printWithBreak(x = "let ${statement.name}: ${statement.typeAnnotation.toTsTypeString()};")
            } else {
                printer.printWithBreak(x = "let ${statement.name};")
            }
        }

        override fun visit(statement: VariableAssignment) {
            printer.printlnWithoutFurtherIndentation {
                printWithBreak(x = statement.name)
                printWithBreak(x = "=")
                printExpression(expression = statement.assignedExpression)
                printWithoutBreak(";")
            }
        }

        override fun visit(statement: ConstantDefinition) {
            val (pattern, typeAnnotation, assignedExpression) = statement
            if (pattern == IrPattern.WildCardPattern) {
                printer.printlnWithoutFurtherIndentation {
                    printWithoutBreak(x = "_ = ")
                    printExpression(expression = assignedExpression)
                    printWithoutBreak(x = ";")
                }
                return
            }
            printer.printlnWithoutFurtherIndentation {
                if (withType) {
                    printWithoutBreak(x = "const $pattern: ${typeAnnotation.toTsTypeString()} = ")
                } else {
                    printWithoutBreak(x = "const $pattern = ")
                }
                printExpression(expression = assignedExpression)
                printWithoutBreak(x = ";")
            }
        }

        override fun visit(statement: Return) {
            val returnedExpression = statement.expression
            if (returnedExpression == null) {
                printer.printWithBreak(x = "return;")
            } else {
                printer.printlnWithoutFurtherIndentation {
                    printWithoutBreak(x = "return ")
                    printExpression(expression = returnedExpression)
                    printWithoutBreak(x = ";")
                }
            }
        }

        private inner class TsExpressionPrinter : IrExpressionVisitor<Unit> {

            private fun IrExpression.printSelf(withParenthesis: Boolean = false): Unit =
                if (withParenthesis) {
                    printer.printlnWithoutFurtherIndentation {
                        printWithoutBreak(x = "(")
                        printSelf()
                        print(x = ")", requireBreak = false)
                    }
                } else accept(visitor = this@TsExpressionPrinter)

            override fun visit(expression: IrExpression.Never) {
                printer.printWithoutBreak(x = "void 0")
            }

            override fun visit(expression: Literal) {
                if (expression.literal == samlang.ast.common.Literal.UnitLiteral) {
                    printer.printWithoutBreak(x = "void 0")
                } else {
                    printer.printWithoutBreak(x = expression.literal.prettyPrintedValue)
                }
            }

            override fun visit(expression: Variable) {
                printer.printWithoutBreak(x = expression.name)
            }

            override fun visit(expression: This) {
                printer.printWithoutBreak(x = "_this")
            }

            override fun visit(expression: ClassMember) {
                if (expression.className == moduleName) {
                    printer.printWithoutBreak(x = expression.memberName)
                } else {
                    printer.printWithoutBreak(x = "${expression.className}.${expression.memberName}")
                }
            }

            override fun visit(expression: TupleConstructor) {
                printer.printlnWithoutFurtherIndentation {
                    printWithoutBreak(x = "[")
                    expression.expressionList.forEachIndexed { index, e ->
                        e.printSelf()
                        if (index != expression.expressionList.size - 1) {
                            printWithoutBreak(x = ", ")
                        }
                    }
                    printWithoutBreak(x = "]")
                }
            }

            override fun visit(expression: ObjectConstructor) {
                val (_, spreadExpression, fieldDeclaration) = expression
                printer.printlnWithoutFurtherIndentation {
                    printWithoutBreak(x = "{ ")
                    if (spreadExpression != null) {
                        printWithoutBreak(x = "...")
                        spreadExpression.printSelf(
                            withParenthesis = spreadExpression.precedence >= expression.precedence
                        )
                        printWithoutBreak(x = ", ")
                    }
                    fieldDeclaration.forEachIndexed { index, (name, e) ->
                        printWithoutBreak(x = name)
                        printWithoutBreak(x = ": ")
                        e.printSelf()
                        if (index != fieldDeclaration.size - 1) {
                            printWithoutBreak(x = ", ")
                        }
                    }
                    printWithoutBreak(x = " }")
                }
            }

            override fun visit(expression: VariantConstructor) {
                printer.printlnWithoutFurtherIndentation {
                    printWithoutBreak("{ _type: \"${expression.tag}\", data: ")
                    expression.data.printSelf()
                    printWithoutBreak(x = " }")
                }
            }

            override fun visit(expression: FieldAccess) {
                printer.printlnWithoutFurtherIndentation {
                    expression.expression.printSelf(
                        withParenthesis = expression.expression.precedence >= expression.precedence
                    )
                    printWithoutBreak(x = ".${expression.fieldName}")
                }
            }

            override fun visit(expression: MethodAccess) {
                printer.printlnWithoutFurtherIndentation {
                    printWithoutBreak(x = "((...arguments) => ")
                    printWithoutBreak(x = expression.methodName)
                    printWithoutBreak(x = "(")
                    expression.expression.printSelf(withParenthesis = false)
                    printWithoutBreak(x = ", ...arguments))")
                }
            }

            override fun visit(expression: Unary) {
                printer.printlnWithoutFurtherIndentation {
                    printWithoutBreak(x = expression.operator.symbol)
                    expression.expression.printSelf(
                        withParenthesis = expression.expression.precedence > expression.precedence
                    )
                }
            }

            private fun printFunctionCallArguments(arguments: List<IrExpression>) {
                printer.printWithoutBreak(x = "(")
                arguments.forEachIndexed { index, e ->
                    e.printSelf()
                    if (index != arguments.size - 1) {
                        printer.printWithBreak(x = ",")
                    }
                }
                printer.printWithoutBreak(x = ")")
            }

            override fun visit(expression: FunctionApplication) {
                printer.printlnWithoutFurtherIndentation {
                    val (_, functionExpression, arguments) = expression
                    if (functionExpression is MethodAccess) {
                        val receiverType = functionExpression.expression.type
                                as? Type.IdentifierType
                            ?: error(message = "Method receiver must be a identifier type!")
                        if (receiverType.identifier == moduleName) {
                            printWithoutBreak(x = functionExpression.methodName)
                        } else {
                            printWithoutBreak(x = "${receiverType.identifier}.${functionExpression.methodName}")
                        }
                        val argumentsWithReceiver = arrayListOf(functionExpression.expression)
                        argumentsWithReceiver.addAll(elements = arguments)
                        printFunctionCallArguments(arguments = argumentsWithReceiver)
                    } else {
                        functionExpression.printSelf(
                            withParenthesis = expression.functionExpression.precedence >= expression.precedence
                        )
                        printFunctionCallArguments(arguments = arguments)
                    }
                }
            }

            private fun printNormalBinaryExpression(expression: Binary) {
                expression.e1.printSelf(withParenthesis = expression.e1.precedence >= expression.precedence)
                printer.printWithoutBreak(x = " ${expression.operator.symbol} ")
                expression.e2.printSelf(withParenthesis = expression.e2.precedence >= expression.precedence)
            }

            override fun visit(expression: Binary) {
                printer.printlnWithoutFurtherIndentation {
                    if (expression.operator == BinaryOperator.DIV) {
                        printWithoutBreak(x = "Math.floor(")
                        printNormalBinaryExpression(expression = expression)
                        printWithBreak(x = ")")
                        return@printlnWithoutFurtherIndentation
                    }
                    printNormalBinaryExpression(expression = expression)
                }
            }

            override fun visit(expression: Ternary) {
                printer.printlnWithoutFurtherIndentation {
                    expression.boolExpression.printSelf(
                        withParenthesis = expression.boolExpression.precedence >= expression.precedence
                    )
                    printWithoutBreak(x = " ? ")
                    expression.e1.printSelf(withParenthesis = expression.e1.precedence >= expression.precedence)
                    printWithoutBreak(x = " : ")
                    expression.e2.printSelf(withParenthesis = expression.e2.precedence >= expression.precedence)
                }
            }

            override fun visit(expression: Lambda) {
                val parameterString =
                    expression.parameters.joinToString(separator = ", ", prefix = "(", postfix = ")") { (name, type) ->
                        if (withType) {
                            "$name: ${type.toTsTypeString()}"
                        } else {
                            name
                        }
                    }
                printer.printlnWithoutFurtherIndentation {
                    printWithoutBreak(x = parameterString)
                    if (withType) {
                        printWithoutBreak(x = ": ")
                        printWithoutBreak(x = expression.type.returnType.toTsTypeString())
                    }
                    printWithoutBreak(x = " => {")
                    expression.body.forEach { it.accept(this@TsStatementPrinter) }
                    printWithoutBreak(x = "}")
                }
            }
        }
    }
}
