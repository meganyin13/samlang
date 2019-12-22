package samlang.lsp.service

import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec
import samlang.ast.common.Location
import samlang.ast.common.Position
import samlang.ast.common.Range
import samlang.lsp.services.LocationLookup

class LocationLookupTest : StringSpec() {
    init {
        "self-consistent test" {
            val lookup = LocationLookup<Unit>()
            val range = Range(start = Position(line = 1, column = 1), end = Position(line = 2, column = 2))
            val location = Location(sourcePath = "foo", range = range)
            lookup[location] = Unit
            lookup.getBestLocation(sourcePath = "foo", position = range.start) shouldBe location
            lookup.getBestLocation(sourcePath = "foo", position = range.end) shouldBe location
            lookup.getBestLocation(sourcePath = "foo", position = Position(line = 100, column = 100)) shouldBe null
            lookup.get(sourcePath = "foo", position = range.start) shouldBe Unit
            lookup.get(sourcePath = "foo", position = range.end) shouldBe Unit
            lookup.get(sourcePath = "foo", position = Position(line = 100, column = 100)) shouldBe null
        }
        "get favors small range." {
            val lookup = LocationLookup<Int>()
            val smallRange = Range(start = Position(line = 2, column = 1), end = Position(line = 3, column = 2))
            val smallLocation = Location(sourcePath = "foo", range = smallRange)
            val bigRange = Range(start = Position(line = 1, column = 1), end = Position(line = 30, column = 2))
            val bigLocation = Location(sourcePath = "foo", range = bigRange)
            lookup[smallLocation] = 1
            lookup[bigLocation] = 2
            lookup.getBestLocation(sourcePath = "foo", position = Position(line = 3, column = 1)) shouldBe smallLocation
            lookup.getBestLocation(sourcePath = "foo", position = Position(line = 10, column = 2)) shouldBe bigLocation
            lookup.getBestLocation(sourcePath = "foo", position = Position(line = 100, column = 100)) shouldBe null
            lookup.get(sourcePath = "foo", position = Position(line = 3, column = 1)) shouldBe 1
            lookup.get(sourcePath = "foo", position = Position(line = 10, column = 2)) shouldBe 2
            lookup.get(sourcePath = "foo", position = Position(line = 100, column = 100)) shouldBe null
        }
    }
}