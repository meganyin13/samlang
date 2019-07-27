package samlang.errors

import samlang.ast.common.Range
import samlang.ast.lang.Type

class UnexpectedTypeError(
    expected: Type,
    actual: Type,
    range: Range
) : CompileTimeError.WithRange(
    reason = "Expected: `${expected.prettyPrint()}`, actual: `${actual.prettyPrint()}`.",
    range = range
)
