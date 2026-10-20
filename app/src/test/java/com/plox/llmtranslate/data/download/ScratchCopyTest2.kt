package com.plox.llmtranslate.data.download

import org.junit.Test
import java.io.File
import java.nio.file.Files

class ScratchCopyTest2 {
    @Test
    fun probe() {
        val root = Files.createTempDirectory("probe5").toFile()
        val subdir = File(root, "m1/pack").apply { mkdirs() }
        File(subdir, "conflict.bin").writeText("c")
        val rootDir = File(root, "m1/conflict.bin").apply { mkdirs(); resolve("inner").writeText("i") }
        println("rename=" + File(subdir, "conflict.bin").renameTo(rootDir))
        val r = runCatching { File(subdir, "conflict.bin").copyRecursively(rootDir, overwrite = true) }
        println("copy=" + r)
        root.deleteRecursively()
    }
}
