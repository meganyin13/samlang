package samlang.errors

import samlang.ast.common.Range

class TypeParamSizeMismatchError(expectedSize: Int, actualSize: Int, range: Range) : CompileTimeError.WithRange(
    errorType = "TypeParamSizeMismatch",
    reason = "Incorrect number of type arguments. Expected: $expectedSize, actual: $actualSize.",
    range = range
)
