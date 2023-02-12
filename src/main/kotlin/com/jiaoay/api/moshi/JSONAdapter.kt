package com.jiaoay.api.moshi

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonAdapter.Factory
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.lang.reflect.Type

class JSONAdapter(
    moshi: Moshi,
    keyType: Type,
    valueType: Type
) : JsonAdapter<JSONObject?>() {

    private val keyAdapter: JsonAdapter<String> = moshi.adapter(keyType)
    private val valueAdapter: JsonAdapter<Any> = moshi.adapter(valueType)

    companion object {
        val FACTORY: Factory = Factory { type, annotations, moshi ->
            val rawType = Types.getRawType(type)
            if (annotations.isNotEmpty()) {
                return@Factory null
            }
            if (rawType != JSONObject::class.java) {
                return@Factory null
            }
            val keyAndValue = if (type === java.util.Properties::class.java) {
                arrayOf<Type>(
                    String::class.java,
                    String::class.java
                )
            } else {
                arrayOf<Type>(Any::class.java, Any::class.java)
            }
            JSONAdapter(
                moshi,
                keyAndValue[0],
                keyAndValue[1]
            ).nullSafe()
        }
    }

    @Throws(IOException::class)
    override fun toJson(writer: JsonWriter, value: JSONObject?) {
        writer.beginObject()
        value?.keys()?.forEach { key ->
            val v = if (value.isNull(key).not()) {
                value.opt(key)
            } else {
                ""
            }
            writer.promoteValueToName()
            keyAdapter.toJson(writer, key)
            if (v is JSONArray) {
                writer.beginArray()
                for (index in 0 until v.length()) {
                    valueAdapter.toJson(writer, v.get(index))
                }
                writer.endArray()
            } else {
                valueAdapter.toJson(writer, v)
            }
        }
        writer.endObject()
    }

    @Throws(IOException::class)
    override fun fromJson(reader: JsonReader): JSONObject {
        val result = JSONObject()
        reader.beginObject()
        while (reader.hasNext()) {
            reader.promoteNameToValue()
            val name = keyAdapter.fromJson(reader)
            if (name == null) {
                throw JsonDataException(
                    "JSONObject key:$name is null."
                )
            }
            val value = valueAdapter.fromJson(reader)
            result.put(name, value)
        }
        reader.endObject()
        return result
    }

    override fun toString(): String {
        return "JsonAdapter($keyAdapter=$valueAdapter)"
    }
}
