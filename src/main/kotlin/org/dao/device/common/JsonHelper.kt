package org.dao.device.common

import com.fasterxml.jackson.core.Version
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.kotlinModule

object JsonHelper {

  val mapper = ObjectMapper()

  init {
    val simpleModule = SimpleModule("SimpleModule", Version(1, 0, 0, null, "", ""))
    mapper.registerModule(simpleModule)
    // simpleModule.addSerializer(ObjectId::class.java, ObjectIdSerializer())
    // simpleModule.addDeserializer(ObjectId::class.java, ObjectIdDeserializer())
    mapper.registerModule(
      kotlinModule {
        configure(KotlinFeature.NullIsSameAsDefault, true)
      },
    )
    mapper.registerModule(JavaTimeModule())
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    // 允许未转义的控制字符，如 \n
    mapper.configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
  }

  // TODO 这样捕获，后面难以判定是 JsonProcessingException
  fun writeValueAsString(v: Any?): String {
    if (v == null) return ""
    if (v is String) return v
    return mapper.writeValueAsString(v)
  }

  fun <T> clone(v: T, valueTypeRef: TypeReference<T>): T {
    val s = mapper.writeValueAsString(v)
    return mapper.readValue(s, valueTypeRef)
  }

  fun writeToTree(v: Any?): JsonNode? {
    if (v == null) return v
    val str = mapper.writeValueAsString(v)
    return mapper.readTree(str)
  }
}