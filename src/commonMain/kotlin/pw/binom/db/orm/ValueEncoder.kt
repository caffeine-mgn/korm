package pw.binom.db.orm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import pw.binom.db.SqlValue

/**
 * Извлекает значения полей объекта в [SqlValue].
 *
 * ## Почему свой энкодер
 *
 * `kotlinx.serialization` пишет в форматы (JSON, CBOR, Protobuf), а нам нужен
 * плоский набор значений по именам колонок. `AbstractEncoder` даёт
 * минимальный набор методов — их и реализуем.
 *
 * ## Как ведётся путь
 *
 * У энкодера один изменяемый указатель [path] — стек имён от корня. При
 * входе в структуру он дополняется именем поля, при выходе — усекается.
 * Значение кладётся по полному пути через точку: `addr.city`. Это
 * единственный способ развести `@Embedded`, у которого поля могут совпадать
 * с полями родителя.
 *
 * ## Почему ключ — путь, а не просто имя
 *
 * Два вложенных объекта с полем `name` (`addr.name` и `person.name`) дали бы
 * коллизию. Путь её исключает.
 */
internal object ValueEncoder {

    fun <T : Any> encode(value: T, serializer: KSerializer<T>): Map<String, SqlValue> {
        val out = LinkedHashMap<String, SqlValue>()
        val encoder = FlatEncoder(out)
        encoder.encodeSerializableValue(serializer, value)
        return out
    }

    private class FlatEncoder(
        private val out: MutableMap<String, SqlValue>,
    ) : AbstractEncoder() {

        override val serializersModule: SerializersModule = EmptySerializersModule()

        /** Стек имён: `["addr", "city"]` → ключ `addr.city`. */
        private val path = ArrayDeque<String>()

        private var pendingName: String? = null

        private fun key(): String? {
            val name = pendingName ?: return null
            return if (path.isEmpty()) name else path.joinToString(".") + "." + name
        }

        private fun put(v: SqlValue) {
            val k = key() ?: return
            out[k] = v
        }

        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            pendingName = descriptor.getElementName(index)
            return true
        }

        /**
         * Вложенная структура: имя поля кладётся в путь, значения идут с
         * префиксом. Без этого `@Embedded` затирал бы поля родителя.
         */
        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
            val d = serializer.descriptor
            if (d.kind == StructureKind.CLASS || d.kind == StructureKind.OBJECT) {
                val here = pendingName
                pendingName = null
                if (here != null) {
                    path.addLast(here)
                }
                serializer.serialize(this, value)
                if (here != null) path.removeLast()
                return
            }
            serializer.serialize(this, value)
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder = this

        override fun endStructure(descriptor: SerialDescriptor) {
            pendingName = null
        }

        override fun encodeNull() = put(SqlValue.Null)
        override fun encodeBoolean(value: Boolean) = put(SqlValue.Bool(value))
        override fun encodeByte(value: Byte) = put(SqlValue.Int8(value))
        override fun encodeShort(value: Short) = put(SqlValue.Int16(value))
        override fun encodeInt(value: Int) = put(SqlValue.Int32(value))
        override fun encodeLong(value: Long) = put(SqlValue.Int64(value))
        override fun encodeFloat(value: Float) = put(SqlValue.Float32(value))
        override fun encodeDouble(value: Double) = put(SqlValue.Float64(value))
        override fun encodeString(value: String) = put(SqlValue.Text(value))
        override fun encodeChar(value: Char) = put(SqlValue.Text(value.toString()))

        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) =
            put(SqlValue.Text(enumDescriptor.getElementName(index)))
    }
}
