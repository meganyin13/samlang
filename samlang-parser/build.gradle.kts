plugins {
    kotlin(module = "multiplatform")
}

// Bug in Kotlin. See https://youtrack.jetbrains.com/issue/KT-34389
plugins.apply(type = org.jetbrains.kotlin.gradle.targets.js.npm.NpmResolverPlugin::class)

kotlin {
    jvm {
        tasks.named<Test>("jvmTest") {
            useJUnitPlatform()
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation(project(":samlang-ast"))
                implementation(project(":samlang-errors"))
                implementation(project(":samlang-utils"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(dependencyNotation = "org.jetbrains.kotlin:kotlin-test-common")
                implementation(dependencyNotation = "org.jetbrains.kotlin:kotlin-test-annotations-common")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib-jdk8"))
                implementation(dependencyNotation = "org.antlr:antlr4:4.8")
                implementation(dependencyNotation = "org.apache.commons:commons-text:1.6")
                implementation(project(":samlang-parser-generated-java"))
            }
        }
        val jvmTest by getting {
            dependsOn(jvmMain)
            dependencies {
                implementation(dependencyNotation = "org.jetbrains.kotlin:kotlin-test-junit")
                implementation(dependencyNotation = "io.kotlintest:kotlintest-runner-junit5:3.4.2")
            }
        }
    }
}
