package samlang.parser

import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import samlang.ast.Position
import samlang.ast.Range

private val Token.startPosition: Position get() = Position(line = line, column = charPositionInLine)

private val Token.endPosition: Position
    get() = Position(
        line = line,
        column = charPositionInLine + text.length
    )

internal val Token.range: Range get() = Range(start = startPosition, end = endPosition)

val Token.rangeWithName: Range.WithName get() = Range.WithName(range = range, name = text)

internal val ParserRuleContext.range: Range
    get() = Range(
        start = start.startPosition,
        end = stop.endPosition
    )
