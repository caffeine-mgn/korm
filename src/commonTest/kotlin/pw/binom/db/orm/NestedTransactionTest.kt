package pw.binom.db.orm

import kotlinx.coroutines.test.runTest
import pw.binom.db.Connection
import pw.binom.db.DatabaseEngine
import pw.binom.db.DatabaseInfo
import pw.binom.db.PreparedStatement
import pw.binom.db.TransactionMode
import pw.binom.db.pool.ConnectionPoolImpl
import pw.binom.db.pool.PoolConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Вложенная транзакция обязана быть одной транзакцией.
 *
 * ## Какой баг это ловит
 *
 * В провижинере создание машины шло так:
 *
 * ```
 * store.inTransaction { tx ->            // BEGIN
 *     CidAllocator(tx.specs).allocate()  // leaseCid → orm.transaction
 *     tx.catalog.createInstance(...)     // INSERT → duplicate key
 * }                                      // ROLLBACK
 * ```
 *
 * `leaseCid` вызывался на сторе, который внутри себя звал `orm.transaction`
 * на **корневом** ORM. Тот брал из пула **второе** соединение и открывал
 * **свою** транзакцию. Аренда CID коммитилась сразу; внешняя падала и
 * откатывалась. Итог: `cid_lease` занят, `vm_instance` и `vm_desired` пусты,
 * клиент получил `202` и вечно висящую в `CREATING` машину.
 *
 * На Postgres это маскировалось размером пула; на SQLite с пулом в одно
 * соединение дало бы дедлок.
 *
 * ## Что проверяем
 *
 * Фейковое соединение считает `BEGIN`/`COMMIT`/`ROLLBACK` и ведёт флаг
 * `isInTransaction` — как настоящий драйвер. Если `Orm.transaction` внутри
 * уже открытой транзакции делает второй `BEGIN`, счётчик это покажет.
 */
class NestedTransactionTest {

    @Test
    fun nestedTransactionDoesNotBeginSecondTime() = runTest {
        val conn = FakeTxConnection()
        val orm = Orm(poolOf(conn), DatabaseEngine.POSTGRESQL)

        orm.transaction { outer ->
            outer.transaction { }
        }

        assertEquals(1, conn.begins, "вложенный вызов не должен открывать вторую транзакцию")
        assertEquals(1, conn.commits, "COMMIT должен быть ровно один — внешний")
        assertEquals(0, conn.rollbacks)
    }

    @Test
    fun innerFailureRollsBackOuterWork() = runTest {
        // Главное свойство: работа, сделанная во вложенном блоке, откатывается
        // вместе с внешней. До правки она уже была закоммичена.
        val conn = FakeTxConnection()
        val orm = Orm(poolOf(conn), DatabaseEngine.POSTGRESQL)

        assertFailsWith<IllegalStateException> {
            orm.transaction { outer ->
                outer.transaction { }
                error("падение после вложенного блока")
            }
        }

        assertEquals(1, conn.begins)
        assertEquals(0, conn.commits, "внешняя транзакция не должна коммититься")
        assertEquals(1, conn.rollbacks, "должен быть ровно один откат")
    }

    @Test
    fun sequentialTransactionsAreIndependent() = runTest {
        // Соседние транзакции не должны схлопываться в одну: флаг снимается
        // на COMMIT, и следующий вызов снова открывает BEGIN.
        val conn = FakeTxConnection()
        val orm = Orm(poolOf(conn), DatabaseEngine.POSTGRESQL)

        orm.transaction { }
        orm.transaction { }

        assertEquals(2, conn.begins)
        assertEquals(2, conn.commits)
    }
}

/**
 * Соединение-счётчик.
 *
 * Ведёт себя как настоящий драйвер в той части, которая важна для
 * вложенности: `beginTransaction` при открытой транзакции не начинает новую,
 * `commit`/`rollback` снимают флаг.
 */
/**
 * Пул на одно соединение.
 *
 * Не самодельная реализация [pw.binom.db.pool.ConnectionPool] (у интерфейса
 * девять членов и все нужны), а настоящий [ConnectionPoolImpl] с фабрикой,
 * всегда отдающей то же соединение. Так тест идёт тем же путём, что продакшен:
 * `withConnection` → `PooledConnection` → `isInTransaction`.
 */
private fun poolOf(conn: Connection) = ConnectionPoolImpl(
    PoolConfig(maxConnections = 1),
    factory = { conn },
)

private class FakeTxConnection : Connection {
    var begins = 0
        private set
    var commits = 0
        private set
    var rollbacks = 0
        private set

    private var inTx = false

    override val isInTransaction: Boolean get() = inTx
    override val isClosed: Boolean get() = false
    override val isReadyForQuery: Boolean get() = true
    override val transactionMode: TransactionMode get() = TransactionMode.READ_COMMITTED
    override val dbInfo: DatabaseInfo = object : DatabaseInfo {
        override val engine: DatabaseEngine get() = DatabaseEngine.POSTGRESQL
        override val tableNameQuotesStart: String get() = "\""
        override val tableNameQuotesEnd: String get() = "\""
    }

    override suspend fun beginTransaction() {
        if (inTx) return
        inTx = true
        begins++
    }

    override suspend fun commit() {
        if (!inTx) return
        inTx = false
        commits++
    }

    override suspend fun rollback() {
        if (!inTx) return
        inTx = false
        rollbacks++
    }

    override suspend fun setTransactionMode(mode: TransactionMode) = Unit
    override suspend fun prepare(sql: String): PreparedStatement = error("не используется")
    override suspend fun executeUpdate(sql: String): Long = error("не используется")
    override fun close() = Unit
}
