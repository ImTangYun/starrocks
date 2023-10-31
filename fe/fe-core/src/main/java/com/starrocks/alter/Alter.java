// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/alter/Alter.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.alter;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.starrocks.analysis.AddPartitionClause;
import com.starrocks.analysis.AlterClause;
import com.starrocks.analysis.AlterSystemStmt;
import com.starrocks.analysis.AlterTableStmt;
import com.starrocks.analysis.AlterViewStmt;
import com.starrocks.analysis.ColumnRenameClause;
import com.starrocks.analysis.CreateMaterializedViewStmt;
import com.starrocks.analysis.DropMaterializedViewStmt;
import com.starrocks.analysis.DropPartitionClause;
import com.starrocks.analysis.ModifyPartitionClause;
import com.starrocks.analysis.ModifyTablePropertiesClause;
import com.starrocks.analysis.PartitionRenameClause;
import com.starrocks.analysis.ReplacePartitionClause;
import com.starrocks.analysis.RollupRenameClause;
import com.starrocks.analysis.SwapTableClause;
import com.starrocks.analysis.TableName;
import com.starrocks.analysis.TableRenameClause;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.ColocateTableIndex;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.DataProperty;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.OlapTable.OlapTableState;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PartitionInfo;
import com.starrocks.catalog.PartitionType;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Table.TableType;
import com.starrocks.catalog.View;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.DdlException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.MetaNotFoundException;
import com.starrocks.common.UserException;
import com.starrocks.common.util.DynamicPartitionUtil;
import com.starrocks.common.util.PropertyAnalyzer;
import com.starrocks.persist.AlterViewInfo;
import com.starrocks.persist.BatchModifyPartitionsInfo;
import com.starrocks.persist.ModifyPartitionInfo;
import com.starrocks.persist.SwapTableOperationLog;
import com.starrocks.qe.ConnectContext;
import com.starrocks.thrift.TTabletType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class Alter {
    private static final Logger LOG = LogManager.getLogger(Alter.class);

    private AlterHandler schemaChangeHandler;
    private AlterHandler materializedViewHandler;
    private SystemHandler clusterHandler;

    public Alter() {
        schemaChangeHandler = new SchemaChangeHandler();
        materializedViewHandler = new MaterializedViewHandler();
        clusterHandler = new SystemHandler();
    }

    public void start() {
        schemaChangeHandler.start();
        materializedViewHandler.start();
        clusterHandler.start();
    }

    public void processCreateMaterializedView(CreateMaterializedViewStmt stmt)
            throws DdlException, AnalysisException {
        String tableName = stmt.getBaseIndexName();
        // check db
        String dbName = stmt.getDBName();
        Database db = Catalog.getCurrentCatalog().getDb(dbName);
        if (db == null) {
            ErrorReport.reportDdlException(ErrorCode.ERR_BAD_DB_ERROR, dbName);
        }
        // check cluster capacity
        Catalog.getCurrentSystemInfo().checkClusterCapacity(stmt.getClusterName());
        // check db quota
        db.checkQuota();

        db.writeLock();
        try {
            Table table = db.getTable(tableName);
            if (table.getType() != TableType.OLAP) {
                throw new DdlException("Do not support alter non-OLAP table[" + tableName + "]");
            }
            OlapTable olapTable = (OlapTable) table;
            if (olapTable.getKeysType() == KeysType.PRIMARY_KEYS) {
                throw new DdlException("Do not support create materialized view on primary key table[" + tableName + "]");
            }
            olapTable.checkStableAndNormal(db.getClusterName());

            ((MaterializedViewHandler) materializedViewHandler).processCreateMaterializedView(stmt, db,
                    olapTable);
        } finally {
            db.writeUnlock();
        }
    }

    public void processDropMaterializedView(DropMaterializedViewStmt stmt) throws DdlException, MetaNotFoundException {
        // check db
        String dbName = stmt.getDbName();
        Database db = Catalog.getCurrentCatalog().getDb(dbName);
        if (db == null) {
            ErrorReport.reportDdlException(ErrorCode.ERR_BAD_DB_ERROR, dbName);
        }

        db.writeLock();
        try {
            Table table = null;
            if (stmt.getTblName() != null) {
                table = db.getTable(stmt.getTblName());
            } else {
                boolean hasfindTable = false;
                for (Table t : db.getTables()) {
                    if (t instanceof OlapTable) {
                        OlapTable olapTable = (OlapTable) t;
                        for (MaterializedIndex mvIdx : olapTable.getVisibleIndex()) {
                            if (olapTable.getIndexNameById(mvIdx.getId()).equals(stmt.getMvName())) {
                                table = olapTable;
                                hasfindTable = true;
                                break;
                            }
                        }
                        if (hasfindTable) {
                            break;
                        }
                    }
                }
            }
            if (table == null) {
                throw new MetaNotFoundException("Materialized view " + stmt.getMvName() + " is not find");
            }
            // check table type
            if (table.getType() != TableType.OLAP) {
                throw new DdlException(
                        "Do not support non-OLAP table [" + table.getName() + "] when drop materialized view");
            }
            // check table state
            OlapTable olapTable = (OlapTable) table;
            if (olapTable.getState() != OlapTableState.NORMAL) {
                throw new DdlException("Table[" + table.getName() + "]'s state is not NORMAL. "
                        + "Do not allow doing DROP ops");
            }
            // drop materialized view
            ((MaterializedViewHandler) materializedViewHandler).processDropMaterializedView(stmt, db, olapTable);

        } catch (MetaNotFoundException e) {
            if (stmt.isSetIfExists()) {
                LOG.info(e.getMessage());
            } else {
                throw e;
            }
        } finally {
            db.writeUnlock();
        }
    }

    public void processAlterTable(AlterTableStmt stmt) throws UserException {
        TableName dbTableName = stmt.getTbl();
        String dbName = dbTableName.getDb();
        final String clusterName = stmt.getClusterName();

        Database db = Catalog.getCurrentCatalog().getDb(dbName);
        if (db == null) {
            ErrorReport.reportDdlException(ErrorCode.ERR_BAD_DB_ERROR, dbName);
        }

        // check conflict alter ops first
        List<AlterClause> alterClauses = stmt.getOps();
        AlterOperations currentAlterOps = new AlterOperations();
        currentAlterOps.checkConflict(alterClauses);

        // check cluster capacity and db quota, only need to check once.
        if (currentAlterOps.needCheckCapacity()) {
            Catalog.getCurrentSystemInfo().checkClusterCapacity(clusterName);
            db.checkQuota();
        }

        // some operations will take long time to process, need to be done outside the databse lock
        boolean needProcessOutsideDatabaseLock = false;
        String tableName = dbTableName.getTbl();
        db.writeLock();
        try {
            Table table = db.getTable(tableName);
            if (table == null) {
                ErrorReport.reportDdlException(ErrorCode.ERR_BAD_TABLE_ERROR, tableName);
            }

            if (table.getType() != TableType.OLAP) {
                throw new DdlException("Do not support alter non-OLAP table[" + tableName + "]");
            }
            OlapTable olapTable = (OlapTable) table;

            if (olapTable.getState() != OlapTableState.NORMAL) {
                throw new DdlException(
                        "Table[" + table.getName() + "]'s state is not NORMAL. Do not allow doing ALTER ops");
            }

            if (currentAlterOps.hasSchemaChangeOp()) {
                // if modify storage type to v2, do schema change to convert all related tablets to segment v2 format
                schemaChangeHandler.process(alterClauses, clusterName, db, olapTable);
            } else if (currentAlterOps.hasRollupOp()) {
                materializedViewHandler.process(alterClauses, clusterName, db, olapTable);
            } else if (currentAlterOps.hasPartitionOp()) {
                Preconditions.checkState(alterClauses.size() == 1);
                AlterClause alterClause = alterClauses.get(0);
                if (alterClause instanceof DropPartitionClause) {
                    if (!((DropPartitionClause) alterClause).isTempPartition()) {
                        DynamicPartitionUtil.checkAlterAllowed((OlapTable) db.getTable(tableName));
                    }
                    Catalog.getCurrentCatalog().dropPartition(db, olapTable, ((DropPartitionClause) alterClause));
                } else if (alterClause instanceof ReplacePartitionClause) {
                    Catalog.getCurrentCatalog()
                            .replaceTempPartition(db, tableName, (ReplacePartitionClause) alterClause);
                } else if (alterClause instanceof ModifyPartitionClause) {
                    ModifyPartitionClause clause = ((ModifyPartitionClause) alterClause);
                    // expand the partition names if it is 'Modify Partition(*)'
                    if (clause.isNeedExpand()) {
                        List<String> partitionNames = clause.getPartitionNames();
                        partitionNames.clear();
                        for (Partition partition : olapTable.getPartitions()) {
                            partitionNames.add(partition.getName());
                        }
                    }
                    Map<String, String> properties = clause.getProperties();
                    if (properties.containsKey(PropertyAnalyzer.PROPERTIES_INMEMORY)) {
                        needProcessOutsideDatabaseLock = true;
                    } else {
                        List<String> partitionNames = clause.getPartitionNames();
                        modifyPartitionsProperty(db, olapTable, partitionNames, properties);
                    }
                } else if (alterClause instanceof AddPartitionClause) {
                    needProcessOutsideDatabaseLock = true;
                } else {
                    throw new DdlException("Invalid alter opertion: " + alterClause.getOpType());
                }
            } else if (currentAlterOps.hasRenameOp()) {
                processRename(db, olapTable, alterClauses);
            } else if (currentAlterOps.hasSwapOp()) {
                processSwap(db, olapTable, alterClauses);
            } else if (currentAlterOps.contains(AlterOpType.MODIFY_TABLE_PROPERTY_SYNC)) {
                needProcessOutsideDatabaseLock = true;
            } else {
                throw new DdlException("Invalid alter operations: " + currentAlterOps);
            }
        } finally {
            db.writeUnlock();
        }

        // the following ops should done outside db lock. because it contain synchronized create operation
        if (needProcessOutsideDatabaseLock) {
            Preconditions.checkState(alterClauses.size() == 1);
            AlterClause alterClause = alterClauses.get(0);
            if (alterClause instanceof AddPartitionClause) {
                if (!((AddPartitionClause) alterClause).isTempPartition()) {
                    DynamicPartitionUtil.checkAlterAllowed((OlapTable) db.getTable(tableName));
                }
                Catalog.getCurrentCatalog().addPartitions(db, tableName, (AddPartitionClause) alterClause);
            } else if (alterClause instanceof ModifyPartitionClause) {
                ModifyPartitionClause clause = ((ModifyPartitionClause) alterClause);
                Map<String, String> properties = clause.getProperties();
                List<String> partitionNames = clause.getPartitionNames();
                // currently, only in memory property could reach here
                Preconditions.checkState(properties.containsKey(PropertyAnalyzer.PROPERTIES_INMEMORY));
                ((SchemaChangeHandler) schemaChangeHandler).updatePartitionsInMemoryMeta(
                        db, tableName, partitionNames, properties);

                db.writeLock();
                try {
                    OlapTable olapTable = (OlapTable) db.getTable(tableName);
                    modifyPartitionsProperty(db, olapTable, partitionNames, properties);
                } finally {
                    db.writeUnlock();
                }
            } else if (alterClause instanceof ModifyTablePropertiesClause) {
                Map<String, String> properties = alterClause.getProperties();
                // currently, only in memory property could reach here
                Preconditions.checkState(properties.containsKey(PropertyAnalyzer.PROPERTIES_INMEMORY));
                ((SchemaChangeHandler) schemaChangeHandler).updateTableInMemoryMeta(db, tableName, properties);
            } else {
                throw new DdlException("Invalid alter opertion: " + alterClause.getOpType());
            }
        }
    }

    // entry of processing swap table
    private void processSwap(Database db, OlapTable origTable, List<AlterClause> alterClauses) throws UserException {
        if (!(alterClauses.get(0) instanceof SwapTableClause)) {
            throw new DdlException("swap operation only support table");
        }

        // must hold db write lock
        Preconditions.checkState(db.isWriteLockHeldByCurrentThread());

        SwapTableClause clause = (SwapTableClause) alterClauses.get(0);
        String origTblName = origTable.getName();
        String newTblName = clause.getTblName();
        Table newTbl = db.getTable(newTblName);
        if (newTbl == null || newTbl.getType() != TableType.OLAP) {
            throw new DdlException("Table " + newTblName + " does not exist or is not OLAP table");
        }
        OlapTable olapNewTbl = (OlapTable) newTbl;

        // First, we need to check whether the table to be operated on can be renamed
        olapNewTbl.checkAndSetName(origTblName, true);
        origTable.checkAndSetName(newTblName, true);

        swapTableInternal(db, origTable, olapNewTbl);

        // write edit log
        SwapTableOperationLog log = new SwapTableOperationLog(db.getId(), origTable.getId(), olapNewTbl.getId());
        Catalog.getCurrentCatalog().getEditLog().logSwapTable(log);

        LOG.info("finish swap table {}-{} with table {}-{}", origTable.getId(), origTblName, newTbl.getId(),
                newTblName);
    }

    public void replaySwapTable(SwapTableOperationLog log) {
        long dbId = log.getDbId();
        long origTblId = log.getOrigTblId();
        long newTblId = log.getNewTblId();
        Database db = Catalog.getCurrentCatalog().getDb(dbId);
        OlapTable origTable = (OlapTable) db.getTable(origTblId);
        OlapTable newTbl = (OlapTable) db.getTable(newTblId);

        try {
            swapTableInternal(db, origTable, newTbl);
        } catch (DdlException e) {
            LOG.warn("should not happen", e);
        }
        LOG.debug("finish replay swap table {}-{} with table {}-{}", origTblId, origTable.getName(), newTblId,
                newTbl.getName());
    }

    /**
     * The swap table operation works as follow:
     * For example, SWAP TABLE A WITH TABLE B.
     * must pre check A can be renamed to B and B can be renamed to A
     */
    private void swapTableInternal(Database db, OlapTable origTable, OlapTable newTbl)
            throws DdlException {
        String origTblName = origTable.getName();
        String newTblName = newTbl.getName();

        // drop origin table and new table
        db.dropTable(origTblName);
        db.dropTable(newTblName);

        // rename new table name to origin table name and add it to database
        newTbl.checkAndSetName(origTblName, false);
        db.createTable(newTbl);

        // rename origin table name to new table name and add it to database
        origTable.checkAndSetName(newTblName, false);
        db.createTable(origTable);
    }

    public void processAlterView(AlterViewStmt stmt, ConnectContext ctx) throws UserException {
        TableName dbTableName = stmt.getTbl();
        String dbName = dbTableName.getDb();

        Database db = Catalog.getCurrentCatalog().getDb(dbName);
        if (db == null) {
            ErrorReport.reportDdlException(ErrorCode.ERR_BAD_DB_ERROR, dbName);
        }

        String tableName = dbTableName.getTbl();
        db.writeLock();
        try {
            Table table = db.getTable(tableName);
            if (table == null) {
                ErrorReport.reportDdlException(ErrorCode.ERR_BAD_TABLE_ERROR, tableName);
            }

            if (table.getType() != TableType.VIEW) {
                throw new DdlException("The specified table [" + tableName + "] is not a view");
            }

            View view = (View) table;
            modifyViewDef(db, view, stmt.getInlineViewDef(), ctx.getSessionVariable().getSqlMode(), stmt.getColumns());
        } finally {
            db.writeUnlock();
        }
    }

    private void modifyViewDef(Database db, View view, String inlineViewDef, long sqlMode, List<Column> newFullSchema)
            throws DdlException {
        String viewName = view.getName();

        view.setInlineViewDefWithSqlMode(inlineViewDef, sqlMode);
        try {
            view.init();
        } catch (UserException e) {
            throw new DdlException("failed to init view stmt", e);
        }
        view.setNewFullSchema(newFullSchema);

        db.dropTable(viewName);
        db.createTable(view);

        AlterViewInfo alterViewInfo =
                new AlterViewInfo(db.getId(), view.getId(), inlineViewDef, newFullSchema, sqlMode);
        Catalog.getCurrentCatalog().getEditLog().logModifyViewDef(alterViewInfo);
        LOG.info("modify view[{}] definition to {}", viewName, inlineViewDef);
    }

    public void replayModifyViewDef(AlterViewInfo alterViewInfo) throws DdlException {
        long dbId = alterViewInfo.getDbId();
        long tableId = alterViewInfo.getTableId();
        String inlineViewDef = alterViewInfo.getInlineViewDef();
        List<Column> newFullSchema = alterViewInfo.getNewFullSchema();

        Database db = Catalog.getCurrentCatalog().getDb(dbId);
        db.writeLock();
        try {
            View view = (View) db.getTable(tableId);
            String viewName = view.getName();
            view.setInlineViewDefWithSqlMode(inlineViewDef, alterViewInfo.getSqlMode());
            try {
                view.init();
            } catch (UserException e) {
                throw new DdlException("failed to init view stmt", e);
            }
            view.setNewFullSchema(newFullSchema);

            db.dropTable(viewName);
            db.createTable(view);

            LOG.info("replay modify view[{}] definition to {}", viewName, inlineViewDef);
        } finally {
            db.writeUnlock();
        }
    }

    public void processAlterCluster(AlterSystemStmt stmt) throws UserException {
        clusterHandler.process(Arrays.asList(stmt.getAlterClause()), stmt.getClusterName(), null, null);
    }

    private void processRename(Database db, OlapTable table, List<AlterClause> alterClauses) throws DdlException {
        for (AlterClause alterClause : alterClauses) {
            if (alterClause instanceof TableRenameClause) {
                Catalog.getCurrentCatalog().renameTable(db, table, (TableRenameClause) alterClause);
                break;
            } else if (alterClause instanceof RollupRenameClause) {
                Catalog.getCurrentCatalog().renameRollup(db, table, (RollupRenameClause) alterClause);
                break;
            } else if (alterClause instanceof PartitionRenameClause) {
                Catalog.getCurrentCatalog().renamePartition(db, table, (PartitionRenameClause) alterClause);
                break;
            } else if (alterClause instanceof ColumnRenameClause) {
                Catalog.getCurrentCatalog().renameColumn(db, table, (ColumnRenameClause) alterClause);
                break;
            } else {
                Preconditions.checkState(false);
            }
        }
    }

    /**
     * Batch update partitions' properties
     * caller should hold the db lock
     */
    public void modifyPartitionsProperty(Database db,
                                         OlapTable olapTable,
                                         List<String> partitionNames,
                                         Map<String, String> properties)
            throws DdlException, AnalysisException {
        Preconditions.checkArgument(db.isWriteLockHeldByCurrentThread());
        ColocateTableIndex colocateTableIndex = Catalog.getCurrentColocateIndex();
        List<ModifyPartitionInfo> modifyPartitionInfos = Lists.newArrayList();
        if (olapTable.getState() != OlapTableState.NORMAL) {
            throw new DdlException("Table[" + olapTable.getName() + "]'s state is not NORMAL");
        }

        for (String partitionName : partitionNames) {
            Partition partition = olapTable.getPartition(partitionName);
            if (partition == null) {
                throw new DdlException(
                        "Partition[" + partitionName + "] does not exist in table[" + olapTable.getName() + "]");
            }
        }

        boolean hasInMemory = false;
        if (properties.containsKey(PropertyAnalyzer.PROPERTIES_INMEMORY)) {
            hasInMemory = true;
        }

        // get value from properties here
        // 1. data property
        DataProperty newDataProperty =
                PropertyAnalyzer.analyzeDataProperty(properties, null);
        // 2. replication num
        short newReplicationNum =
                PropertyAnalyzer.analyzeReplicationNum(properties, (short) -1);
        // 3. in memory
        boolean newInMemory = PropertyAnalyzer.analyzeBooleanProp(properties,
                PropertyAnalyzer.PROPERTIES_INMEMORY, false);
        // 4. tablet type
        TTabletType tTabletType =
                PropertyAnalyzer.analyzeTabletType(properties);

        // modify meta here
        PartitionInfo partitionInfo = olapTable.getPartitionInfo();
        for (String partitionName : partitionNames) {
            Partition partition = olapTable.getPartition(partitionName);
            // 1. date property
            if (newDataProperty != null) {
                partitionInfo.setDataProperty(partition.getId(), newDataProperty);
            }
            // 2. replication num
            if (newReplicationNum != (short) -1) {
                if (colocateTableIndex.isColocateTable(olapTable.getId())) {
                    throw new DdlException(
                            "table " + olapTable.getName() + " is colocate table, cannot change replicationNum");
                }
                partitionInfo.setReplicationNum(partition.getId(), newReplicationNum);
                // update default replication num if this table is unpartitioned table
                if (partitionInfo.getType() == PartitionType.UNPARTITIONED) {
                    olapTable.setReplicationNum(newReplicationNum);
                }
            }
            // 3. in memory
            boolean oldInMemory = partitionInfo.getIsInMemory(partition.getId());
            if (hasInMemory && (newInMemory != oldInMemory)) {
                partitionInfo.setIsInMemory(partition.getId(), newInMemory);
            }
            // 4. tablet type
            if (tTabletType != partitionInfo.getTabletType(partition.getId())) {
                partitionInfo.setTabletType(partition.getId(), tTabletType);
            }
            ModifyPartitionInfo info = new ModifyPartitionInfo(db.getId(), olapTable.getId(), partition.getId(),
                    newDataProperty, newReplicationNum, hasInMemory ? newInMemory : oldInMemory);
            modifyPartitionInfos.add(info);
        }

        // log here
        BatchModifyPartitionsInfo info = new BatchModifyPartitionsInfo(modifyPartitionInfos);
        Catalog.getCurrentCatalog().getEditLog().logBatchModifyPartition(info);
    }

    public void replayModifyPartition(ModifyPartitionInfo info) {
        Database db = Catalog.getCurrentCatalog().getDb(info.getDbId());
        db.writeLock();
        try {
            OlapTable olapTable = (OlapTable) db.getTable(info.getTableId());
            PartitionInfo partitionInfo = olapTable.getPartitionInfo();
            if (info.getDataProperty() != null) {
                partitionInfo.setDataProperty(info.getPartitionId(), info.getDataProperty());
            }
            if (info.getReplicationNum() != (short) -1) {
                short replicationNum = info.getReplicationNum();
                partitionInfo.setReplicationNum(info.getPartitionId(), replicationNum);
                // update default replication num if this table is unpartitioned table
                if (partitionInfo.getType() == PartitionType.UNPARTITIONED) {
                    olapTable.setReplicationNum(replicationNum);
                }
            }
            partitionInfo.setIsInMemory(info.getPartitionId(), info.isInMemory());
        } finally {
            db.writeUnlock();
        }
    }

    public AlterHandler getSchemaChangeHandler() {
        return this.schemaChangeHandler;
    }

    public AlterHandler getMaterializedViewHandler() {
        return this.materializedViewHandler;
    }

    public AlterHandler getClusterHandler() {
        return this.clusterHandler;
    }
}