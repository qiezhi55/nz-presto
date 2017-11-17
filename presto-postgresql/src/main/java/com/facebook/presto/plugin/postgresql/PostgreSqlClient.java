/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.plugin.postgresql;

import com.facebook.presto.plugin.jdbc.BaseJdbcClient;
import com.facebook.presto.plugin.jdbc.BaseJdbcConfig;
import com.facebook.presto.plugin.jdbc.JdbcConnectorId;
import com.facebook.presto.plugin.jdbc.JdbcOutputTableHandle;
import com.facebook.presto.plugin.jdbc.JdbcTableHandle;
import com.facebook.presto.plugin.jdbc.JdbcTableLayoutHandle;
import com.facebook.presto.spi.ConnectorSplitSource;
import com.facebook.presto.spi.type.BigintType;
import com.facebook.presto.spi.type.BooleanType;
import com.facebook.presto.spi.type.DateType;
import com.facebook.presto.spi.type.DoubleType;
import com.facebook.presto.spi.type.IntegerType;
import com.facebook.presto.spi.type.SmallintType;
import com.facebook.presto.spi.type.StandardTypes;
import com.facebook.presto.spi.type.TimeType;
import com.facebook.presto.spi.type.TimestampType;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.facebook.presto.spi.type.TypeSignatureParameter;
import com.facebook.presto.spi.type.VarbinaryType;
import com.facebook.presto.spi.type.VarcharType;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import org.postgresql.Driver;

import javax.inject.Inject;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static com.facebook.presto.spi.type.CharType.createCharType;
import static com.facebook.presto.spi.type.VarcharType.createUnboundedVarcharType;
import static com.facebook.presto.spi.type.VarcharType.createVarcharType;
import static java.util.Collections.singletonList;

public class PostgreSqlClient
        extends BaseJdbcClient
{
    private static final Logger log = Logger.get(PostgreSqlClient.class);

    private static final Map<String, Type> PG_ARRAY_TYPE_TO_ELEMENT_TYPE =
            ImmutableMap.<String, Type>builder()
                    .put("_bool", BooleanType.BOOLEAN)
                    .put("_bit", BooleanType.BOOLEAN)
                    .put("_int8", BigintType.BIGINT)
                    .put("_int4", IntegerType.INTEGER)
                    .put("_int2", SmallintType.SMALLINT)
                    .put("_text", VarcharType.createUnboundedVarcharType())
                    .put("_bytea", VarbinaryType.VARBINARY)
                    .put("_float4", DoubleType.DOUBLE)
                    .put("_float8", DoubleType.DOUBLE)
                    .put("_timestamp", TimestampType.TIMESTAMP)
                    .put("_date", DateType.DATE)
                    .put("_time", TimeType.TIME)
                    .put("_numeric", DoubleType.DOUBLE)
                    .build();

    @Inject
    public PostgreSqlClient(JdbcConnectorId connectorId, TypeManager typeManager, BaseJdbcConfig config)
            throws SQLException
    {
        super(connectorId, typeManager, config, "\"", new Driver());
    }

    @Override
    public void commitCreateTable(JdbcOutputTableHandle handle)
    {
        // PostgreSQL does not allow qualifying the target of a rename
        StringBuilder sql = new StringBuilder()
                .append("ALTER TABLE ")
                .append(quoted(handle.getCatalogName(), handle.getSchemaName(), handle.getTemporaryTableName()))
                .append(" RENAME TO ")
                .append(quoted(handle.getTableName()));

        try (Connection connection = getConnection(handle)) {
            execute(connection, sql.toString());
        }
        catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public PreparedStatement getPreparedStatement(Connection connection, String sql)
            throws SQLException
    {
        connection.setAutoCommit(false);
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setFetchSize(1000);
        return statement;
    }

    @Override
    protected ResultSet getTables(Connection connection, String schemaName, String tableName)
            throws SQLException
    {
        DatabaseMetaData metadata = connection.getMetaData();
        String escape = metadata.getSearchStringEscape();
        return metadata.getTables(
                connection.getCatalog(),
                escapeNamePattern(schemaName, escape),
                escapeNamePattern(tableName, escape),
                new String[] {"TABLE", "VIEW", "MATERIALIZED VIEW", "FOREIGN TABLE"});
    }

    @Override
    protected Type toPrestoType(int dataType, int columnSize, String typeName)
    {
        Type elementType = null;

        if ("_char".equals(typeName)) {
            elementType = createCharType(columnSize);
        }
        else if ("_varchar".equals(typeName) && columnSize < VarcharType.MAX_LENGTH && columnSize > 0) {
            elementType = createVarcharType(columnSize);
        }
        else if ("_varchar".equals(typeName)) {
            elementType = createUnboundedVarcharType();
        }
        else {
            elementType = PG_ARRAY_TYPE_TO_ELEMENT_TYPE.get(typeName);
        }

        if (elementType != null) {
            return typeManager.getParameterizedType(
                    StandardTypes.ARRAY,
                    singletonList(TypeSignatureParameter.of(elementType.getTypeSignature())));
        }

        return super.toPrestoType(dataType, columnSize, typeName);
    }

    @Override
    public ConnectorSplitSource getSplits(JdbcTableLayoutHandle layoutHandle)
    {
        JdbcTableHandle tableHandle = layoutHandle.getTable();
        return new PostgreSqlSplitSource(
                connectorId,
                tableHandle,
                connectionUrl,
                connectionProperties,
                layoutHandle.getTupleDomain(),
                this
        );
    }

    public Optional<PostgreSqlStatistics> getStatistics(final JdbcTableHandle table, final String connectionUrl, Properties connectionProperties)
    {
        try (final Connection conn = driver.connect(connectionUrl, connectionProperties);
             final Statement statement = conn.createStatement()) {
            final long numberOfRows;
            statement.execute("select count(*) from " + quoted(table.getCatalogName(), table.getSchemaName(), table.getTableName()));
            try (ResultSet resultSet = statement.getResultSet()) {
                resultSet.next();
                numberOfRows = resultSet.getLong(1);
            }

            final DatabaseMetaData metaData = conn.getMetaData();
            final ImmutableList.Builder<String> primaryKeyColumnsBuilder = ImmutableList.builder();
            try (ResultSet primaryKeys = metaData.getPrimaryKeys(conn.getCatalog(), table.getSchemaName(), table.getTableName())) {
                while (primaryKeys.next()) {
                    final String columnName = primaryKeys.getString("COLUMN_NAME");
                    if (columnName != null) {
                        primaryKeyColumnsBuilder.add(columnName);
                    }
                }
            }
            final List<String> primaryKeyColumns = primaryKeyColumnsBuilder.build();
            if (primaryKeyColumns.size() != 1) {
                return Optional.empty();
            }
            final String primaryKeyColumn = primaryKeyColumns.get(0);

            final Type primaryKeyColumnType;
            final String typeName;
            try (ResultSet columns = metaData.getColumns(conn.getCatalog(), table.getSchemaName(), table.getTableName(), primaryKeyColumn)) {
                columns.next();
                int dataType = columns.getInt("DATA_TYPE");
                typeName = columns.getString("TYPE_NAME");
                int columnSize = columns.getInt("COLUMN_SIZE");
                primaryKeyColumnType = toPrestoType(dataType, columnSize, typeName);
            }

            List<Object> histogram;
            log.error("Schema name: " + table.getSchemaName());
            log.error("Table name: " + table.getTableName());
            log.error("attname : " + primaryKeyColumn);

            try (PreparedStatement preparedStatement = conn.prepareStatement(
                    String.format("select cast(cast(histogram_bounds as text) as %s[]) from pg_catalog.pg_stats " +
                                  "where schemaname = ? and tablename = ? and attname = ?", typeName))) {
                preparedStatement.setString(1, table.getSchemaName());
                preparedStatement.setString(2, table.getTableName());
                preparedStatement.setString(3, primaryKeyColumn);

                try (ResultSet histogramCount = preparedStatement.executeQuery()) {
                    if (!histogramCount.next()) {
                        log.error("Couldn't get histogram");
                        return Optional.empty();
                    }

                    Array array = histogramCount.getArray(1);
                    if (array == null) {
                        return Optional.empty();
                    }
                    Object[] objectArray = (Object[]) array.getArray();
                    histogram = ImmutableList.copyOf(objectArray);
                }
            }

            return Optional.of(new PostgreSqlStatistics(numberOfRows, primaryKeyColumn, primaryKeyColumnType, histogram));
        }
        catch (SQLException e) {
            log.error(e, "Failed to get statistics for: " + table);
            return Optional.empty();
        }
    }
}
