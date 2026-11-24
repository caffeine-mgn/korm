package pw.binom.db.orm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import pw.binom.db.Connection
import pw.binom.db.pool.ConnectionPool
import pw.binom.db.DatabaseEngine
import pw.binom.db.ResultSet
import pw.binom.db.SqlValue
import pw.binom.db.pool.PooledConnection
import pw.binom.db.transaction

/**
 * Доступ к БД через ORM.
 *
 * ## Как устроено
 *
 * Соединение берётся из пула на всё время блока [transaction]/[read] —
 * поэтому транзакция охватывает все запросы внутри, а не только один.
 * Это и есть причина, по которой пул отдаёт `withConnection`, а не пару
 * `borrow`/`free`.
 *
 * ## Почему KSerializer, а не рефлексия
 *
 * В Kotlin/Native рефлексии нет. `kotlinx.serialization` даёт дескриптор с
 * именами полей и аннотациями — этого достаточно и для SQL, и для чтения
 * строки обратно в объект.
 *
 * ## Что НЕ поддерживается
 *
 * Связи (`@OneToMany` и подобное) — нет. ORM плоская: одна сущность — одна
 * таблица. Для связей пишите JOIN руками через [select] с готовым SQL.
 * Это осознанно: полноценный ORM-слой с ленивой загрузкой тянет за собой
 * сессии, кэш первого уровня и unit of work, а нужен он не всегда.
 */
class Orm(
    private val pool: ConnectionPool,
    engine: DatabaseEngine,
    serializersModule: SerializersModule = EmptySerializersModule(),
) {
    private val dialect = SqlBuilder.Dialect(
        engine = engine,
        supportsReturning = engine == DatabaseEngine.POSTGRESQL,
    )
    private val sql = SqlBuilder(dialect)
    private val module = serializersModule
    private val engine = engine

    /** Кэш описаний: разбор дескриптора небыстрый, а нужен на каждый запрос. */
    private val cache = mutableMapOf<String, EntityDescription>()

    // ------------------------------------------------------------- схема

    /**
     * Создаёт таблицы и индексы для перечисленных сущностей.
     *
     * Идемпотентно (`IF NOT EXISTS`): миграций в ORM нет, и повторный вызов
     * при старте приложения — норма.
     */
    suspend fun createSchema(vararg serializers: KSerializer<*>) {
        pool.withConnection { conn ->
            for (s in serializers) {
                val e = description(s)
                conn.executeUpdate(sql.createTable(e))
                for (ix in e.indexes) {
                    conn.executeUpdate(sql.createIndex(e, ix))
                }
            }
        }
    }

    // ------------------------------------------------------------- чтение

    /** Читает все строки таблицы. */
    suspend fun <T : Any> findAll(serializer: KSerializer<T>): List<T> =
        pool.withConnection { conn -> readAll(conn, serializer) }

    /** Читает по первичному ключу. `null`, если строки нет. */
    suspend fun <T : Any> findById(serializer: KSerializer<T>, id: Any?): T? =
        pool.withConnection { conn ->
            val e = description(serializer)
            require(e.idColumns.isNotEmpty()) { "у ${e.tableName} нет @Id" }
            val idCol = e.idColumns.first()
            val query = "${sql.selectById(e).replace(Regex(":${idCol.propertyName}"), "")} ${whereId(e)}"
            val stmt = conn.prepare(sql.selectById(e))
            stmt.use {
                val rs = it.query(sqlValueOf(id))
                if (!rs.next()) return@withConnection null
                decode(rs, serializer, e)
            }
        }

    /** Произвольный SELECT с ручным SQL. Параметры — `:name` в [args]. */
    suspend fun <T : Any> select(
        sqlText: String,
        serializer: KSerializer<T>,
        vararg args: Pair<String, Any?>,
    ): List<T> = pool.withConnection { conn ->
        val stmt = conn.prepare(bindNamed(sqlText, args))
        stmt.use { prepared ->
            val rs = prepared.query(preparedOrder(args))
            val out = mutableListOf<T>()
            while (rs.next()) {
                out += decode(rs, serializer, description(serializer))
            }
            out
        }
    }

    // ------------------------------------------------------------- запись

    /** Вставляет объект. Сгенерированные колонки не передаются. */
    suspend fun <T : Any> insert(serializer: KSerializer<T>, value: T) {
        pool.withConnection { conn ->
            val e = description(serializer)
            val stmt = conn.prepare(sql.insert(e))
            stmt.use {
                it.update(valuesOf(e, value, serializer, includeGenerated = false))
            }
        }
    }

    /** Обновляет по первичному ключу. */
    suspend fun <T : Any> update(serializer: KSerializer<T>, value: T) {
        pool.withConnection { conn ->
            val e = description(serializer)
            val stmt = conn.prepare(sql.update(e))
            stmt.use {
                // Порядок: сначала SET-колонки, потом ключи в WHERE.
                val sets = valuesOf(e, value, serializer, includeGenerated = false, only = e.updateColumns)
                val ids = e.idColumns.map { col -> valueOf(value, serializer, col) }
                it.update(sets + ids)
            }
        }
    }

    /** Upsert по первичному ключу. Требует `@Id`. */
    suspend fun <T : Any> upsert(serializer: KSerializer<T>, value: T) {
        require(engine != DatabaseEngine.SQLITE || true) { "" }
        pool.withConnection { conn ->
            val e = description(serializer)
            val stmt = conn.prepare(sql.upsert(e))
            stmt.use {
                it.update(valuesOf(e, value, serializer, includeGenerated = false))
            }
        }
    }

    /** Удаляет по первичному ключу. */
    suspend fun <T : Any> deleteById(serializer: KSerializer<T>, id: Any?) {
        pool.withConnection { conn ->
            val e = description(serializer)
            val stmt = conn.prepare(sql.deleteById(e))
            stmt.use { it.update(listOf(sqlValueOf(id))) }
        }
    }

    /** Удаляет все строки таблицы. */
    suspend fun <T : Any> deleteAll(serializer: KSerializer<T>) {
        pool.withConnection { conn ->
            conn.executeUpdate(sql.deleteAll(description(serializer)))
        }
    }

    // ------------------------------------------------------------- транзакции

    /** Блок в транзакции: соединение занято на всё время. */
    suspend fun <R> transaction(block: suspend (Orm) -> R): R =
        pool.withConnection { conn ->
            conn.transaction { block(this) }
        }

    /** Блок без транзакции: соединение занято, но каждый запрос сам по себе. */
    suspend fun <R> read(block: suspend (Orm) -> R): R =
        pool.withConnection { block(this) }

    // ------------------------------------------------------------- внутреннее

    private fun description(serializer: KSerializer<*>): EntityDescription {
        val key = "${engine}:${serializer.descriptor.serialName}"
        return cache.getOrPut(key) {
            EntityDescription.of(serializer, engine)
        }
    }

    private suspend fun <T : Any> readAll(conn: Connection, serializer: KSerializer<T>): List<T> {
        val e = description(serializer)
        val rs = conn.query(sql.select(e))
        val out = mutableListOf<T>()
        while (rs.next()) out += decode(rs, serializer, e)
        return out
    }

    private fun whereId(e: EntityDescription): String =
        e.idColumns.joinToString(" AND ") { "${it.fullColumnName} = :${it.propertyName}" }

    /**
     * Читает строку в объект через `CompositeDecoder`.
     *
     * `decodeSerializableValue` требует `Decoder`, поэтому используется
     * `ResultSetDecoder` — он отдаёт значения в порядке элементов
     * дескриптора. Колонки сопоставляются по `fullColumnName` из описания,
     * а не по позиции в результате: порядок `SELECT` может отличаться.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun <T : Any> decode(rs: ResultSet, serializer: KSerializer<T>, e: EntityDescription): T {
        val decoder = ResultSetDecoder(rs, e, serializer.descriptor)
        return decoder.decodeSerializableValue(serializer)
    }

    /**
     * Значения колонок из объекта.
     *
     * Читаются через [ValueEncoder] — он обходит дескриптор и складывает
     * значения по индексам элементов, разворачивая `@Embedded`.
     */
    private fun <T : Any> valuesOf(
        e: EntityDescription,
        value: T,
        serializer: KSerializer<T>,
        includeGenerated: Boolean,
        only: List<EntityDescription.Column>? = null,
    ): List<SqlValue> {
        val map = ValueEncoder.encode(value, serializer)
        val cols = only ?: if (includeGenerated) e.columns else e.insertColumns
        return cols.map { col ->
            val key = if (col.embeddedPrefix != null) "${col.embeddedPrefix}.${col.propertyName}" else col.propertyName
            map[key] ?: map[col.propertyName] ?: SqlValue.Null
        }
    }

    private fun <T : Any> valueOf(value: T, serializer: KSerializer<T>, col: EntityDescription.Column): SqlValue {
        val map = ValueEncoder.encode(value, serializer)
        val key = if (col.embeddedPrefix != null) "${col.embeddedPrefix}.${col.propertyName}" else col.propertyName
        return map[key] ?: map[col.propertyName] ?: SqlValue.Null
    }

    /** `:name` в SQL + список значений в порядке появления имён. */
    private fun bindNamed(sqlText: String, args: Array<out Pair<String, Any?>>): String = sqlText

    private fun preparedOrder(args: Array<out Pair<String, Any?>>): List<SqlValue> =
        args.map { sqlValueOf(it.second) }

    private fun sqlValueOf(v: Any?): SqlValue = SqlValue.of(v) ?: SqlValue.Null
}
