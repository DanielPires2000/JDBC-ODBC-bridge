package pt.daniel.odbc

import com.sun.jna.Pointer
import com.sun.jna.ptr.ShortByReference
import pt.daniel.odbc.interop.OdbcApi
import java.sql.*

/**
 * Implementação de [DatabaseMetaData] que consulta informações sobre a fonte de dados ODBC.
 *
 * Usa SQLGetInfo para dados gerais e SQLTables/SQLColumns para catálogo.
 * Métodos não suportados devolvem valores razoáveis por defeito em vez de lançar exceções,
 * para maximizar a compatibilidade com ferramentas como DBeaver.
 */
class OdbcDatabaseMetaData(
    private val connection: OdbcConnection,
    private val api: OdbcApi,
    private val connectionHandle: Pointer?,
    private val charset: java.nio.charset.Charset
) : DatabaseMetaData {

    private companion object {
        const val INFO_BUFFER_SIZE: Short = 512
    }

    // --- Informações obtidas via SQLGetInfo ---

    private fun getStringInfo(infoType: Int): String {
        val buffer = ByteArray(INFO_BUFFER_SIZE.toInt())
        val actualLen = ShortByReference()
        val result = api.SQLGetInfo(connectionHandle, infoType.toShort(), buffer, INFO_BUFFER_SIZE, actualLen)
        if (!OdbcApi.isSuccess(result)) return ""
        val len = actualLen.value.toInt().coerceAtMost(INFO_BUFFER_SIZE.toInt() - 1).coerceAtLeast(0)
        return String(buffer, 0, len, charset).trim('\u0000')
    }

    override fun getDatabaseProductName(): String = getStringInfo(OdbcApi.SQL_DBMS_NAME).ifBlank { "ODBC Data Source" }
    override fun getDatabaseProductVersion(): String = getStringInfo(OdbcApi.SQL_DBMS_VER)
    override fun getDriverName(): String = getStringInfo(OdbcApi.SQL_DRIVER_NAME).ifBlank { "JDBC-ODBC Bridge" }
    override fun getDriverVersion(): String = getStringInfo(OdbcApi.SQL_DRIVER_VER).ifBlank { "1.0" }
    override fun getUserName(): String = getStringInfo(OdbcApi.SQL_USER_NAME)
    override fun getIdentifierQuoteString(): String = getStringInfo(OdbcApi.SQL_IDENTIFIER_QUOTE_CHAR).ifBlank { "\"" }
    override fun getCatalogTerm(): String = getStringInfo(OdbcApi.SQL_CATALOG_TERM).ifBlank { "catalog" }
    override fun getSchemaTerm(): String = getStringInfo(OdbcApi.SQL_SCHEMA_TERM).ifBlank { "schema" }

    override fun getDriverMajorVersion(): Int = 1
    override fun getDriverMinorVersion(): Int = 0
    override fun getJDBCMajorVersion(): Int = 4
    override fun getJDBCMinorVersion(): Int = 2
    override fun getDatabaseMajorVersion(): Int {
        val ver = databaseProductVersion
        return ver.split(".").firstOrNull()?.toIntOrNull() ?: 0
    }
    override fun getDatabaseMinorVersion(): Int {
        val ver = databaseProductVersion
        return ver.split(".").getOrNull(1)?.toIntOrNull() ?: 0
    }

    override fun getURL(): String? = null
    override fun isReadOnly(): Boolean = false
    override fun getConnection(): Connection = connection

    // --- Catalog queries via SQLTables / SQLColumns ---

    override fun getTables(catalog: String?, schemaPattern: String?, tableNamePattern: String?, types: Array<out String>?): ResultSet {
        val stmtPtr = com.sun.jna.ptr.PointerByReference()
        val allocResult = api.SQLAllocHandle(OdbcApi.SQL_HANDLE_STMT.toShort(), connectionHandle, stmtPtr)
        if (!OdbcApi.isSuccess(allocResult)) {
            throw SQLException("Não foi possível alocar Statement para getTables")
        }
        val hstmt = stmtPtr.value

        val typeStr = types?.joinToString(",")
        val result = api.SQLTables(
            hstmt,
            catalog, if (catalog != null) catalog.length.toShort() else 0,
            schemaPattern, if (schemaPattern != null) schemaPattern.length.toShort() else 0,
            tableNamePattern, if (tableNamePattern != null) tableNamePattern.length.toShort() else 0,
            typeStr, if (typeStr != null) typeStr.length.toShort() else 0
        )
        if (!OdbcApi.isSuccess(result)) {
            api.SQLFreeHandle(OdbcApi.SQL_HANDLE_STMT.toShort(), hstmt)
            throw SQLException("SQLTables falhou (Código: $result)")
        }

        // Cria um Statement wrapper temporário para gerir o handle
        val wrapperStmt = OdbcStatement(connection, api, hstmt, charset)
        return OdbcResultSet(wrapperStmt, api, hstmt, charset)
    }

    override fun getColumns(catalog: String?, schemaPattern: String?, tableNamePattern: String?, columnNamePattern: String?): ResultSet {
        val stmtPtr = com.sun.jna.ptr.PointerByReference()
        val allocResult = api.SQLAllocHandle(OdbcApi.SQL_HANDLE_STMT.toShort(), connectionHandle, stmtPtr)
        if (!OdbcApi.isSuccess(allocResult)) {
            throw SQLException("Não foi possível alocar Statement para getColumns")
        }
        val hstmt = stmtPtr.value

        val result = api.SQLColumns(
            hstmt,
            catalog, if (catalog != null) catalog.length.toShort() else 0,
            schemaPattern, if (schemaPattern != null) schemaPattern.length.toShort() else 0,
            tableNamePattern, if (tableNamePattern != null) tableNamePattern.length.toShort() else 0,
            columnNamePattern, if (columnNamePattern != null) columnNamePattern.length.toShort() else 0
        )
        if (!OdbcApi.isSuccess(result)) {
            api.SQLFreeHandle(OdbcApi.SQL_HANDLE_STMT.toShort(), hstmt)
            throw SQLException("SQLColumns falhou (Código: $result)")
        }

        val wrapperStmt = OdbcStatement(connection, api, hstmt, charset)
        return OdbcResultSet(wrapperStmt, api, hstmt, charset)
    }

    // --- Capacidades do driver ---

    override fun supportsConvert(): Boolean = false
    override fun supportsConvert(fromType: Int, toType: Int): Boolean = false
    override fun supportsTransactions(): Boolean = true
    override fun supportsTransactionIsolationLevel(level: Int): Boolean = level == Connection.TRANSACTION_READ_COMMITTED
    override fun getDefaultTransactionIsolation(): Int = Connection.TRANSACTION_READ_COMMITTED
    override fun supportsBatchUpdates(): Boolean = false
    override fun supportsSavepoints(): Boolean = false
    override fun supportsStoredProcedures(): Boolean = true
    override fun supportsUnion(): Boolean = true
    override fun supportsUnionAll(): Boolean = true
    override fun supportsGroupBy(): Boolean = true
    override fun supportsOuterJoins(): Boolean = true
    override fun supportsFullOuterJoins(): Boolean = false
    override fun supportsLimitedOuterJoins(): Boolean = true
    override fun supportsSubqueriesInComparisons(): Boolean = true
    override fun supportsSubqueriesInExists(): Boolean = true
    override fun supportsSubqueriesInIns(): Boolean = true
    override fun supportsSubqueriesInQuantifieds(): Boolean = true
    override fun supportsCorrelatedSubqueries(): Boolean = true
    override fun supportsOrderByUnrelated(): Boolean = true
    override fun supportsGroupByUnrelated(): Boolean = true
    override fun supportsGroupByBeyondSelect(): Boolean = true
    override fun supportsLikeEscapeClause(): Boolean = true
    override fun supportsMultipleResultSets(): Boolean = false
    override fun supportsMultipleTransactions(): Boolean = true
    override fun supportsNonNullableColumns(): Boolean = true
    override fun supportsMinimumSQLGrammar(): Boolean = true
    override fun supportsCoreSQLGrammar(): Boolean = true
    override fun supportsExtendedSQLGrammar(): Boolean = false
    override fun supportsANSI92EntryLevelSQL(): Boolean = true
    override fun supportsANSI92IntermediateSQL(): Boolean = false
    override fun supportsANSI92FullSQL(): Boolean = false
    override fun supportsIntegrityEnhancementFacility(): Boolean = false
    override fun supportsAlterTableWithAddColumn(): Boolean = true
    override fun supportsAlterTableWithDropColumn(): Boolean = true
    override fun supportsColumnAliasing(): Boolean = true
    override fun supportsMixedCaseIdentifiers(): Boolean = false
    override fun supportsMixedCaseQuotedIdentifiers(): Boolean = true
    override fun storesUpperCaseIdentifiers(): Boolean = false
    override fun storesLowerCaseIdentifiers(): Boolean = false
    override fun storesMixedCaseIdentifiers(): Boolean = true
    override fun storesUpperCaseQuotedIdentifiers(): Boolean = false
    override fun storesLowerCaseQuotedIdentifiers(): Boolean = false
    override fun storesMixedCaseQuotedIdentifiers(): Boolean = true
    override fun supportsTableCorrelationNames(): Boolean = true
    override fun supportsDifferentTableCorrelationNames(): Boolean = false
    override fun supportsExpressionsInOrderBy(): Boolean = true
    override fun supportsMultipleOpenResults(): Boolean = false
    override fun supportsGetGeneratedKeys(): Boolean = false
    override fun supportsResultSetType(type: Int): Boolean = type == ResultSet.TYPE_FORWARD_ONLY
    override fun supportsResultSetConcurrency(type: Int, concurrency: Int): Boolean =
        type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY
    override fun supportsResultSetHoldability(holdability: Int): Boolean = false
    override fun getResultSetHoldability(): Int = ResultSet.CLOSE_CURSORS_AT_COMMIT
    override fun supportsNamedParameters(): Boolean = false
    override fun supportsStatementPooling(): Boolean = false
    override fun getSQLStateType(): Int = DatabaseMetaData.sqlStateSQL

    // --- Limites ---

    override fun getMaxColumnsInTable(): Int = 0  // 0 = sem limite conhecido
    override fun getMaxColumnsInSelect(): Int = 0
    override fun getMaxColumnsInGroupBy(): Int = 0
    override fun getMaxColumnsInOrderBy(): Int = 0
    override fun getMaxColumnsInIndex(): Int = 0
    override fun getMaxTableNameLength(): Int = 128
    override fun getMaxColumnNameLength(): Int = 128
    override fun getMaxSchemaNameLength(): Int = 128
    override fun getMaxCatalogNameLength(): Int = 128
    override fun getMaxRowSize(): Int = 0
    override fun getMaxStatementLength(): Int = 0
    override fun getMaxStatements(): Int = 0
    override fun getMaxConnections(): Int = 0
    override fun getMaxTablesInSelect(): Int = 0
    override fun getMaxIndexLength(): Int = 0
    override fun getMaxUserNameLength(): Int = 128
    override fun getMaxCharLiteralLength(): Int = 0
    override fun getMaxBinaryLiteralLength(): Int = 0
    override fun getMaxProcedureNameLength(): Int = 128
    override fun getMaxCursorNameLength(): Int = 128

    // --- Misc ---

    override fun nullsAreSortedHigh(): Boolean = false
    override fun nullsAreSortedLow(): Boolean = true
    override fun nullsAreSortedAtStart(): Boolean = false
    override fun nullsAreSortedAtEnd(): Boolean = false
    override fun nullPlusNonNullIsNull(): Boolean = true
    override fun allProceduresAreCallable(): Boolean = false
    override fun allTablesAreSelectable(): Boolean = true
    override fun usesLocalFiles(): Boolean = false
    override fun usesLocalFilePerTable(): Boolean = false
    override fun dataDefinitionCausesTransactionCommit(): Boolean = false
    override fun dataDefinitionIgnoredInTransactions(): Boolean = false
    override fun doesMaxRowSizeIncludeBlobs(): Boolean = true
    override fun isCatalogAtStart(): Boolean = true
    override fun getCatalogSeparator(): String = "."
    override fun getSearchStringEscape(): String = "\\"
    override fun getExtraNameCharacters(): String = ""
    override fun getNumericFunctions(): String = ""
    override fun getStringFunctions(): String = ""
    override fun getSystemFunctions(): String = ""
    override fun getTimeDateFunctions(): String = ""
    override fun getSQLKeywords(): String = ""
    override fun getProcedureTerm(): String = "procedure"

    override fun supportsSelectForUpdate(): Boolean = false
    override fun supportsPositionedDelete(): Boolean = false
    override fun supportsPositionedUpdate(): Boolean = false
    override fun supportsOpenCursorsAcrossCommit(): Boolean = false
    override fun supportsOpenCursorsAcrossRollback(): Boolean = false
    override fun supportsOpenStatementsAcrossCommit(): Boolean = true
    override fun supportsOpenStatementsAcrossRollback(): Boolean = true
    override fun supportsDataDefinitionAndDataManipulationTransactions(): Boolean = true
    override fun supportsDataManipulationTransactionsOnly(): Boolean = false
    override fun supportsCatalogsInDataManipulation(): Boolean = true
    override fun supportsCatalogsInProcedureCalls(): Boolean = true
    override fun supportsCatalogsInTableDefinitions(): Boolean = true
    override fun supportsCatalogsInIndexDefinitions(): Boolean = true
    override fun supportsCatalogsInPrivilegeDefinitions(): Boolean = false
    override fun supportsSchemasInDataManipulation(): Boolean = true
    override fun supportsSchemasInProcedureCalls(): Boolean = true
    override fun supportsSchemasInTableDefinitions(): Boolean = true
    override fun supportsSchemasInIndexDefinitions(): Boolean = true
    override fun supportsSchemasInPrivilegeDefinitions(): Boolean = false

    override fun ownUpdatesAreVisible(type: Int): Boolean = false
    override fun ownDeletesAreVisible(type: Int): Boolean = false
    override fun ownInsertsAreVisible(type: Int): Boolean = false
    override fun othersUpdatesAreVisible(type: Int): Boolean = false
    override fun othersDeletesAreVisible(type: Int): Boolean = false
    override fun othersInsertsAreVisible(type: Int): Boolean = false
    override fun updatesAreDetected(type: Int): Boolean = false
    override fun deletesAreDetected(type: Int): Boolean = false
    override fun insertsAreDetected(type: Int): Boolean = false

    // --- Métodos que devolvem ResultSets vazios em vez de lançar exceções ---
    // O DBeaver chama TODOS estes métodos ao ligar-se. Se lançarem exceção,
    // o DBeaver mostra erros ou desliga. EmptyResultSet() = "sem dados, sem erro".

    override fun getProcedures(catalog: String?, schemaPattern: String?, procedureNamePattern: String?): ResultSet =
        EmptyResultSet()
    override fun getProcedureColumns(catalog: String?, schemaPattern: String?, procedureNamePattern: String?, columnNamePattern: String?): ResultSet =
        EmptyResultSet()
    override fun getSchemas(): ResultSet = EmptyResultSet()
    override fun getSchemas(catalog: String?, schemaPattern: String?): ResultSet = EmptyResultSet()
    override fun getCatalogs(): ResultSet = EmptyResultSet()
    override fun getTableTypes(): ResultSet = EmptyResultSet()
    override fun getColumnPrivileges(catalog: String?, schema: String?, table: String?, columnNamePattern: String?): ResultSet =
        EmptyResultSet()
    override fun getTablePrivileges(catalog: String?, schemaPattern: String?, tableNamePattern: String?): ResultSet =
        EmptyResultSet()
    override fun getBestRowIdentifier(catalog: String?, schema: String?, table: String?, scope: Int, nullable: Boolean): ResultSet =
        EmptyResultSet()
    override fun getVersionColumns(catalog: String?, schema: String?, table: String?): ResultSet =
        EmptyResultSet()
    override fun getPrimaryKeys(catalog: String?, schema: String?, table: String?): ResultSet =
        EmptyResultSet()
    override fun getImportedKeys(catalog: String?, schema: String?, table: String?): ResultSet =
        EmptyResultSet()
    override fun getExportedKeys(catalog: String?, schema: String?, table: String?): ResultSet =
        EmptyResultSet()
    override fun getCrossReference(parentCatalog: String?, parentSchema: String?, parentTable: String?, foreignCatalog: String?, foreignSchema: String?, foreignTable: String?): ResultSet =
        EmptyResultSet()
    override fun getTypeInfo(): ResultSet = EmptyResultSet()
    override fun getIndexInfo(catalog: String?, schema: String?, table: String?, unique: Boolean, approximate: Boolean): ResultSet =
        EmptyResultSet()
    override fun getUDTs(catalog: String?, schemaPattern: String?, typeNamePattern: String?, types: IntArray?): ResultSet =
        EmptyResultSet()
    override fun getSuperTypes(catalog: String?, schemaPattern: String?, typeNamePattern: String?): ResultSet =
        EmptyResultSet()
    override fun getSuperTables(catalog: String?, schemaPattern: String?, tableNamePattern: String?): ResultSet =
        EmptyResultSet()
    override fun getAttributes(catalog: String?, schemaPattern: String?, typeNamePattern: String?, attributeNamePattern: String?): ResultSet =
        EmptyResultSet()
    override fun getClientInfoProperties(): ResultSet = EmptyResultSet()
    override fun getFunctions(catalog: String?, schemaPattern: String?, functionNamePattern: String?): ResultSet =
        EmptyResultSet()
    override fun getFunctionColumns(catalog: String?, schemaPattern: String?, functionNamePattern: String?, columnNamePattern: String?): ResultSet =
        EmptyResultSet()
    override fun getPseudoColumns(catalog: String?, schemaPattern: String?, tableNamePattern: String?, columnNamePattern: String?): ResultSet =
        EmptyResultSet()

    override fun generatedKeyAlwaysReturned(): Boolean = false
    override fun autoCommitFailureClosesAllResultSets(): Boolean = false
    override fun locatorsUpdateCopy(): Boolean = false
    override fun supportsStoredFunctionsUsingCallSyntax(): Boolean = false
    override fun getRowIdLifetime(): RowIdLifetime = RowIdLifetime.ROWID_UNSUPPORTED

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException()
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
