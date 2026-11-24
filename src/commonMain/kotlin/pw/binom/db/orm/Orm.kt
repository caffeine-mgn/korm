package pw.binom.db.orm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import pw.binom.db.Connection
import pw.binom.db.DatabaseEngine
import pw.binom.db.ResultSet
import pw.binom.db.SqlValue
import pw.binom.db.pool.ConnectionPool
import pw.binom.db.transaction

/**
 * Доступ к БД через ORM.
 *
 * ## Привязанное соединение
 *
 * Внутри [transaction]/[read] методы работают с уже выданным соединением, а
 * не берут новое из пула. Это не оптимизация, а условие работоспособности:
 * без него `transaction { orm -> orm.findAll() }` занимает одно соединение
 * снаружи и просит второе внутри — при `maxConnections = 1` это вечный
 * deadlock, а при большем потолке — просто разбазаривание соединений.
 *
 * Реализовано двумя экземплярами: обычный [Orm] смотрит в пул, а тот, что
 * приходит в блок, держит [bound] и в пул не обращается. Никаких
 * `ThreadLocal` и переменных в корутинном контексте: соединение
 * передаётся явно, и его видно в типе.
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
 * таблица. Для JOIN пишите SQL руками через [select].
 */
class Orm private constructor(
    /** Пул. `null`, если соединение привязано. */
    private val pool: ConnectionPool?,
    /** Привязанное соединение. `null`, если работаем через пул. */
    private val bound: Connection?,
    private val engine: DatabaseEngine,
) {
    /**
     * Основной конструктор: ORM поверх пула.
     *
     * Пул не `null` здесь и не `null` в [bound] — ровно одно из двух, и
     * private-конструктор гарантирует, что другого не будет.
     */
    constructor(pool: ConnectionPool, engine: DatabaseEngine) : this(pool, null, engine)

    private val dialect = SqlBuilder.Dialect(
        engine = engine,
        supportsReturning = engine == DatabaseEngine.POSTGRESQL,
    )
    private val sql = SqlBuilder(dialect)

    /** Кэш описаний: разбор дескриптора небыстрый, а нужен на каждый запрос. */
    private val cache = mutableMapOf<String, EntityDescription>()

    /**
     * Выполняет блок с соединением: привязанным или из пула.
     *
     * Единственное место, где решается этот выбор. Все методы ниже идут
     * через него, поэтому вложенный вызов внутри транзакции всегда попадает
     * в то же соединение.
     */
    private suspend fun <R> withConn(block: suspend (Connection) -> R): R {
        val b = bound
        if (b != null) return block(b)
        val p = pool ?: error("ни пул, ни соединение не заданы")
        return p.withConnection(block)
    }

    // ------------------------------------------------------------- схема

    /**
     * Создаёт таблицы и индексы для перечисленных сущностей.
     *
     * Идемпотентно (`IF NOT EXISTS`): миграций в ORM нет, и повторный вызов
     * при старте приложения — норма.
     */
    suspend fun createSchema(vararg serializers: KSerializer<*>) {
        withConn { conn ->
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
    suspend fun <T : Any> findAll(serializer: KSerializer<T>): List<T> = withConn { conn ->
        val e = description(serializer)
        val rs = conn.query(sql.select(e))
        val out = mutableListOf<T>()
        while (rs.next()) out += decode(rs, serializer, e)
        out
    }

    /** Читает по первичному ключу. `null`, если строки нет. */
    suspend fun <T : Any> findById(serializer: KSerializer<T>, id: Any?): T? = withConn { conn ->
        val e = description(serializer)
        require(e.idColumns.isNotEmpty()) { "у ${e.tableName} нет @Id" }
        val stmt = conn.prepare(sql.selectById(e))
        stmt.use {
            val rs = it.query(listOf(sqlValueOf(id)))
            if (!rs.next()) return@withConn null
            decode(rs, serializer, e)
        }
    }

    /**
     * Произвольный SELECT.
     *
     * Параметры — `:name` в тексте и пары `"name" to значение`. Порядок пар
     * задаёт порядок позиций: конвертер драйвера переставит их сам, если
     * имя встречается в запросе несколько раз.
     */
    suspend fun <T : Any> select(
        sqlText: String,
        serializer: KSerializer<T>,
        vararg args: Pair<String, Any?>,
    ): List<T> = withConn { conn ->
        val stmt = conn.prepare(sqlText)
        stmt.use { prepared ->
            val rs = prepared.query(args.map { sqlValueOf(it.second) })
            val e = description(serializer)
            val out = mutableListOf<T>()
            while (rs.next()) out += decode(rs, serializer, e)
            out
        }
    }

    // ------------------------------------------------------------- запись

    /** Вставляет объект. Сгенерированные колонки не передаются. */
    suspend fun <T : Any> insert(serializer: KSerializer<T>, value: T) {
        withConn { conn ->
            val e = description(serializer)
            val stmt = conn.prepare(sql.insert(e))
            stmt.use { it.update(valuesOf(e, value, serializer, e.insertColumns)) }
        }
    }

    /** Обновляет по первичному ключу. */
    suspend fun <T : Any> update(serializer: KSerializer<T>, value: T) {
        withConn { conn ->
            val e = description(serializer)
            val stmt = conn.prepare(sql.update(e))
            stmt.use {
                // Порядок: сначала SET-колонки, потом ключи в WHERE.
                val sets = valuesOf(e, value, serializer, e.updateColumns)
                val ids = e.idColumns.map { col -> valueOf(value, serializer, col) }
                it.update(sets + ids)
            }
        }
    }

    /**
     * Вставляет или обновляет по первичному ключу.
     *
     * Колонка ключа передаётся **всегда**, даже если она `@AutoGenerated`:
     * без неё `INSERT` не даст конфликта, `ON CONFLICT` не сработает, и
     * вместо обновления появится новая строка с новым ключом. Значение `0`
     * трактуется как «новый объект» — см. [upsertAuto].
     */
    suspend fun <T : Any> upsert(serializer: KSerializer<T>, value: T) {
        withConn { conn ->
            val e = description(serializer)
            // Набор колонок в SQL и набор значений обязаны совпадать:
            // расхождение сдвигает параметры и даёт неверный тип.
            val cols = upsertColumns(e)
            val stmt = conn.prepare(sql.upsert(e, insertColumns = cols))
            stmt.use { it.update(valuesOf(e, value, serializer, cols)) }
        }
    }

    /**
     * Upsert для сущности с авто-ключом.
     *
     * Если ключ не задан (`0` или `null`), выполняется обычный `insert`:
     * иначе `ON CONFLICT (id = 0)` создал бы строку с нулевым ключом.
     * Возвращает `true`, если строка была вставлена.
     */
    suspend fun <T : Any> upsertAuto(serializer: KSerializer<T>, value: T): Boolean {
        val e = description(serializer)
        val idCol = e.idColumns.firstOrNull() ?: error("upsertAuto требует @Id у ${e.tableName}")
        val idValue = valueOf(value, serializer, idCol)
        val isNew = when (idValue) {
            SqlValue.Null -> true
            is SqlValue.Int64 -> idValue.value == 0L
            is SqlValue.Int32 -> idValue.value == 0
            else -> false
        }
        if (isNew) {
            insert(serializer, value)
            return true
        }
        upsert(serializer, value)
        return false
    }

    /** Удаляет по первичному ключу. */
    suspend fun <T : Any> deleteById(serializer: KSerializer<T>, id: Any?) {
        withConn { conn ->
            val e = description(serializer)
            val stmt = conn.prepare(sql.deleteById(e))
            stmt.use { it.update(listOf(sqlValueOf(id))) }
        }
    }

    /** Удаляет все строки таблицы. */
    suspend fun <T : Any> deleteAll(serializer: KSerializer<T>) {
        withConn { conn ->
            conn.executeUpdate(sql.deleteAll(description(serializer)))
        }
    }

    // ------------------------------------------------------------- транзакции

    /**
     * Блок в транзакции.
     *
     * В блок приходит `Orm` с привязанным соединением: все запросы внутри
     * идут в ту же транзакцию. Соединение освобождается в конце.
     */
    suspend fun <R> transaction(block: suspend (Orm) -> R): R =
        withConn { conn ->
            conn.transaction { block(Orm(null, conn, engine)) }
        }

    /**
     * Блок без транзакции, но с одним соединением на всё время.
     *
     * Нужен, когда запросов несколько и важна их последовательность в одном
     * соединении (например, `createSchema` плюс проверка), но атомарность не
     * требуется.
     */
    suspend fun <R> read(block: suspend (Orm) -> R): R =
        withConn { conn -> block(Orm(null, conn, engine)) }

    // ------------------------------------------------------------- внутреннее

    private fun description(serializer: KSerializer<*>): EntityDescription {
        val key = "${engine}:${serializer.descriptor.serialName}"
        return cache.getOrPut(key) { EntityDescription.of(serializer.descriptor, engine) }
    }

    /**
     * Колонки для `upsert`.
     *
     * Отличие от `insertColumns` только в ключе: `@AutoGenerated` ключ
     * обязан присутствовать, иначе конфликта не будет.
     */
    private fun upsertColumns(e: EntityDescription): List<EntityDescription.Column> =
        e.columns.filter { it.isId || !it.autoGenerated }

    /** Читает строку в объект через [ResultSetDecoder]. */
    private fun <T : Any> decode(rs: ResultSet, serializer: KSerializer<T>, e: EntityDescription): T =
        ResultSetDecoder(rs, e, serializer.descriptor).decodeSerializableValue(serializer)

    /**
     * Значения указанных колонок из объекта.
     *
     * [ValueEncoder] отдаёт карту по путям (`addr.city`), а колонки описаны
     * с префиксом из `@EmbeddedPrefix`. Ключи не совпадают намеренно:
     * префикс в БД и имя поля в объекте — разные вещи, и склеивать их здесь
     * значило бы требовать совпадения.
     */
    private fun <T : Any> valuesOf(
        e: EntityDescription,
        value: T,
        serializer: KSerializer<T>,
        cols: List<EntityDescription.Column>,
    ): List<SqlValue> {
        val map = ValueEncoder.encode(value, serializer)
        // Ключ — [EntityDescription.Column.objectPath]: `address.city`. Это
        // путь в объекте, а не имя колонки: `@EmbeddedPrefix("home_")` даёт
        // колонку `home_city`, но поле в классе остаётся `address.city`.
        return cols.map { col -> map[col.objectPath] ?: SqlValue.Null }
    }

    private fun <T : Any> valueOf(value: T, serializer: KSerializer<T>, col: EntityDescription.Column): SqlValue {
        val map = ValueEncoder.encode(value, serializer)
        return map[col.objectPath] ?: SqlValue.Null
    }

    private fun sqlValueOf(v: Any?): SqlValue = SqlValue.of(v) ?: SqlValue.Null
}
