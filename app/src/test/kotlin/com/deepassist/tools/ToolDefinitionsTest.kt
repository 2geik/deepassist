package com.deepassist.tools

import com.deepassist.llm.DeepSeekClient
import com.google.gson.GsonBuilder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDefinitionsTest {

    @Test
    fun everyToolIsWellFormed() {
        val definitions = ToolRegistry({ null }, DeepSeekClient { "" }).getDefinitions()
        val names = definitions.map { it.function.name }
        assertEquals("duplicate tool names", names.size, names.toSet().size)
        for (d in definitions) {
            val f = d.function
            assertTrue("${f.name}: empty description", f.description.isNotBlank())
            for (r in f.parameters.required) {
                assertTrue("${f.name}: required '$r' is not a parameter", r in f.parameters.properties)
            }
        }
        // Prompt-routing checks run against exactly what the app sends
        File("build/tool_definitions.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(definitions))
    }
}
