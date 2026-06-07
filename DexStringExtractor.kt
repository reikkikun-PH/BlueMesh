import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: kotlin DexStringExtractorKt <directory_or_file>")
        return
    }

    val target = File(args[0])
    if (target.isDirectory) {
        val files = target.listFiles { _, name -> name.endsWith(".dex") }
        files?.forEach { file ->
            processFile(file)
        }
    } else {
        processFile(target)
    }
}

private fun processFile(file: File) {
    println("=== Parsing file: ${file.name} ===")
    try {
        FileInputStream(file).use { fis ->
            val data = fis.readBytes()
            
            // A simple strings extractor: find sequences of 4+ printable ASCII chars (32-126)
            var start = -1
            for (i in data.indices) {
                val b = data[i]
                if (b in 32..126) {
                    if (start == -1) {
                        start = i
                    }
                } else {
                    if (start != -1) {
                        val len = i - start
                        if (len >= 4) {
                            val s = String(data, start, len, StandardCharsets.US_ASCII)
                            if (shouldPrint(s)) {
                                println(s)
                            }
                        }
                        start = -1
                    }
                }
            }
        }
    } catch (e: IOException) {
        e.printStackTrace()
    }
}

private fun shouldPrint(s: String): Boolean {
    val lower = s.lowercase()
    return s.contains("com/example/bitchat_lite") || s.contains("com.example.bitchat_lite") ||
           lower.contains("uuid") || lower.contains("static") || lower.contains("queue") || 
           lower.contains("identity")
}
