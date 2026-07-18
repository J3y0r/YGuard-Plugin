package me.jeyor.yguard.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import me.jeyor.yguard.config.StorageConfig
import me.jeyor.yguard.config.StorageType
import java.nio.file.Files

data class DatabaseHandle(
    val dataSource: HikariDataSource,
    val dialect: DatabaseDialect,
) : AutoCloseable {
    override fun close() {
        dataSource.close()
    }
}

object DatabaseFactory {
    fun open(config: StorageConfig): DatabaseHandle {
        val hikari = HikariConfig().apply {
            poolName = "YGuard-Database"
            connectionTimeout = 10_000
            validationTimeout = 5_000
            initializationFailTimeout = 10_000
            isAutoCommit = true
        }
        val dialect = when (config.type) {
            StorageType.SQLITE -> {
                Files.createDirectories(config.sqlite.file.parent)
                hikari.dataSourceClassName = "org.sqlite.SQLiteDataSource"
                hikari.addDataSourceProperty("url", "jdbc:sqlite:${config.sqlite.file}")
                hikari.maximumPoolSize = 1
                hikari.connectionInitSql = "PRAGMA busy_timeout=10000"
                DatabaseDialect.SQLITE
            }

            StorageType.MYSQL -> {
                hikari.dataSourceClassName = "com.mysql.cj.jdbc.MysqlDataSource"
                hikari.addDataSourceProperty("serverName", config.mysql.host)
                hikari.addDataSourceProperty("port", config.mysql.port)
                hikari.addDataSourceProperty("databaseName", config.mysql.database)
                hikari.addDataSourceProperty("user", config.mysql.username)
                hikari.addDataSourceProperty("password", config.mysql.password)
                hikari.addDataSourceProperty("characterEncoding", "UTF-8")
                hikari.addDataSourceProperty("serverTimezone", "UTC")
                hikari.addDataSourceProperty("useServerPrepStmts", true)
                hikari.addDataSourceProperty("cachePrepStmts", true)
                hikari.maximumPoolSize = 4
                DatabaseDialect.MYSQL
            }
        }
        val dataSource = HikariDataSource(hikari)
        try {
            SchemaMigrator.migrate(dataSource, dialect)
        } catch (exception: Exception) {
            dataSource.close()
            throw exception
        }
        return DatabaseHandle(dataSource, dialect)
    }
}
