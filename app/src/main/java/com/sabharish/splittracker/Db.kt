package com.sabharish.splittracker

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * kind:   SPEND (card) | UPI_OUT | UPI_IN | REFUND | PAYMENT (card bill payment)
 * status: INBOX | TAGGED | IGNORED | REFUND | REVERSED | FAILED | BILLPAY | SELF | REPAID
 */
@Entity(tableName = "txn")
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bank: String,
    val card: String,          // card last 4, or bank account last digits for UPI
    val kind: String,
    val amount: Double,
    val merchant: String,      // merchant, or the other party for UPI
    val txnDate: Long,
    val smsTime: Long,
    val status: String,
    val splitAdded: Boolean = false,
    val category: String = "",
    val refundOf: Long = 0,
    val note: String = "",
    val auto: Boolean = false   // tagged automatically by a remembered rule
)

@Entity(tableName = "share", indices = [Index("txnId"), Index("person")])
data class Share(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val txnId: Long,
    val person: String,        // "Me" for your own share
    val amount: Double,
    val settled: Double
)

@Entity(tableName = "settlement")
data class Settlement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val person: String,
    val amount: Double,
    val date: Long,
    val mode: String
)

@Entity(tableName = "rule")
data class Rule(
    @PrimaryKey val ruleKey: String,
    val action: String,        // MINE | PEOPLE | CAT
    val people: String         // PEOPLE: comma list (may include "Me"); CAT: the category
)

data class PendingRow(
    val shareId: Long,
    val txnId: Long,
    val person: String,
    val amount: Double,
    val settled: Double,
    val merchant: String,
    val card: String,
    val txnDate: Long,
    val txnTotal: Double = 0.0,   // the whole bill
    val parts: Int = 1            // people sharing the bill (you included)
)

data class SummaryRow(
    val card: String,
    val txnDate: Long,
    val category: String,
    val person: String,
    val shareAmt: Double,
    val settled: Double
)

@Dao
interface AppDao {
    @Query("SELECT * FROM txn") suspend fun allTxnsList(): List<Txn>
    @Query("SELECT * FROM share") suspend fun allSharesList(): List<Share>
    @Query("SELECT * FROM settlement") suspend fun allSettlementsList(): List<Settlement>
    @Query("SELECT * FROM rule") suspend fun allRulesList(): List<Rule>
    @Query("DELETE FROM txn") suspend fun wipeTxns()
    @Query("DELETE FROM share") suspend fun wipeShares()
    @Query("DELETE FROM settlement") suspend fun wipeSettlements()
    @Query("DELETE FROM rule") suspend fun wipeRules()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreTxn(t: Txn)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreShare(s: Share)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreSettlement(s: Settlement)

    @Insert suspend fun insert(t: Txn): Long
    @Insert suspend fun insertShare(s: Share): Long
    @Insert suspend fun insertSettlement(s: Settlement)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRule(r: Rule)

    @Query("SELECT * FROM rule WHERE ruleKey = :k")
    suspend fun rule(k: String): Rule?

    @Query("DELETE FROM rule WHERE ruleKey = :k")
    suspend fun deleteRule(k: String)

    @Query("SELECT * FROM rule WHERE ruleKey LIKE 'alias|%'")
    fun aliases(): Flow<List<Rule>>

    @Query("UPDATE txn SET auto = :v WHERE id = :id")
    suspend fun setAuto(id: Long, v: Boolean)

    @Query("SELECT * FROM txn WHERE auto = 1 AND status = 'TAGGED' ORDER BY txnDate DESC, id DESC LIMIT 40")
    fun autoTagged(): Flow<List<Txn>>

    @Query("SELECT * FROM txn WHERE status = 'FAILED' ORDER BY txnDate DESC, id DESC")
    fun failed(): Flow<List<Txn>>

    @Query("SELECT * FROM txn WHERE kind = 'REFUND' AND status = 'INBOX' AND amount BETWEEN :lo AND :hi AND smsTime >= :since")
    suspend fun refundsWaiting(lo: Double, hi: Double, since: Long): List<Txn>

    @Query("SELECT COUNT(*) FROM txn WHERE kind = :kind AND amount = :amount AND card = :card AND merchant = :merchant AND smsTime BETWEEN :from AND :to")
    suspend fun near(kind: String, amount: Double, card: String, merchant: String, from: Long, to: Long): Int

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun get(id: Long): Txn?

    @Query("UPDATE txn SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("UPDATE txn SET status = :status, note = :note WHERE id = :id")
    suspend fun setStatusNote(id: Long, status: String, note: String)

    @Query("UPDATE txn SET refundOf = :orig, status = :status, note = :note WHERE id = :id")
    suspend fun setRefund(id: Long, orig: Long, status: String, note: String)

    @Query("UPDATE txn SET category = :c WHERE id = :id")
    suspend fun setCategory(id: Long, c: String)

    @Query("UPDATE txn SET splitAdded = :v WHERE id = :id")
    suspend fun setSplitAdded(id: Long, v: Boolean)

    @Query("DELETE FROM share WHERE txnId = :txnId")
    suspend fun deleteShares(txnId: Long)

    @Query("SELECT * FROM share WHERE txnId = :txnId")
    suspend fun sharesFor(txnId: Long): List<Share>

    @Query("UPDATE share SET amount = :amount WHERE id = :id")
    suspend fun setShareAmount(id: Long, amount: Double)

    @Query("SELECT * FROM txn WHERE status = 'INBOX' AND kind IN ('SPEND','UPI_OUT') ORDER BY txnDate DESC, id DESC")
    fun inbox(): Flow<List<Txn>>

    @Query("SELECT * FROM txn WHERE status = 'INBOX' AND kind IN ('UPI_IN','REFUND') ORDER BY txnDate DESC, id DESC")
    fun moneyIn(): Flow<List<Txn>>

    @Query("SELECT * FROM txn ORDER BY txnDate DESC, id DESC")
    fun all(): Flow<List<Txn>>

    @Query("SELECT * FROM share")
    fun shares(): Flow<List<Share>>

    @Query("SELECT person FROM share WHERE person != 'Me' GROUP BY person ORDER BY MAX(id) DESC LIMIT 8")
    fun recentPeople(): Flow<List<String>>

    @Query("SELECT s.id AS shareId, s.txnId AS txnId, s.person AS person, s.amount AS amount, s.settled AS settled, t.merchant AS merchant, t.card AS card, t.txnDate AS txnDate, t.amount AS txnTotal, (SELECT COUNT(*) FROM share x WHERE x.txnId = t.id AND x.amount > 0.004) AS parts FROM share s JOIN txn t ON t.id = s.txnId WHERE s.person != 'Me' AND s.settled < s.amount - 0.005 ORDER BY t.txnDate ASC")
    fun pending(): Flow<List<PendingRow>>

    @Query("SELECT s.* FROM share s JOIN txn t ON t.id = s.txnId WHERE s.person = :p AND s.settled < s.amount - 0.005 ORDER BY t.txnDate ASC")
    suspend fun openShares(p: String): List<Share>

    @Query("UPDATE share SET settled = :v WHERE id = :id")
    suspend fun setSettled(id: Long, v: Double)

    @Query("SELECT t.card AS card, t.txnDate AS txnDate, t.category AS category, s.person AS person, s.amount AS shareAmt, s.settled AS settled FROM txn t JOIN share s ON s.txnId = t.id WHERE t.kind IN ('SPEND','UPI_OUT')")
    fun summary(): Flow<List<SummaryRow>>

    @Query("SELECT * FROM txn WHERE kind IN ('SPEND','UPI_OUT') AND amount BETWEEN :lo AND :hi AND smsTime BETWEEN :from AND :to AND status NOT IN ('REVERSED','SELF','IGNORED') AND id NOT IN (SELECT refundOf FROM txn WHERE refundOf > 0) ORDER BY smsTime DESC LIMIT 12")
    suspend fun refundCandidates(lo: Double, hi: Double, from: Long, to: Long): List<Txn>

    @Query("SELECT * FROM txn WHERE kind IN ('SPEND','UPI_OUT') AND status != 'SELF' ORDER BY txnDate DESC LIMIT 80")
    suspend fun recentSpends(): List<Txn>

    @Query("SELECT * FROM txn WHERE category = '' AND kind IN ('SPEND','UPI_OUT')")
    suspend fun uncategorised(): List<Txn>
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE txn ADD COLUMN auto INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE txn ADD COLUMN category TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE txn ADD COLUMN refundOf INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE txn ADD COLUMN note TEXT NOT NULL DEFAULT ''")
    }
}

@Database(entities = [Txn::class, Share::class, Settlement::class, Rule::class], version = 3, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, Db::class.java, "splittracker.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { inst = it }
        }
    }
}
