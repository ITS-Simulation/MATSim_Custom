package com.thomas.pt.data.processor

import org.duckdb.DuckDBConnection
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import java.sql.DriverManager
import java.sql.ResultSet

class DuckDBHelper : AutoCloseable {
    private val conn: DuckDBConnection =
        DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection

    private fun ResultSet.toDataFrame(): AnyFrame {
        val meta = metaData
        val colNames = (1..meta.columnCount).map { meta.getColumnName(it) }
        val rows = mutableListOf<Map<String, Any?>>()
        while (next()) {
            rows.add(colNames.associateWith { getObject(it) })
        }
        return rows.toDataFrame()
    }

    init {
        // Install and load Arrow extension for read_arrow() support
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
