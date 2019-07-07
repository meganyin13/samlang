package samlang.checker

import samlang.ast.Range
import samlang.ast.TypeExpression
import samlang.ast.TypeExpression.*
import samlang.ast.TypeExpressionVisitor

/**
 * Force a parameterized type into a non-parameterized one by making each generic type as undecided type.
 */
internal object CheckedTypeDeparameterizer {

    private fun createUndecidedTypes(number: Int, range: Range): List<UndecidedType> {
        val list = arrayListOf<UndecidedType>()
        for (i in 0 until number) {
            list.add(element = UndecidedType.create(range = range))
        }
        return list
    }

    fun convert(
        typeExpression: TypeExpression,
        typeParameters: List<String>
    ): Pair<TypeExpression, List<UndecidedType>> {
        val len = typeParameters.size
        val autoGeneratedUndecidedTypes = createUndecidedTypes(number = len, range = typeExpression.range)
        val replacementMap = typeParameters.zip(other = autoGeneratedUndecidedTypes).toMap()
        return typeExpression.accept(visitor = Visitor, context = replacementMap) to autoGeneratedUndecidedTypes
    }

    fun convert(
        typeMappingRange: Range,
        typeMappings: Map<String, TypeExpression>,
        typeParameters: List<String>
    ): Pair<Map<String, TypeExpression>, List<UndecidedType>> {
        val len = typeParameters.size
        val autoGeneratedUndecidedTypes = createUndecidedTypes(number = len, range = typeMappingRange)
        val replacementMap = typeParameters.zip(other = autoGeneratedUndecidedTypes).toMap()
        val newTypeMappings = typeMappings.mapValues { (_, v) -> v.accept(visitor = Visitor, context = replacementMap) }
        return newTypeMappings to autoGeneratedUndecidedTypes
    }

    private object Visitor :
        TypeExpressionVisitor<Map<String, UndecidedType>, TypeExpression> {

        override fun visit(typeExpression: UnitType, context: Map<String, UndecidedType>): TypeExpression =
            typeExpression

        override fun visit(typeExpression: IntType, context: Map<String, UndecidedType>): TypeExpression =
            typeExpression

        override fun visit(typeExpression: StringType, context: Map<String, UndecidedType>): TypeExpression =
            typeExpression

        override fun visit(typeExpression: BoolType, context: Map<String, UndecidedType>): TypeExpression =
            typeExpression

        private fun TypeExpression.convert(context: Map<String, UndecidedType>): TypeExpression =
            accept(visitor = Visitor, context = context)

        override fun visit(typeExpression: IdentifierType, context: Map<String, UndecidedType>): TypeExpression =
            if (typeExpression.typeArguments != null) {
                val newTypeArguments = typeExpression.typeArguments.map { it.convert(context = context) }
                typeExpression.copy(typeArguments = newTypeArguments)
            } else {
                context[typeExpression.identifier] ?: typeExpression
            }

        override fun visit(typeExpression: TupleType, context: Map<String, UndecidedType>): TypeExpression =
            typeExpression.copy(mappings = typeExpression.mappings.map { it.convert(context = context) })

        override fun visit(typeExpression: FunctionType, context: Map<String, UndecidedType>): TypeExpression =
            typeExpression.copy(
                argumentTypes = typeExpression.argumentTypes.map { it.convert(context = context) },
                returnType = typeExpression.returnType.convert(context = context)
            )

        override fun visit(typeExpression: UndecidedType, context: Map<String, UndecidedType>): TypeExpression =
            error(message = "Type expression should not contain undecided type since it's the type of a module member.")

    }
}
