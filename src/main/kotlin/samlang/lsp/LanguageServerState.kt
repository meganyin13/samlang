package samlang.lsp

import samlang.Configuration
import samlang.ast.common.ModuleReference
import samlang.ast.common.Sources
import samlang.ast.lang.Module
import samlang.checker.DependencyTracker
import samlang.checker.ErrorCollector
import samlang.checker.typeCheckSources
import samlang.errors.CompilationFailedException
import samlang.parser.ModuleBuilder
import samlang.service.SourceCollector

internal class LanguageServerState {
    private val dependencyTracker: DependencyTracker = DependencyTracker()
    private val rawSources: MutableMap<ModuleReference, String> = hashMapOf()
    private val rawModules: MutableMap<ModuleReference, Module> = hashMapOf()
    private val checkedModules: MutableMap<ModuleReference, Module>

    init {
        // TODO: Separate errors into modules
        val errorCollector = ErrorCollector()
        for ((moduleReference, inputStream) in SourceCollector.collectHandles(configuration = Configuration.parse())) {
            val sourceCode = inputStream.bufferedReader().use { it.readText() }
            rawSources[moduleReference] = sourceCode
            try {
                val rawModule = ModuleBuilder.buildModuleFromText(
                    file = moduleReference.toFilename(),
                    text = sourceCode
                )
                dependencyTracker.update(
                    moduleReference = moduleReference,
                    importedModules = rawModule.imports.map { it.importedModule }
                )
                rawModules[moduleReference] = rawModule
            } catch (exception: CompilationFailedException) {
                exception.errors.forEach { errorCollector.add(compileTimeError = it) }
                continue
            }
        }
        checkedModules = typeCheckSources(
            sources = Sources(moduleMappings = rawModules),
            errorCollector = errorCollector
        ).moduleMappings.toMutableMap()
    }

    fun update(moduleReference: ModuleReference, sourceCode: String) {
        // TODO
    }

    fun remove(moduleReference: ModuleReference) {
        rawSources.remove(key = moduleReference)
        rawModules.remove(key = moduleReference)
        checkedModules.remove(key = moduleReference)
        val affected = HashSet(dependencyTracker.getReverseDependencies(moduleReference = moduleReference))
        dependencyTracker.update(moduleReference = moduleReference, importedModules = null)
        affected.addAll(elements = dependencyTracker.getReverseDependencies(moduleReference = moduleReference))
        // typeCheckSourcesIncrementally(sources = , affectedSourceList = , errorCollector = )
    }
}
