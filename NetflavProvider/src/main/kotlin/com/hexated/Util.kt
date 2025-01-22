package com.anhdaden

fun removeSquareBracketsContent(input: String): String {
    return input.replace(Regex("\\[.*?\\]"), "").trim()
}