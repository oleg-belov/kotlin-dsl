/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.kotlin.dsl.support

import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.classpath.DefaultClassPath
import org.gradle.util.TextUtil.normaliseFileSeparators

import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult

import java.io.Closeable
import java.io.File
import java.util.jar.JarFile


internal
fun classPathBytesRepositoryFor(jarsOrDirs: List<File>) =
    ClassBytesRepository(DefaultClassPath.of(jarsOrDirs))


private
typealias ClassBytesSupplier = () -> ByteArray


private
typealias ClassBytesIndex = (String) -> ClassBytesSupplier?


/**
 * Repository providing access to class bytes by Kotlin source names.
 *
 * Follows the one directory per package name segment convention.
 * Keeps JAR files open for fast lookup, must be closed.
 */
internal
class ClassBytesRepository(classPath: ClassPath) : Closeable {

    private
    val openJars = mutableMapOf<File, JarFile>()

    private
    val classPathFiles: List<File> = classPath.asFiles

    private
    val classBytesIndex = classPathFiles.map { classBytesIndexFor(it) }

    /**
     * Class file bytes for Kotlin source name, if found.
     */
    fun classBytesFor(sourceName: String): ByteArray? =
        classBytesSupplierForSourceName(sourceName)?.let { it() }

    /**
     * All found class files bytes by Kotlin source name.
     */
    fun allClassesBytesBySourceName(): Sequence<Pair<String, ClassBytesSupplier>> =
        classPathFiles.asSequence()
            .flatMap { sourceNamesFrom(it) }
            .mapNotNull { sourceName ->
                classBytesSupplierForSourceName(sourceName)?.let { Pair(sourceName, it) }
            }

    private
    fun classBytesSupplierForSourceName(sourceName: String): ClassBytesSupplier? =
        classFilePathCandidatesFor(sourceName).firstNotNullResult { classBytesSupplierForFilePath(it) }

    private
    fun classBytesSupplierForFilePath(classFilePath: String): ClassBytesSupplier? =
        classBytesIndex.firstNotNullResult { it(classFilePath) }

    private
    fun sourceNamesFrom(jarOrDir: File): Sequence<String> =
        when {
            jarOrDir.isFile -> sourceNamesFromJar(jarOrDir)
            jarOrDir.isDirectory -> sourceNamesFromDir(jarOrDir)
            else -> emptySequence()
        }

    private
    fun sourceNamesFromJar(jar: File): Sequence<String> =
        openJarFile(jar).run {
            entries().asSequence()
                .filter { it.name.isClassFilePath }
                .map { kotlinSourceNameOf(it.name) }
        }

    private
    fun sourceNamesFromDir(dir: File): Sequence<String> =
        dir.walkTopDown()
            .filter { it.name.isClassFilePath }
            .map { kotlinSourceNameOf(normaliseFileSeparators(it.relativeTo(dir).path)) }

    private
    fun classBytesIndexFor(jarOrDir: File): ClassBytesIndex =
        when {
            jarOrDir.isFile -> jarClassBytesIndexFor(jarOrDir)
            jarOrDir.isDirectory -> directoryClassBytesIndexFor(jarOrDir)
            else -> { _ -> null }
        }

    private
    fun jarClassBytesIndexFor(jar: File): ClassBytesIndex = { classFilePath ->
        openJarFile(jar).run {
            getJarEntry(classFilePath)?.let { jarEntry ->
                {
                    getInputStream(jarEntry).use { jarInput ->
                        jarInput.readBytes()
                    }
                }
            }
        }
    }

    private
    fun directoryClassBytesIndexFor(dir: File): ClassBytesIndex = { classFilePath ->
        dir.resolve(classFilePath).takeIf { it.isFile }?.let { classFile -> { classFile.readBytes() } }
    }

    private
    fun openJarFile(file: File) =
        openJars.computeIfAbsent(file, ::JarFile)

    override fun close() {
        openJars.values.forEach(JarFile::close)
    }
}


private
val String.isClassFilePath
    get() = endsWith(classFilePathSuffix)


private
const val classFilePathSuffix = ".class"


private
val slashOrDollar = Regex("[/$]")


internal
fun kotlinSourceNameOf(classFilePath: String): String =
    classFilePath.run {
        if (endsWith("Kt$classFilePathSuffix")) dropLast(8)
        else dropLast(6)
    }.replace(slashOrDollar, ".")


internal
fun classFilePathCandidatesFor(sourceName: String): Iterable<String> =
    sourceName.replace(".", "/").let { path ->
        sequenceOf("$path$classFilePathSuffix", "${path}Kt$classFilePathSuffix") +
            if (path.contains("/")) {
                val generator = { p: String ->
                    if (p.contains("/")) p.substringBeforeLast("/") + '$' + p.substring(p.lastIndexOf("/") + 1)
                    else null
                }
                generateSequence({ generator(path) }, generator)
                    .flatMap { sequenceOf("$it$classFilePathSuffix", "${it}Kt$classFilePathSuffix") }
            } else emptySequence()
    }.asIterable()