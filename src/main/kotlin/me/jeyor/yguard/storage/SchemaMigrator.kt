package me.jeyor.yguard.storage

import javax.sql.DataSource

object SchemaMigrator {
    private const val CURRENT_VERSION = 1

    fun migrate(dataSource: DataSource, dialect: DatabaseDialect) {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
                }
                val version = connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT version FROM schema_version").use { result ->
                        if (result.next()) result.getInt(1) else 0
                    }
                }
                if (version > CURRENT_VERSION) {
                    throw IllegalStateException("Database schema version $version is newer than supported version $CURRENT_VERSION")
                }
                if (version < 1) {
                    createVersionOne(connection, dialect)
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("DELETE FROM schema_version")
                        statement.executeUpdate("INSERT INTO schema_version(version) VALUES (1)")
                    }
                }
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    private fun createVersionOne(connection: java.sql.Connection, dialect: DatabaseDialect) {
        val statements = when (dialect) {
            DatabaseDialect.SQLITE -> sqliteVersionOne
            DatabaseDialect.MYSQL -> mysqlVersionOne
        }
        connection.createStatement().use { statement ->
            statements.forEach(statement::executeUpdate)
        }
    }

    private val sqliteVersionOne = listOf(
        """
        CREATE TABLE IF NOT EXISTS bans (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            subject_type TEXT NOT NULL,
            subject_value TEXT NOT NULL COLLATE BINARY,
            source_detection TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            created_by TEXT NOT NULL,
            lifted_at INTEGER NULL,
            lifted_by TEXT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_bans_active_subject ON bans(subject_type, subject_value, lifted_at)",
        """
        CREATE TABLE IF NOT EXISTS audit_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_uuid TEXT NULL,
            username_snapshot TEXT NULL,
            session_id TEXT NULL,
            detection_type TEXT NOT NULL,
            action_name TEXT NOT NULL,
            details TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_audit_player_created ON audit_events(player_uuid, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_audit_session ON audit_events(session_id)",
    )

    private val mysqlVersionOne = listOf(
        """
        CREATE TABLE IF NOT EXISTS bans (
            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            subject_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            subject_value VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            source_detection VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            created_at BIGINT NOT NULL,
            created_by VARCHAR(191) NOT NULL,
            lifted_at BIGINT NULL,
            lifted_by VARCHAR(191) NULL,
            INDEX idx_bans_active_subject(subject_type, subject_value, lifted_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS audit_events (
            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            player_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
            username_snapshot VARCHAR(64) NULL,
            session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
            detection_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            action_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            details TEXT NOT NULL,
            created_at BIGINT NOT NULL,
            INDEX idx_audit_player_created(player_uuid, created_at),
            INDEX idx_audit_session(session_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """.trimIndent(),
    )
}
