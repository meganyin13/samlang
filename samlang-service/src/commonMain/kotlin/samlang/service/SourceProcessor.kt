package samlang.service

import samlang.ast.common.ModuleReference
import samlang.ast.common.Sources
import samlang.ast.lang.Module
import samlang.ast.mir.MidIrCompilationUnit
import samlang.checker.ErrorCollector
import samlang.checker.typeCheckSources
import samlang.compiler.asm.AssemblyGenerator
import samlang.compiler.hir.compileSources
import samlang.compiler.mir.MidIrGenerator
import samlang.errors.CompileTimeError
import samlang.optimization.IrCompilationUnitOptimizer
import samlang.optimization.Optimizer
import samlang.parser.buildModuleFromText
import samlang.printer.AssemblyPrinter
import samlang.printer.OsTarget

fun checkSources(sourceHandles: List<Pair<ModuleReference, String>>): Pair<Sources<Module>, List<CompileTimeError>> {
    val errorCollector = ErrorCollector()
    val moduleMappings = mutableMapOf<ModuleReference, Module>()
    for ((moduleReference, text) in sourceHandles) {
        val (module, parseErrors) = buildModuleFromText(moduleReference = moduleReference, text = text)
        parseErrors.forEach { errorCollector.add(compileTimeError = it) }
        moduleMappings[moduleReference] = module
    }
    val (checkedSources, _) = typeCheckSources(
        sources = Sources(moduleMappings = moduleMappings),
        errorCollector = errorCollector
    )
    return checkedSources to errorCollector.collectedErrors
}

@ExperimentalStdlibApi
fun lowerToAssemblyString(
    source: Sources<Module>,
    entryModuleReference: ModuleReference,
    osTarget: OsTarget,
    optimizer: Optimizer<MidIrCompilationUnit>
): String {
    val highIrSources = compileSources(sources = source)
    val unoptimizedCompilationUnit = MidIrGenerator.generate(
        sources = highIrSources,
        entryModuleReference = entryModuleReference
    )
    val optimizedCompilationUnit = optimizer.optimize(source = unoptimizedCompilationUnit)
    val assemblyProgram = AssemblyGenerator.generate(compilationUnit = optimizedCompilationUnit)
    return AssemblyPrinter(includeComments = false, osTarget = osTarget).printProgram(program = assemblyProgram)
}

@ExperimentalStdlibApi
fun lowerToOptimizedAssemblyString(
    source: Sources<Module>,
    entryModuleReference: ModuleReference,
    osTarget: OsTarget
): String = lowerToAssemblyString(source, entryModuleReference, osTarget, IrCompilationUnitOptimizer.allEnabled)
