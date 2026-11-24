package pw.binom.db.orm

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import pw.binom.db.ResultSet
import pw.binom.db.SqlValue

/**
 * Читает строку [ResultSet] в объект.
 *
 * ## Как сопоставляются колонки
 *
 * По полному имени из [EntityDescription] (с учётом `@Embedded`-префикса), а
 * не по позиции в результате. Причина: `SELECT` может быть написан руками и
 * вернуть колонки в другом порядке или с лишними (`SELECT *`, JOIN). Порядок
 * `SerialDescriptor` — про класс, а не про запрос.
 *
 * ## Почему значения читаются по `SerialKind`
 *
 * `ResultSet.getValue` определяет тип по `ColumnType` СУБД, а нам нужно то,
 * что ждёт поле класса. Например, `Instant` приходит из Postgres строкой,
 * и разбирать его надо как строку, а не как `TIMESTAMP`-колонку.
 */
internal class ResultSetDecoder(
    private val rs: ResultSet,
    private val entity: EntityDescription,
    private val root: SerialDescriptor,
) : AbstractDecoder() {

    override val serializersModule: SerializersModule = EmptySerializersModule()

    /**
     * Дескриптор корня.
     *
     * Не `override`: `AbstractDecoder` не объявляет `descriptor` — он есть
     * только у `Decoder` через `beginStructure`. Хранится для чтения имён
     * при инициализации вложенных структур.
     */
    private val rootDescriptor: SerialDescriptor = root

    private var index: Int = -1

    /** Стек имён для `@Embedded`, как в [ValueEncoder]. */
    private val path = ArrayDeque<String>()

    private var pendingName: String? = null

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        index++
        if (index >= descriptor.elementsCount) return CompositeDecoder.DECODE_DONE
        // Имя запоминаем сразу: следующий вызов будет либо scalar-decode,
        // либо beginStructure для вложенного объекта.
        pendingName = descriptor.getElementName(index)
        return index
    }

    /**
     * Вложенная структура: имя поля уходит в путь, значения ищутся с
     * префиксом. Симметрично [ValueEncoder].
     */
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val here = pendingName
        pendingName = null
        if (here != null) path.addLast(here)
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        if (path.isNotEmpty()) path.removeLast()
        pendingName = null
    }

    override fun decodeNotNullMark(): Boolean = !isNull()

    override fun decodeNull(): Nothing? = null

    override fun decodeBoolean(): Boolean = (read(PrimitiveKind.BOOLEAN) as? SqlValue.Bool)?.value ?: false
    override fun decodeByte(): Byte = (read(PrimitiveKind.BYTE) as? SqlValue.Int8)?.value ?: 0
    override fun decodeShort(): Short = (read(PrimitiveKind.SHORT) as? SqlValue.Int16)?.value ?: 0
    override fun decodeInt(): Int = (read(PrimitiveKind.INT) as? SqlValue.Int32)?.value ?: 0
    override fun decodeLong(): Long = (read(PrimitiveKind.LONG) as? SqlValue.Int64)?.value ?: 0
    override fun decodeFloat(): Float = (read(PrimitiveKind.FLOAT) as? SqlValue.Float32)?.value ?: 0f
    override fun decodeDouble(): Double = (read(PrimitiveKind.DOUBLE) as? SqlValue.Float64)?.value ?: 0.0
    override fun decodeString(): String = (read(PrimitiveKind.STRING) as? SqlValue.Text)?.value ?: ""
    override fun decodeChar(): Char = decodeString().firstOrNull() ?: ' '

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val name = decodeString()
        for (i in 0 until enumDescriptor.elementsCount) {
            if (enumDescriptor.getElementName(i) == name) return i
        }
        return 0
    }

    /** Имя колонки текущего элемента, вычисляемое по индексу и пути. */
    private fun currentColumn(): String? {
        val name = pendingName ?: return null
        return if (path.isEmpty()) name else path.joinToString(".") + "." + name
    }

    private fun columnIndex(): Int? {
        val full = currentColumn() ?: return null
        val col = entity.columns.firstOrNull { it.propertyName == full }
            ?: entity.columns.firstOrNull { it.fullColumnName == full }
            ?: return null
        return runCatching { rs.columnIndex(col.fullColumnName) }.getOrNull()
    }

    private fun isNull(): Boolean {
        val idx = columnIndex() ?: return true
        return rs.isNull(idx)
    }

    /**
     * Читает значение по ожидаемому [kind].
     *
     * `pendingName` обновляется из [decodeElementIndex] не здесь, поэтому
     * имя берётся из дескриптора по текущему индексу: `decodeElementIndex`
     * уже вызван, и `index` указывает на нужный элемент.
     */
    private fun read(kind: SerialKind): SqlValue? {
        val idx = columnIndex() ?: return null
        if (rs.isNull(idx)) return SqlValue.Null

        return when (kind) {
            PrimitiveKind.BOOLEAN -> rs.getBoolean(idx)?.let { SqlValue.Bool(it) }
            PrimitiveKind.BYTE -> rs.getByte(idx)?.let { SqlValue.Int8(it) }
            PrimitiveKind.SHORT -> rs.getShort(idx)?.let { SqlValue.Int16(it) }
            PrimitiveKind.INT -> rs.getInt(idx)?.let { SqlValue.Int32(it) }
            PrimitiveKind.LONG -> rs.getLong(idx)?.let { SqlValue.Int64(it) }
            PrimitiveKind.FLOAT -> rs.getFloat(idx)?.let { SqlValue.Float32(it) }
            PrimitiveKind.DOUBLE -> rs.getDouble(idx)?.let { SqlValue.Float64(it) }
            // Всё остальное — текстом: строки, время, UUID, enum.
            else -> rs.getString(idx)?.let { SqlValue.Text(it) } ?: SqlValue.Null
        }
    }
}
