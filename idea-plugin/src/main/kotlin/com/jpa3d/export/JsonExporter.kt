package com.jpa3d.export

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule

object JsonExporter {

    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        .enable(SerializationFeature.INDENT_OUTPUT)

    fun render(model: ExportModel): String = mapper.writeValueAsString(model)
}
