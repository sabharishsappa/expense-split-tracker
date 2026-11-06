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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "txn")
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bank: String,
    val card: String,
    val kind: String,          // SPEND | REFUND | PAYMENT
    val amount: Double,
    val merchant: String,
    val txnDate: Long,
    val smsTime: Long,
    val status: String,        // INBOX | TAGGED | IGNORED | REFUND | BILLPAY
    val splitAdded: Boolean = false
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
    val action: String,        // MINE | PEOPLE
    val people: String
)

data class PendingRow(
    val shareId: Long,
    val txnId: Long,
    val person: String,
    val amount: Double,
    val settled: Double,
    val merchant: String,
    val card: String,
    val txnDate: Long
)

data class SummaryRow(
    val card: String,
    val txnDate: Long,
    val total: Double,
    val person: String,
    val shareAmt: Double,
    val settled: Double
)

@Dao
interface AppDao {
    @Insert suspend fun insert(t: Txn): Long
    @Insert suspend fun insertShare(s: Share): Long
    @Insert suspend fun insertSettlement(s: Settlement)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRule(r: Rule)

    @Query("SELECT * FROM rule WHERE ruleKey = :k")
    suspend fun rule(k: String): Rule?

    @Query("SELECT COUNT(*) FROM txn WHERE kind = :kind AND amount = :amount AND card = :card AND merchant = :merchant AND smsTime BETWEEN :from AND :to")
    suspend fun near(kind: String, amount: Double, card: String, merchant: String, from: Long, to: Long): Int

    @Query("UPDATE txn SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("UPDATE txn SET splitAdded = :v WHERE id = :id")
    suspend fun setSplitAdded(id: Long, v: Boolean)

    @Query("SELECT * FROM txn WHERE status = 'INBOX' AND kind = 'SPEND' ORDER BY txnDate DESC")
    fun inbox(): Flow<List<Txn>>

    @Query("SELECT * FROM txn ORDER BY txnDate DESC, id DESC")
    fun all(): Flow<List<Txn>>

    @Query("SELECT * FROM share")
    fun shares(): Flow<List<Share>>

    @Query("SELECT s.id AS shareId, s.txnId AS txnId, s.person AS person, s.amount AS amount, s.settled AS settled, t.merchant AS merchant, t.card AS card, t.txnDate AS txnDate FROM share s JOIN txn t ON t.id = s.txnId WHERE s.person != 'Me' AND s.settled < s.amount - 0.005 ORDER BY t.txnDate ASC")
    fun pending(): Flow<List<PendingRow>>

    @Query("SELECT s.* FROM share s JOIN txn t ON t.id = s.txnId WHERE s.person = :p AND s.settled < s.amount - 0.005 ORDER BY t.txnDate ASC")
    suspend fun openShares(p: String): List<Share>

    @Query("UPDATE share SET settled = :v WHERE id = :id")
    suspend fun setSettled(id: Long, v: Double)

    @Query("SELECT t.card AS card, t.txnDate AS txnDate, t.amount AS total, s.person AS person, s.amount AS shareAmt, s.settled AS settled FROM txn t JOIN share s ON s.txnId = t.id WHERE t.kind = 'SPEND'")
    fun summary(): Flow<List<SummaryRow>>

    @Query("SELECT * FROM settlement ORDER BY date DESC")
    fun settlements(): Flow<List<Settlement>>
}

@Database(entities = [Txn::class, Share::class, Settlement::class, Rule::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, Db::class.java, "splittracker.db").build().also { inst = it }
        }
    }
}
