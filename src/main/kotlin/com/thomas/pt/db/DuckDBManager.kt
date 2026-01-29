package com.thomas.pt.db

import org.duckdb.DuckDBArray
import org.duckdb.DuckDBConnection
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import java.sql.DriverManager
import java.sql.ResultSet

class DuckDBManager : AutoCloseable {
    private val conn: DuckDBConnection =
        DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection

    private fun ResultSet.toDataFrame(): AnyFrame {
        val colNames = (1..metaData.columnCount).map { metaData.getColumnName(it) }
        val rows = mutableListOf<Map<String, Any?>>()
        while (next()) {
            rows.add(
                colNames.associateWith { col ->
                    val value = getObject(col)
                    value.takeIf { it !is DuckDBArray }
                        ?: ((value as DuckDBArray).array as Array<*>).toList()
                }
            )
        }
        return rows.toDataFrame()
    }

    init {
        conn.createStatement().apply {
            execute("INSTALL arrow FROM community")
            execute("LOAD arrow")
        }
    }

    fun query(sql: String): AnyFrame {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery(sql)
        return rs.toDataFrame()
    }

    fun queryScalar(sql: String): Double {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery(sql)
        return if (rs.next()) rs.getDouble(1) else 0.0
    }

    override fun close() = conn.close()
}