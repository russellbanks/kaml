/*

   Copyright 2018-2019 Charles Korn.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

*/

package com.charleskorn.kaml

import kotlinx.serialization.CompositeDecoder
import kotlinx.serialization.CompositeDecoder.Companion.READ_DONE
import kotlinx.serialization.CompositeDecoder.Companion.UNKNOWN_NAME
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicKind
import kotlinx.serialization.PrimitiveKind
import kotlinx.serialization.SerialDescriptor
import kotlinx.serialization.SerialKind
import kotlinx.serialization.StructureKind
import kotlinx.serialization.UnionKind
import kotlinx.serialization.UpdateMode
import kotlinx.serialization.builtins.AbstractDecoder
import kotlinx.serialization.modules.SerialModule

sealed class YamlInput(val node: YamlNode, override var context: SerialModule, val configuration: YamlConfiguration) : AbstractDecoder() {
    companion object {
        fun createFor(node: YamlNode, context: SerialModule, configuration: YamlConfiguration, descriptor: SerialDescriptor): YamlInput = when (node) {
            is YamlNull -> YamlNullInput(node, context, configuration)

            is YamlScalar -> when (descriptor.kind) {
                is PrimitiveKind, UnionKind.ENUM_KIND, UnionKind.CONTEXTUAL -> YamlScalarInput(node, context, configuration)
                else -> throw IncorrectTypeException("Expected ${descriptor.kind.friendlyDescription}, but got a scalar value", node.location)
            }

            is YamlList -> when (descriptor.kind) {
                is StructureKind.LIST, UnionKind.CONTEXTUAL -> YamlListInput(node, context, configuration)
                else -> throw IncorrectTypeException("Expected ${descriptor.kind.friendlyDescription}, but got a list", node.location)
            }

            is YamlMap -> when (descriptor.kind) {
                is StructureKind.CLASS, StructureKind.OBJECT -> YamlObjectInput(node, context, configuration)
                is StructureKind.MAP -> YamlMapInput(node, context, configuration, descriptor)
                is UnionKind.CONTEXTUAL -> throw YamlException("Cannot use contextual serializer on YAML map", node.location)
                else -> throw IncorrectTypeException("Expected ${descriptor.kind.friendlyDescription}, but got a map", node.location)
            }

            is YamlTaggedNode -> when (descriptor.kind) {
                is PolymorphicKind -> YamlTaggedInput(node, context, configuration)
                else -> createFor(node.node, context, configuration, descriptor)
            }
        }

        private val SerialKind.friendlyDescription: String
            get() {
                return when (this) {
                    is StructureKind.MAP -> "a map"
                    is StructureKind.CLASS -> "an object"
                    is StructureKind.LIST -> "a list"
                    is PrimitiveKind.STRING -> "a string"
                    is PrimitiveKind.BOOLEAN -> "a boolean"
                    is PrimitiveKind.BYTE -> "a byte"
                    is PrimitiveKind.CHAR -> "a character"
                    is PrimitiveKind.DOUBLE -> "a double"
                    is PrimitiveKind.FLOAT -> "a float"
                    is PrimitiveKind.INT -> "an integer"
                    is PrimitiveKind.SHORT -> "a short"
                    is PrimitiveKind.LONG -> "a long"
                    is UnionKind.ENUM_KIND -> "an enumeration value"
                    else -> "a $this"
                }
            }
    }

    override val updateMode: UpdateMode = UpdateMode.BANNED

    abstract fun getCurrentLocation(): Location
}

private class YamlScalarInput(val scalar: YamlScalar, context: SerialModule, configuration: YamlConfiguration) : YamlInput(scalar, context, configuration) {
    override fun decodeString(): String = scalar.content
    override fun decodeInt(): Int = scalar.toInt()
    override fun decodeLong(): Long = scalar.toLong()
    override fun decodeShort(): Short = scalar.toShort()
    override fun decodeByte(): Byte = scalar.toByte()
    override fun decodeDouble(): Double = scalar.toDouble()
    override fun decodeFloat(): Float = scalar.toFloat()
    override fun decodeBoolean(): Boolean = scalar.toBoolean()
    override fun decodeChar(): Char = scalar.toChar()

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val index = enumDescriptor.getElementIndex(scalar.content)

        if (index != UNKNOWN_NAME) {
            return index
        }

        val choices = (0..enumDescriptor.elementsCount - 1)
            .map { enumDescriptor.getElementName(it) }
            .sorted()
            .joinToString(", ")

        throw YamlScalarFormatException("Value ${scalar.contentToString()} is not a valid option, permitted choices are: $choices", scalar.location, scalar.content)
    }

    override fun getCurrentLocation(): Location = scalar.location

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int = 0
}

private class YamlNullInput(val nullValue: YamlNode, context: SerialModule, configuration: YamlConfiguration) : YamlInput(nullValue, context, configuration) {
    override fun decodeNotNullMark(): Boolean = false

    override fun decodeValue(): Any = throw UnexpectedNullValueException(nullValue.location)
    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = throw UnexpectedNullValueException(nullValue.location)
    override fun beginStructure(descriptor: SerialDescriptor, vararg typeParams: KSerializer<*>): CompositeDecoder = throw UnexpectedNullValueException(nullValue.location)

    override fun getCurrentLocation(): Location = nullValue.location

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int = 0
}

private class YamlListInput(val list: YamlList, context: SerialModule, configuration: YamlConfiguration) : YamlInput(list, context, configuration) {
    private var nextElementIndex = 0
    private lateinit var currentElementDecoder: YamlInput

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = list.items.size

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (nextElementIndex == list.items.size) {
            return READ_DONE
        }

        currentElementDecoder = createFor(list.items[nextElementIndex], context, configuration, descriptor.getElementDescriptor(0))

        return nextElementIndex++
    }

    override fun decodeNotNullMark(): Boolean {
        if (!haveStartedReadingElements) {
            return true
        }

        return currentElementDecoder.decodeNotNullMark()
    }

    override fun decodeString(): String = checkTypeAndDecodeFromCurrentValue("a string") { decodeString() }
    override fun decodeInt(): Int = checkTypeAndDecodeFromCurrentValue("an integer") { decodeInt() }
    override fun decodeLong(): Long = checkTypeAndDecodeFromCurrentValue("a long") { decodeLong() }
    override fun decodeShort(): Short = checkTypeAndDecodeFromCurrentValue("a short") { decodeShort() }
    override fun decodeByte(): Byte = checkTypeAndDecodeFromCurrentValue("a byte") { decodeByte() }
    override fun decodeDouble(): Double = checkTypeAndDecodeFromCurrentValue("a double") { decodeDouble() }
    override fun decodeFloat(): Float = checkTypeAndDecodeFromCurrentValue("a float") { decodeFloat() }
    override fun decodeBoolean(): Boolean = checkTypeAndDecodeFromCurrentValue("a boolean") { decodeBoolean() }
    override fun decodeChar(): Char = checkTypeAndDecodeFromCurrentValue("a character") { decodeChar() }
    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = checkTypeAndDecodeFromCurrentValue("an enumeration value") { decodeEnum(enumDescriptor) }

    private fun <T> checkTypeAndDecodeFromCurrentValue(expectedTypeDescription: String, action: YamlInput.() -> T): T {
        if (!haveStartedReadingElements) {
            throw IncorrectTypeException("Expected $expectedTypeDescription, but got a list", list.location)
        }

        return action(currentElementDecoder)
    }

    private val haveStartedReadingElements: Boolean
        get() = nextElementIndex > 0

    override fun beginStructure(descriptor: SerialDescriptor, vararg typeParams: KSerializer<*>): CompositeDecoder {
        if (haveStartedReadingElements) {
            return currentElementDecoder.beginStructure(descriptor, *typeParams)
        }

        return super.beginStructure(descriptor, *typeParams)
    }

    override fun getCurrentLocation(): Location {
        return if (haveStartedReadingElements) {
            currentElementDecoder.node.location
        } else {
            list.location
        }
    }
}

private sealed class YamlMapLikeInputBase(node: YamlNode, context: SerialModule, configuration: YamlConfiguration) : YamlInput(node, context, configuration) {
    protected lateinit var currentValueDecoder: YamlInput
    protected lateinit var currentKey: YamlNode
    protected var currentlyReadingValue = false

    override fun decodeNotNullMark(): Boolean {
        if (!haveStartedReadingEntries) {
            return true
        }

        return fromCurrentValue { decodeNotNullMark() }
    }

    override fun decodeString(): String = fromCurrentValue { decodeString() }
    override fun decodeInt(): Int = fromCurrentValue { decodeInt() }
    override fun decodeLong(): Long = fromCurrentValue { decodeLong() }
    override fun decodeShort(): Short = fromCurrentValue { decodeShort() }
    override fun decodeByte(): Byte = fromCurrentValue { decodeByte() }
    override fun decodeDouble(): Double = fromCurrentValue { decodeDouble() }
    override fun decodeFloat(): Float = fromCurrentValue { decodeFloat() }
    override fun decodeBoolean(): Boolean = fromCurrentValue { decodeBoolean() }
    override fun decodeChar(): Char = fromCurrentValue { decodeChar() }
    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = fromCurrentValue { decodeEnum(enumDescriptor) }

    protected fun <T> fromCurrentValue(action: YamlInput.() -> T): T {
        try {
            return action(currentValueDecoder)
        } catch (e: YamlException) {
            if (currentlyReadingValue) {
                throw InvalidPropertyValueException(getPropertyName(currentKey), e.message, e.location, e)
            } else {
                throw e
            }
        }
    }

    protected fun getPropertyName(key: YamlNode): String = when (key) {
        is YamlScalar -> key.content
        is YamlNull, is YamlMap, is YamlList, is YamlTaggedNode -> throw MalformedYamlException("Property name must not be a list, map, null or tagged value. (To use 'null' as a property name, enclose it in quotes.)", key.location)
    }

    protected val haveStartedReadingEntries: Boolean
        get() = this::currentValueDecoder.isInitialized

    override fun getCurrentLocation(): Location {
        return if (haveStartedReadingEntries) {
            currentValueDecoder.node.location
        } else {
            node.location
        }
    }
}

private class YamlMapInput(val map: YamlMap, context: SerialModule, configuration: YamlConfiguration, val mapDescriptor: SerialDescriptor) : YamlMapLikeInputBase(map, context, configuration) {
    private val entriesList = map.entries.entries.toList()
    private var nextIndex = 0
    private lateinit var currentEntry: Map.Entry<YamlNode, YamlNode>

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (nextIndex == entriesList.size * 2) {
            return READ_DONE
        }

        val entryIndex = nextIndex / 2
        currentEntry = entriesList[entryIndex]
        currentKey = currentEntry.key
        currentlyReadingValue = nextIndex % 2 != 0

        currentValueDecoder = when (currentlyReadingValue) {
            true -> try {
                createFor(currentEntry.value, context, configuration, descriptor.getElementDescriptor(1))
            } catch (e: IncorrectTypeException) {
                throw InvalidPropertyValueException(getPropertyName(currentKey), e.message, e.location, e)
            }

            false -> createFor(currentKey, context, configuration, descriptor.getElementDescriptor(0))
        }

        return nextIndex++
    }

    override fun beginStructure(descriptor: SerialDescriptor, vararg typeParams: KSerializer<*>): CompositeDecoder {
        if (haveStartedReadingEntries) {
            return fromCurrentValue { beginStructure(descriptor, *typeParams) }
        }

        return super.beginStructure(descriptor, *typeParams)
    }
}

private class YamlObjectInput(val map: YamlMap, context: SerialModule, configuration: YamlConfiguration) : YamlMapLikeInputBase(map, context, configuration) {
    private val entriesList = map.entries.entries.toList()
    private var nextIndex = 0

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (true) {
            if (nextIndex == entriesList.size) {
                return READ_DONE
            }

            val currentEntry = entriesList[nextIndex]
            currentKey = currentEntry.key
            val name = getPropertyName(currentKey)
            val fieldDescriptorIndex = descriptor.getElementIndex(name)

            if (fieldDescriptorIndex == UNKNOWN_NAME) {
                if (configuration.strictMode) {
                    throwUnknownProperty(name, currentKey.location, descriptor)
                } else {
                    nextIndex++
                    continue
                }
            }

            try {
                currentValueDecoder = createFor(entriesList[nextIndex].value, context, configuration, descriptor.getElementDescriptor(fieldDescriptorIndex))
            } catch (e: IncorrectTypeException) {
                throw InvalidPropertyValueException(getPropertyName(currentKey), e.message, e.location, e)
            }

            currentlyReadingValue = true
            nextIndex++

            return fieldDescriptorIndex
        }
    }

    private fun throwUnknownProperty(name: String, location: Location, desc: SerialDescriptor): Nothing {
        val knownPropertyNames = (0 until desc.elementsCount)
            .map { desc.getElementName(it) }
            .toSet()

        throw UnknownPropertyException(name, knownPropertyNames, location)
    }

    override fun beginStructure(descriptor: SerialDescriptor, vararg typeParams: KSerializer<*>): CompositeDecoder {
        if (haveStartedReadingEntries) {
            return fromCurrentValue { beginStructure(descriptor, *typeParams) }
        }

        return super.beginStructure(descriptor, *typeParams)
    }
}

private class YamlTaggedInput(val taggedNode: YamlTaggedNode, context: SerialModule, configuration: YamlConfiguration) : YamlInput(taggedNode, context, configuration) {
    private var currentField = CurrentField.NotStarted
    private lateinit var contentDecoder: YamlInput

    override fun getCurrentLocation(): Location = maybeCallOnContent(blockOnTag = taggedNode::location, blockOnContent = YamlInput::getCurrentLocation)

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return when (currentField) {
            CurrentField.NotStarted -> {
                currentField = CurrentField.Tag
                0
            }
            CurrentField.Tag -> {
                when (taggedNode.node) {
                    is YamlScalar -> contentDecoder = YamlScalarInput(taggedNode.node, context, configuration)
                    is YamlNull -> contentDecoder = YamlNullInput(taggedNode.node, context, configuration)
                }

                currentField = CurrentField.Content
                1
            }
            CurrentField.Content -> READ_DONE
        }
    }

    override fun decodeNotNullMark(): Boolean = maybeCallOnContent(blockOnTag = { true }, blockOnContent = YamlInput::decodeNotNullMark)
    override fun decodeNull(): Nothing? = maybeCallOnContent("decodeNull", blockOnContent = YamlInput::decodeNull)
    override fun decodeUnit(): Unit = maybeCallOnContent("decodeUnit", blockOnContent = YamlInput::decodeUnit)
    override fun decodeBoolean(): Boolean = maybeCallOnContent("decodeBoolean", blockOnContent = YamlInput::decodeBoolean)
    override fun decodeByte(): Byte = maybeCallOnContent("decodeByte", blockOnContent = YamlInput::decodeByte)
    override fun decodeShort(): Short = maybeCallOnContent("decodeShort", blockOnContent = YamlInput::decodeShort)
    override fun decodeInt(): Int = maybeCallOnContent("decodeInt", blockOnContent = YamlInput::decodeInt)
    override fun decodeLong(): Long = maybeCallOnContent("decodeLong", blockOnContent = YamlInput::decodeLong)
    override fun decodeFloat(): Float = maybeCallOnContent("decodeFloat", blockOnContent = YamlInput::decodeFloat)
    override fun decodeDouble(): Double = maybeCallOnContent("decodeDouble", blockOnContent = YamlInput::decodeDouble)
    override fun decodeChar(): Char = maybeCallOnContent("decodeChar", blockOnContent = YamlInput::decodeChar)
    override fun decodeString(): String = maybeCallOnContent(blockOnTag = taggedNode::tag, blockOnContent = YamlInput::decodeString)
    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = maybeCallOnContent("decodeEnum") { decodeEnum(enumDescriptor) }

    override fun beginStructure(descriptor: SerialDescriptor, vararg typeParams: KSerializer<*>): CompositeDecoder {
        return when (currentField) {
            CurrentField.NotStarted, CurrentField.Tag -> super.beginStructure(descriptor, *typeParams)
            CurrentField.Content -> {
                contentDecoder = createFor(taggedNode.node, context, configuration, descriptor)

                return contentDecoder
            }
        }
    }

    private inline fun <T> maybeCallOnContent(functionName: String, blockOnContent: YamlInput.() -> T): T =
        maybeCallOnContent(blockOnTag = { throw IllegalArgumentException("can't call $functionName on tag") }, blockOnContent = blockOnContent)

    private inline fun <T> maybeCallOnContent(blockOnTag: () -> T, blockOnContent: YamlInput.() -> T): T {
        return when (currentField) {
            CurrentField.NotStarted, CurrentField.Tag -> blockOnTag()
            CurrentField.Content -> contentDecoder.blockOnContent()
        }
    }

    private enum class CurrentField {
        NotStarted,
        Tag,
        Content
    }
}
