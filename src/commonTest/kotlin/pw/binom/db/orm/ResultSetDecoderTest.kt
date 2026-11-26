package pw.binom.db.orm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import pw.binom.db.ColumnType
import pw.binom.db.DatabaseEngine
import pw.binom.db.ResultSet
import pw.binom.db.SqlValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Читает ли декодер `NULL` из resultset как `null`, а не как `""`/`0`.
 *
 * ## Зачем отдельный тест
 *
 * `agent_state` в провижинере терял `connected` на живом стенде: запись
 * возвращала `true`, в логе было «записано», а в таблице висело `NULL`.
 * Причина оказалась не в декодере (её нашёл тест на набор колонок: соседний
 * автор писал строку целиком), но именно поэтому контракт чтения обязан быть
 * зафиксирован: пока он не проверялся, `NULL`, прочитанный как `''`, был
 * равноправной версией. С ним объект уехал бы обратно в базу значением `''`,
 * и панель показывала бы «демон никогда не подключался» на живом канале.
 *
 * Раньше ни одна тестовая сущность не имела nullable-полей, и этот путь не
 * проверялся вообще.
 */
class ResultSetDecoderTest {

    private val engine = DatabaseEngine.POSTGRESQL

    private suspend fun decode(row: Map<String, SqlValue>, serializer: KSerializer<*>): Any? {
        val e = EntityDescription.of(serializer, engine)
        val rs = FakeResultSet(row)
        rs.next()
        @Suppress("UNCHECKED_CAST")
        return ResultSetDecoder(rs, e, serializer.descriptor)
            .decodeSerializableValue(serializer as KSerializer<Any>)
    }

    @Test
    fun nullColumnReadsAsNullNotEmptyString() = runBlocking {
        val entity = decode(
            mapOf(
                "key" to SqlValue.Text("k1"),
                "text" to SqlValue.Null,
                "number" to SqlValue.Null,
            ),
            NullableEntity.serializer(),
        ) as NullableEntity

        assertNull(entity.text, "NULL в текстовой колонке обязан читаться как null")
        assertNull(entity.number, "NULL в числовой колонке обязан читаться как null")
    }

    @Test
    fun nullReadsAsNullWhenFieldsHaveNoDefault() = runBlocking {
        val entity = decode(
            mapOf(
                "key" to SqlValue.Text("r1"),
                "text" to SqlValue.Null,
                "number" to SqlValue.Null,
            ),
            RequiredNullableEntity.serializer(),
        ) as RequiredNullableEntity

        assertNull(entity.text)
        assertNull(entity.number)
    }

    @Test
    fun emptyStringInDbStaysEmptyString() = runBlocking {
        // Обратная сторона: `''` — это наблюдение, а не отсутствие данных, и
        // подменять его null'ом нельзя.
        val entity = decode(
            mapOf(
                "key" to SqlValue.Text("k2"),
                "text" to SqlValue.Text(""),
                "number" to SqlValue.Int64(0),
            ),
            NullableEntity.serializer(),
        ) as NullableEntity

        assertEquals("", entity.text)
        assertEquals(0L, entity.number)
    }

    @Test
    fun filledNullableFieldsAreRead() = runBlocking {
        val entity = decode(
            mapOf(
                "key" to SqlValue.Text("k3"),
                "text" to SqlValue.Text("connected"),
                "number" to SqlValue.Int64(17L),
            ),
            NullableEntity.serializer(),
        ) as NullableEntity

        assertEquals("connected", entity.text)
        assertEquals(17L, entity.number)
    }

    @Test
    fun columnMissingFromSelectReadsAsNull() = runBlocking {
        // `select` руками может не принести колонку; молча ставить `""`
        // нельзя — на этом же объекте вызывающий сделает update.
        val entity = decode(
            mapOf("key" to SqlValue.Text("k4"), "number" to SqlValue.Null),
            NullableEntity.serializer(),
        ) as NullableEntity

        assertNull(entity.text)
    }

    /**
     * Однострочный fake курсора поверх готовых [SqlValue].
     *
     * Данные задаются так, как их вернула бы СУБД: [columns] — порядок
     * выдачи, значения текущей строки — те же пары в том же порядке.
     */
    private class FakeResultSet(
        values: Map<String, SqlValue>,
    ) : ResultSet {
        private val names = values.keys.toList()
        private val row = values.values.toList()
        private var consumed = false

        override val columns: List<String> get() = names

        override suspend fun next(): Boolean {
            if (consumed) return false
            consumed = true
            return true
        }

        private fun at(index: Int): SqlValue = row[index]

        override fun isNull(index: Int): Boolean = at(index) is SqlValue.Null

        override fun columnType(index: Int): ColumnType = when (at(index)) {
            is SqlValue.Text -> ColumnType.VARCHAR
            is SqlValue.Int64 -> ColumnType.BIGINT
            is SqlValue.Int32 -> ColumnType.INTEGER
            is SqlValue.Bool -> ColumnType.BIT
            else -> ColumnType.NULL
        }

        override fun getString(index: Int): String? = (at(index) as? SqlValue.Text)?.value
        override fun getBoolean(index: Int): Boolean? = (at(index) as? SqlValue.Bool)?.value
        override fun getByte(index: Int): Byte? = (at(index) as? SqlValue.Int8)?.value
        override fun getShort(index: Int): Short? = (at(index) as? SqlValue.Int16)?.value
        override fun getInt(index: Int): Int? = (at(index) as? SqlValue.Int32)?.value
        override fun getLong(index: Int): Long? = (at(index) as? SqlValue.Int64)?.value
        override fun getDouble(index: Int): Double? = (at(index) as? SqlValue.Float64)?.value
        override fun getBytes(index: Int): ByteArray? = (at(index) as? SqlValue.Bytes)?.value
        override fun getInstant(index: Int): kotlin.time.Instant? = (at(index) as? SqlValue.Time)?.value
        override fun getUuid(index: Int): kotlin.uuid.Uuid? = (at(index) as? SqlValue.UuidValue)?.value
        override fun close() {}
    }
}
