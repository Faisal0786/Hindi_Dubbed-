package OttSource

import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import com.lagradost.cloudstream3.USER_AGENT
import kotlin.reflect.KClass

val JSONParser = object : ResponseParser {
    val mapper: ObjectMapper = jacksonObjectMapper()
        .configure(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            false
        )
        .configure(
            JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(),
            true
        )

    override fun <T : Any> parse(
        text: String,
        kClass: KClass<T>
    ): T = mapper.readValue(text, kClass.java)

    override fun <T : Any> parseSafe(
        text: String,
        kClass: KClass<T>
    ): T? = try {
        mapper.readValue(text, kClass.java)
    } catch (_: Exception) {
        null
    }

    override fun writeValueAsString(obj: Any): String =
        mapper.writeValueAsString(obj)
}

val app = Requests(responseParser = JSONParser).apply {
    defaultHeaders = mapOf("User-Agent" to USER_AGENT)
}

inline fun <reified T : Any> parseJson(text: String): T =
    JSONParser.parse(text, T::class)

inline fun <reified T : Any> tryParseJson(text: String): T? =
    JSONParser.parseSafe(text, T::class)

fun convertRuntimeToMinutes(runtime: String): Int {
    var totalMinutes = 0

    runtime.split(" ").forEach { part ->
        when {
            part.endsWith("h") -> {
                val hours =
                    part.removeSuffix("h").trim().toIntOrNull() ?: 0
                totalMinutes += hours * 60
            }

            part.endsWith("m") -> {
                val minutes =
                    part.removeSuffix("m").trim().toIntOrNull() ?: 0
                totalMinutes += minutes
            }
        }
    }

    return totalMinutes
}
