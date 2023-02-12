package com.jiaoay.api.moshi

import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.internal.Util
import com.squareup.moshi.internal.Util.generatedAdapter
import com.squareup.moshi.internal.Util.resolve
import com.squareup.moshi.rawType
import java.lang.reflect.Modifier
import java.lang.reflect.Type
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaType

/**
 * Classes annotated with this are eligible for this adapter.
 */
private val kotlinMetadata = Metadata::class.java

/**
 * Placeholder value used when a field is absent from the JSON. Note that this code
 * distinguishes between absent values and present-but-null values.
 */
private val absentValue = Any()

/**
 * This class encodes Kotlin classes using their properties. It decodes them by first invoking the
 * constructor, and then by setting any additional properties that exist, if any.
 */
internal class NullSafeKotlinJsonAdapter<T>(
    private val constructor: KFunction<T>,
    private val allBindings: List<Binding<T, Any?>?>,
    private val nonTransientBindings: List<Binding<T, Any?>>,
    private val options: JsonReader.Options,
    private val defaultValueProviders: List<DefaultValueProvider>,
) : JsonAdapter<T>() {

    override fun fromJson(reader: JsonReader): T {
        val constructorSize = constructor.parameters.size

        // Read each value into its slot in the array.
        val values = Array<Any?>(allBindings.size) { absentValue }
        reader.beginObject()
        while (reader.hasNext()) {
            val index = reader.selectName(options)
            if (index == -1) {
                reader.skipName()
                reader.skipValue()
                continue
            }
            val binding = nonTransientBindings[index]

            val propertyIndex = binding.propertyIndex
            if (values[propertyIndex] !== absentValue) {
                throw JsonDataException(
                    "Multiple values for '${binding.property.name}' at ${reader.path}",
                )
            }

            values[propertyIndex] = binding.adapter.fromJson(reader)

            if (values[propertyIndex] == null && !binding.property.returnType.isMarkedNullable) {
                values[propertyIndex] = provideDefaultValue(binding.property.returnType)
                if (values[propertyIndex] == null) {
                    throw Util.unexpectedNull(
                        binding.property.name,
                        binding.jsonName,
                        reader,
                    )
                }
            }
        }
        reader.endObject()

        // Confirm all parameters are present, optional, or nullable.
        var isFullInitialized = allBindings.size == constructorSize
        for (i in 0 until constructorSize) {
            if (values[i] === absentValue) {
                when {
                    constructor.parameters[i].isOptional -> isFullInitialized = false
                    // Replace absent with null.
                    constructor.parameters[i].type.isMarkedNullable -> values[i] = null
                    else -> {
                        allBindings[i]?.let { binding ->
                            val result = provideDefaultValue(binding.property.returnType)
                            if (result != null) {
                                values[i] = result
                            }
                        }
                        if (values[i] === absentValue) {
                            throw Util.missingProperty(
                                constructor.parameters[i].name,
                                allBindings[i]?.jsonName,
                                reader,
                            )
                        }
                    }
                }
            }
        }

        // Call the constructor using a Map so that absent optionals get defaults.
        val result = if (isFullInitialized) {
            constructor.call(*values)
        } else {
            constructor.callBy(IndexedParameterMap(constructor.parameters, values))
        }

        // Set remaining properties.
        for (i in constructorSize until allBindings.size) {
            val binding = allBindings[i]!!
            val value = values[i]
            binding.set(result, value)
        }

        return result
    }

    private fun provideDefaultValue(kType: KType): Any? {
        defaultValueProviders.forEach {
            val result = it.provideDefaultValue(kType)
            if (result != null) {
                return result
            }
        }
        return null
    }

    override fun toJson(writer: JsonWriter, value: T?) {
        if (value == null) throw NullPointerException("value == null")

        writer.beginObject()
        for (binding in allBindings) {
            // Skip constructor parameters that aren't properties.
            if (binding == null) continue

            writer.name(binding.name)
            binding.adapter.toJson(writer, binding.get(value))
        }
        writer.endObject()
    }

    override fun toString() = "KotlinJsonAdapter(${constructor.returnType})"

    data class Binding<K, P>(
        val name: String,
        val jsonName: String?,
        val adapter: JsonAdapter<P>,
        val property: KProperty1<K, P>,
        val parameter: KParameter?,
        val propertyIndex: Int,
    ) {
        fun get(value: K) = property.get(value)

        fun set(result: K, value: P) {
            if (value !== absentValue) {
                (property as KMutableProperty1<K, P>).set(result, value)
            }
        }
    }

    /** A simple [Map] that uses parameter indexes instead of sorting or hashing. */
    class IndexedParameterMap(
        private val parameterKeys: List<KParameter>,
        private val parameterValues: Array<Any?>,
    ) : AbstractMutableMap<KParameter, Any?>() {

        override fun put(key: KParameter, value: Any?): Any? = null

        override val entries: MutableSet<MutableMap.MutableEntry<KParameter, Any?>>
            get() {
                val allPossibleEntries = parameterKeys.mapIndexed { index, value ->
                    SimpleEntry<KParameter, Any?>(value, parameterValues[index])
                }
                return allPossibleEntries.filterTo(mutableSetOf()) {
                    it.value !== absentValue
                }
            }

        override fun containsKey(key: KParameter) = parameterValues[key.index] !== absentValue

        override fun get(key: KParameter): Any? {
            val value = parameterValues[key.index]
            return if (value !== absentValue) value else null
        }
    }
}

class NullSafeKotlinJsonAdapterFactory(
    private val providers: List<DefaultValueProvider> = listOf(),
    private val useBuildInProviders: Boolean = true,
) : JsonAdapter.Factory {
    override fun create(type: Type, annotations: MutableSet<out Annotation>, moshi: Moshi):
        JsonAdapter<*>? {
        if (annotations.isNotEmpty()) return null

        val rawType = type.rawType
        if (rawType.isInterface) return null
        if (rawType.isEnum) return null
        if (!rawType.isAnnotationPresent(kotlinMetadata)) return null
        if (Util.isPlatformType(rawType)) return null
        try {
            val generatedAdapter = generatedAdapter(moshi, type, rawType)
            if (generatedAdapter != null) {
                return generatedAdapter
            }
        } catch (e: RuntimeException) {
            if (e.cause !is ClassNotFoundException) {
                throw e
            }
            // Fall back to a reflective adapter when the generated adapter is not found.
        }

        require(!rawType.isLocalClass) {
            "Cannot serialize local class or object expression ${rawType.name}"
        }
        val rawTypeKotlin = rawType.kotlin
        require(!rawTypeKotlin.isAbstract) {
            "Cannot serialize abstract class ${rawType.name}"
        }
        require(!rawTypeKotlin.isInner) {
            "Cannot serialize inner class ${rawType.name}"
        }
        require(rawTypeKotlin.objectInstance == null) {
            "Cannot serialize object declaration ${rawType.name}"
        }
        require(!rawTypeKotlin.isSealed) {
            "Cannot reflectively serialize sealed class ${rawType.name}. Please register an adapter."
        }

        val constructor = rawTypeKotlin.primaryConstructor ?: return null
        val parametersByName = constructor.parameters.associateBy { it.name }
        constructor.isAccessible = true

        val bindingsByName = LinkedHashMap<String, NullSafeKotlinJsonAdapter.Binding<Any, Any?>>()

        for (property in rawTypeKotlin.memberProperties) {
            val parameter = parametersByName[property.name]

            if (Modifier.isTransient(property.javaField?.modifiers ?: 0)) {
                require(parameter == null || parameter.isOptional) {
                    "No default value for transient constructor $parameter"
                }
                continue
            }

            require(parameter == null || parameter.type == property.returnType) {
                "'${property.name}' has a constructor parameter of type ${parameter!!.type} but a property of type ${property.returnType}."
            }

            if (property !is KMutableProperty1 && parameter == null) continue

            property.isAccessible = true
            val allAnnotations = property.annotations.toMutableList()
            var jsonAnnotation = property.findAnnotation<Json>()

            if (parameter != null) {
                allAnnotations += parameter.annotations
                if (jsonAnnotation == null) {
                    jsonAnnotation = parameter.findAnnotation()
                }
            }

            val name = jsonAnnotation?.name ?: property.name
            val resolvedPropertyType = resolve(type, rawType, property.returnType.javaType)
            val adapter = moshi.adapter<Any>(
                resolvedPropertyType,
                Util.jsonAnnotations(allAnnotations.toTypedArray()),
                property.name,
            )

            @Suppress("UNCHECKED_CAST")
            bindingsByName[property.name] = NullSafeKotlinJsonAdapter.Binding(
                name,
                jsonAnnotation?.name ?: name,
                adapter,
                property as KProperty1<Any, Any?>,
                parameter,
                parameter?.index ?: -1,
            )
        }

        val bindings = ArrayList<NullSafeKotlinJsonAdapter.Binding<Any, Any?>?>()

        for (parameter in constructor.parameters) {
            val binding = bindingsByName.remove(parameter.name)
            require(binding != null || parameter.isOptional) {
                "No property for required constructor $parameter"
            }
            bindings += binding
        }

        var index = bindings.size
        for (bindingByName in bindingsByName) {
            bindings += bindingByName.value.copy(propertyIndex = index++)
        }

        val nonTransientBindings = bindings.filterNotNull()
        val options = JsonReader.Options.of(*nonTransientBindings.map { it.name }.toTypedArray())

        val mergedProviders = mutableListOf<DefaultValueProvider>()
        mergedProviders.addAll(providers)
        if (useBuildInProviders) {
            val buildInProviders: List<DefaultValueProvider> = listOf(BuildInDefaultValueProvider())
            mergedProviders.addAll(buildInProviders)
        }
        return NullSafeKotlinJsonAdapter(
            constructor,
            bindings,
            nonTransientBindings,
            options,
            mergedProviders,
        ).nullSafe()
    }
}
