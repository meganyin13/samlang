package samlang.ast.common

sealed class Type {

    abstract fun prettyPrint(): String

    final override fun toString(): String = prettyPrint()

    abstract fun <C, T> accept(visitor: TypeVisitor<C, T>, context: C): T

    /*
     * --------------------------------------------------------------------------------
     * Part 1: Types definable in the surface syntax.
     * --------------------------------------------------------------------------------
     */

    enum class PrimitiveTypeName(val prettyPrintedName: String) {
        UNIT(prettyPrintedName = "unit"),
        BOOL(prettyPrintedName = "bool"),
        INT(prettyPrintedName = "int"),
        STRING(prettyPrintedName = "string")
    }

    data class PrimitiveType(val name: PrimitiveTypeName) : Type() {

        override fun prettyPrint(): String = name.prettyPrintedName

        override fun <C, T> accept(visitor: TypeVisitor<C, T>, context: C): T =
            visitor.visit(type = this, context = context)
    }

    data class IdentifierType(val identifier: String, val typeArguments: List<Type>) : Type() {

        override fun prettyPrint(): String = typeArguments
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = ", ", prefix = "$identifier<", postfix = ">") { it.prettyPrint() }
            ?: identifier

        override fun <C, T> accept(visitor: TypeVisitor<C, T>, context: C): T =
            visitor.visit(type = this, context = context)
    }

    data class TupleType(val mappings: List<Type>) : Type() {

        override fun prettyPrint(): String =
            mappings.joinToString(separator = " * ", prefix = "[", postfix = "]") { it.prettyPrint() }

        override fun <C, T> accept(visitor: TypeVisitor<C, T>, context: C): T =
            visitor.visit(type = this, context = context)
    }

    data class FunctionType(
        val argumentTypes: List<Type>,
        val returnType: Type
    ) : Type() {

        override fun prettyPrint(): String {
            val parameters = argumentTypes.joinToString(separator = ", ", prefix = "(", postfix = ")") {
                it.prettyPrint()
            }
            return "$parameters -> ${returnType.prettyPrint()}"
        }

        fun prettyPrintWithDummyArgumentName(): String {
            var argumentId = 0
            return argumentTypes.joinToString(separator = ", ", prefix = "(", postfix = "): $returnType") {
                val oneArgument = "a$argumentId: $it"
                argumentId++
                oneArgument
            }
        }

        override fun <C, T> accept(visitor: TypeVisitor<C, T>, context: C): T =
            visitor.visit(type = this, context = context)
    }

    /*
     * --------------------------------------------------------------------------------
     * Part 2: Hidden types used mostly for internal representation and implementation.
     * --------------------------------------------------------------------------------
     */

    data class UndecidedType(val index: Int) : Type() {

        override fun prettyPrint(): String = "__UNDECIDED__"

        override fun <C, T> accept(visitor: TypeVisitor<C, T>, context: C): T =
            visitor.visit(type = this, context = context)
    }

    companion object {
        private var nextUndecidedTypeIndex: Int = 0

        val unit: PrimitiveType = PrimitiveType(name = PrimitiveTypeName.UNIT)
        val bool: PrimitiveType = PrimitiveType(name = PrimitiveTypeName.BOOL)
        val int: PrimitiveType = PrimitiveType(name = PrimitiveTypeName.INT)
        val string: PrimitiveType = PrimitiveType(name = PrimitiveTypeName.STRING)

        fun id(identifier: String, typeArguments: List<Type> = emptyList()): IdentifierType =
            IdentifierType(identifier = identifier, typeArguments = typeArguments)

        fun undecided(): UndecidedType {
            val type = UndecidedType(
                index = nextUndecidedTypeIndex
            )
            nextUndecidedTypeIndex++
            return type
        }

        fun undecidedList(number: Int): List<UndecidedType> {
            val list = mutableListOf<UndecidedType>()
            for (i in 0 until number) {
                list.add(element = undecided())
            }
            return list
        }

        /** Exposed for testing only. Never call this in production code. */
        fun resetUndecidedTypeIndex() {
            nextUndecidedTypeIndex = 0
        }
    }
}
