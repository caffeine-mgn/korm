package pw.binom.db.orm

import pw.binom.db.DatabaseEngine
import pw.binom.db.DatabaseInfo
import pw.binom.db.LockStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Блокирующее чтение: SQL под каждую стратегию.
 *
 * ## Что важнее всего проверить
 *
 * Одна и та же просьба `lock = true` должна давать **разный SQL** в разных
 * СУБД, и ни в одном случае не давать молчаливого отсутствия блокировки:
 *
 * | Стратегия | Ожидание |
 * |---|---|
 * | `ROW_LOCK` (Postgres) | `... FOR UPDATE` в конце |
 * | `TRANSACTION_LOCK` (SQLite) | суффикса нет, транзакция уже держит запись |
 * | `NONE` | исключение, а не тихое чтение без блокировки |
 *
 * Последнее — главное. Молчаливое отсутствие блокировки опаснее падения:
 * код работает, тесты проходят, а гонка вылезает в проде.
 */
class LockingSqlTest {

    private fun info(strategy: LockStrategy): DatabaseInfo = object : DatabaseInfo {
        override val engine: DatabaseEngine = when (strategy) {
            LockStrategy.TRANSACTION_LOCK -> DatabaseEngine.SQLITE
            else -> DatabaseEngine.POSTGRESQL
        }
        override val tableNameQuotesStart: String = "\""
        override val tableNameQuotesEnd: String = "\""
        override val lockStrategy: LockStrategy = strategy
    }

    // Диалект нужен и описанию сущности: типы колонок зависят от СУБД.
    private val user = EntityDescription.of(User.serializer(), DatabaseEngine.POSTGRESQL)

    private fun builder(strategy: LockStrategy): SqlBuilder {
        val i = info(strategy)
        return SqlBuilder(SqlBuilder.Dialect(engine = i.engine, quoteStart = i.tableNameQuotesStart, quoteEnd = i.tableNameQuotesEnd))
    }

    @Test
    fun `postgres добавляет FOR UPDATE`() {
        val strategy = LockStrategy.ROW_LOCK
        val d = info(strategy)
        val sql = builder(strategy).selectById(user, lock = true, dialect = d.dialect)
        assertTrue(sql.endsWith("FOR UPDATE"), "ожидался суффикс FOR UPDATE, получено: $sql")
    }

    @Test
    fun `sqlite не добавляет суффикс`() {
        val strategy = LockStrategy.TRANSACTION_LOCK
        val d = info(strategy)
        val sql = builder(strategy).selectById(user, lock = true, dialect = d.dialect)
        // FOR UPDATE в SQLite — синтаксическая ошибка, поэтому его быть не
        // должно. Блокировку даёт BEGIN IMMEDIATE, а не текст запроса.
        assertFalse(sql.contains("FOR UPDATE"), "SQLite не знает FOR UPDATE: $sql")
        assertTrue(sql.contains("WHERE"), "запрос должен остаться с условием: $sql")
    }

    @Test
    fun `стратегия NONE падает а не молчит`() {
        val strategy = LockStrategy.NONE
        val d = info(strategy)
        val e = assertFailsWith<IllegalStateException> {
            builder(strategy).selectById(user, lock = true, dialect = d.dialect)
        }
        assertTrue(e.message?.contains("блокировка") == true, "сообщение должно говорить о блокировке: ${e.message}")
    }

    @Test
    fun `без диалекта lock падает`() {
        val strategy = LockStrategy.ROW_LOCK
        // Диалект не передан — значит неизвестно, как блокировать. Молчать
        // нельзя.
        assertFailsWith<IllegalStateException> {
            builder(strategy).selectById(user, lock = true, dialect = null)
        }
    }

    @Test
    fun `lock false не меняет SQL`() {
        val strategy = LockStrategy.ROW_LOCK
        val d = info(strategy)
        val withLock = builder(strategy).selectById(user, lock = false, dialect = d.dialect)
        val plain = builder(LockStrategy.ROW_LOCK).selectById(user)
        assertEquals(plain, withLock, "без блокировки SQL должен быть прежним")
        assertFalse(withLock.contains("FOR UPDATE"))
    }

    @Test
    fun `FOR UPDATE идёт после WHERE а не перед`() {
        val strategy = LockStrategy.ROW_LOCK
        val d = info(strategy)
        val sql = builder(strategy).selectById(user, lock = true, dialect = d.dialect)
        val posWhere = sql.indexOf("WHERE")
        val posLock = sql.indexOf("FOR UPDATE")
        assertTrue(posWhere in 0 until posLock, "FOR UPDATE должен быть после WHERE: $sql")
    }

    @Test
    fun `стратегия по умолчанию NONE`() {
        // Драйвер, не заявивший стратегию, блокировку не обеспечивает.
        val plain = object : DatabaseInfo {
            override val engine = DatabaseEngine.POSTGRESQL
            override val tableNameQuotesStart = "\""
            override val tableNameQuotesEnd = "\""
        }
        assertEquals(LockStrategy.NONE, plain.lockStrategy)
        val b = SqlBuilder(SqlBuilder.Dialect(engine = plain.engine, quoteStart = plain.tableNameQuotesStart, quoteEnd = plain.tableNameQuotesEnd))
        assertFailsWith<IllegalStateException> {
            b.selectById(user, lock = true, dialect = plain.dialect)
        }
    }

    @Test
    fun `диалект ROW_LOCK возвращает FOR UPDATE`() {
        assertEquals("FOR UPDATE", info(LockStrategy.ROW_LOCK).dialect.forUpdateClause())
    }

    @Test
    fun `диалект TRANSACTION_LOCK возвращает пустую строку`() {
        assertEquals("", info(LockStrategy.TRANSACTION_LOCK).dialect.forUpdateClause())
    }

    @Test
    fun `диалект NONE возвращает пустую строку`() {
        assertEquals("", info(LockStrategy.NONE).dialect.forUpdateClause())
    }
}
