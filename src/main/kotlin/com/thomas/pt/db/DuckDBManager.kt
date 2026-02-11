package com.thomas.pt.db

import org.duckdb.DuckDBConnection
import java.sql.DriverManager

class DuckDBManager : AutoCloseable {
    private val conn: DuckDBConnection =
        DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection

    init {
        conn.createStatement().apply {
            execute("INSTALL arrow FROM community")
            execute("LOAD arrow")
        }
    }

    fun queryScalar(sql: String): Double
        = conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                if (rs.next()) rs.getDouble(1) else 0.0
            }
        }

    override fun close() = conn.close()
}