package com.example.util

object TablePrinter {
    fun printTable(
        title: String,
        headers: List<String>,
        rows: List<List<Any>>
    ) {
        if (title.isNotEmpty()) {
            println("\n📊 $title")
        }
        if (rows.isEmpty()) {
            println("(No records to display)")
            return
        }

        // Calculate max widths for each column
        val colWidths = headers.indices.map { col ->
            maxOf(
                headers[col].length,
                rows.maxOf { row -> row.getOrNull(col)?.toString()?.length ?: 0 }
            )
        }

        // Print header
        val headerLine = headers.indices.map { col ->
            headers[col].padEnd(colWidths[col])
        }.joinToString(" | ")

        val separatorLine = colWidths.map { width ->
            "-".repeat(width)
        }.joinToString("-+-")

        println(headerLine)
        println(separatorLine)

        // Print rows
        for (row in rows) {
            val rowLine = headers.indices.map { col ->
                row.getOrNull(col)?.toString().orEmpty().padEnd(colWidths[col])
            }.joinToString(" | ")
            println(rowLine)
        }
    }
}
