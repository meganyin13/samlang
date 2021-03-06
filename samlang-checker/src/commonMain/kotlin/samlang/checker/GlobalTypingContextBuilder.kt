package samlang.checker

import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import samlang.ast.common.ModuleReference
import samlang.ast.common.Sources
import samlang.ast.lang.ClassDefinition
import samlang.ast.lang.Module
import samlang.checker.GlobalTypingContext.ClassType
import samlang.checker.GlobalTypingContext.ModuleTypingContext
import samlang.checker.GlobalTypingContext.TypeInfo

/** Responsible for building the global typing environment as part of pre-processing phase. */
internal object GlobalTypingContextBuilder {
    fun buildGlobalTypingContext(sources: Sources<Module>): GlobalTypingContext {
        val phase1Modules = mutableMapOf<ModuleReference, ModuleTypingContext>()
        for ((moduleReference, module) in sources.moduleMappings) {
            phase1Modules[moduleReference] = buildModuleTypingContextPhase1(module = module)
        }
        var phase2Modules = persistentMapOf<ModuleReference, ModuleTypingContext>()
        for ((moduleReference, module) in sources.moduleMappings) {
            val context = phase1Modules[moduleReference] ?: error(message = "Should be there!")
            val updatedModuleContext = buildModuleTypingContextPhase2(
                modules = phase1Modules, moduleTypingContext = context, module = module
            )
            phase2Modules = phase2Modules.put(key = moduleReference, value = updatedModuleContext)
        }
        return GlobalTypingContext(modules = phase2Modules)
    }

    fun updateGlobalTypingContext(
        globalTypingContext: GlobalTypingContext,
        sources: Sources<Module>,
        potentiallyAffectedModuleReferences: Collection<ModuleReference>
    ): GlobalTypingContext {
        val moduleMappings = sources.moduleMappings
        var modules = globalTypingContext.modules
        // Phase 1: Build defined classes
        for (moduleReference in potentiallyAffectedModuleReferences) {
            val module = moduleMappings[moduleReference]
            modules = if (module == null) {
                modules.remove(key = moduleReference)
            } else {
                modules.put(key = moduleReference, value = buildModuleTypingContextPhase1(module = module))
            }
        }
        // Phase 2: Build imported classes
        for (moduleReference in potentiallyAffectedModuleReferences) {
            val module = moduleMappings[moduleReference] ?: continue
            val context = modules[moduleReference] ?: error(message = "Should be there!")
            val updatedModuleContext = buildModuleTypingContextPhase2(
                modules = modules, module = module, moduleTypingContext = context
            )
            modules = modules.put(key = moduleReference, value = updatedModuleContext)
        }
        return globalTypingContext.copy(modules = modules)
    }

    /**
     * @return module's typing context built from reading class definitions, imports are ignored in this phase since
     * they will be patched back in phase 2.
     */
    private fun buildModuleTypingContextPhase1(module: Module): ModuleTypingContext {
        val classes = module.classDefinitions
            .mapNotNull { classDefinition ->
                if (!classDefinition.isPublic) {
                    return@mapNotNull null
                }
                val className = classDefinition.name
                val classType = buildClassType(classDefinition = classDefinition)
                className to classType
            }
            .toMap()
            .toPersistentMap()
        return ModuleTypingContext(definedClasses = classes, importedClasses = persistentMapOf())
    }

    /**
     * @return module's typing context built from merging existing class definitions with imported ones. Existing ones
     * are built in phase 1.
     */
    private fun buildModuleTypingContextPhase2(
        modules: Map<ModuleReference, ModuleTypingContext>,
        moduleTypingContext: ModuleTypingContext,
        module: Module
    ): ModuleTypingContext {
        val importedClassTypes = module.imports.mapNotNull { oneImport ->
            val importedModuleContext = modules[oneImport.importedModule] ?: return@mapNotNull null
            oneImport.importedMembers.mapNotNull { (className, _) ->
                importedModuleContext.definedClasses[className]?.let { className to it }
            }
        }.flatten().toMap().toPersistentMap()
        return moduleTypingContext.copy(importedClasses = importedClassTypes)
    }

    /**
     * @return a class type with only typing information, built from [classDefinition].
     */
    private fun buildClassType(classDefinition: ClassDefinition): ClassType {
        val functions = mutableListOf<Pair<String, TypeInfo>>()
        val methods = mutableListOf<Pair<String, TypeInfo>>()
        for (member in classDefinition.members) {
            val name = member.name
            val typeInfo = TypeInfo(
                isPublic = member.isPublic,
                typeParams = member.typeParameters,
                type = member.type
            )
            if (member.isMethod) {
                methods.add(name to typeInfo)
            } else {
                functions.add(name to typeInfo)
            }
        }
        return ClassType(
            typeDefinition = classDefinition.typeDefinition,
            functions = functions.fold(initial = persistentMapOf()) { member, (key, value) ->
                member.put(key = key, value = value)
            },
            methods = methods.fold(initial = persistentMapOf()) { member, (key, value) ->
                member.put(key = key, value = value)
            }
        )
    }
}
