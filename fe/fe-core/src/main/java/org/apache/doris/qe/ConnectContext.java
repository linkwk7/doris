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

package org.apache.doris.qe;

import org.apache.doris.analysis.BoolLiteral;
import org.apache.doris.analysis.DecimalLiteral;
import org.apache.doris.analysis.Expr;
import org.apache.doris.analysis.FloatLiteral;
import org.apache.doris.analysis.IntLiteral;
import org.apache.doris.analysis.LiteralExpr;
import org.apache.doris.analysis.NullLiteral;
import org.apache.doris.analysis.SetVar;
import org.apache.doris.analysis.StringLiteral;
import org.apache.doris.analysis.UserIdentity;
import org.apache.doris.analysis.VariableExpr;
import org.apache.doris.catalog.DatabaseIf;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.FunctionRegistry;
import org.apache.doris.catalog.TableIf;
import org.apache.doris.catalog.Type;
import org.apache.doris.cluster.ClusterNamespace;
import org.apache.doris.common.Config;
import org.apache.doris.common.util.DebugUtil;
import org.apache.doris.common.util.TimeUtils;
import org.apache.doris.datasource.CatalogIf;
import org.apache.doris.datasource.InternalCatalog;
import org.apache.doris.datasource.SessionContext;
import org.apache.doris.mysql.DummyMysqlChannel;
import org.apache.doris.mysql.MysqlCapability;
import org.apache.doris.mysql.MysqlChannel;
import org.apache.doris.mysql.MysqlCommand;
import org.apache.doris.mysql.MysqlSslContext;
import org.apache.doris.nereids.StatementContext;
import org.apache.doris.nereids.stats.StatsErrorEstimator;
import org.apache.doris.nereids.trees.expressions.literal.Literal;
import org.apache.doris.plugin.AuditEvent.AuditEventBuilder;
import org.apache.doris.resource.Tag;
import org.apache.doris.service.arrowflight.results.FlightSqlChannel;
import org.apache.doris.statistics.ColumnStatistic;
import org.apache.doris.statistics.Histogram;
import org.apache.doris.system.Backend;
import org.apache.doris.task.LoadTaskInfo;
import org.apache.doris.thrift.TNetworkAddress;
import org.apache.doris.thrift.TResultSinkType;
import org.apache.doris.thrift.TUniqueId;
import org.apache.doris.transaction.TransactionEntry;
import org.apache.doris.transaction.TransactionStatus;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.xnio.StreamConnection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

// When one client connect in, we create a connect context for it.
// We store session information here. Meanwhile ConnectScheduler all
// connect with its connection id.
// Use `volatile` to make the reference change atomic.
public class ConnectContext {
    private static final Logger LOG = LogManager.getLogger(ConnectContext.class);
    protected static ThreadLocal<ConnectContext> threadLocalInfo = new ThreadLocal<>();

    private static final String SSL_PROTOCOL = "TLS";

    public enum ConnectType {
        MYSQL,
        ARROW_FLIGHT_SQL
    }

    protected volatile ConnectType connectType;
    // set this id before analyze
    protected volatile long stmtId;
    protected volatile long forwardedStmtId;

    // set for http_stream
    protected volatile TUniqueId loadId;
    protected volatile long backendId;
    protected volatile LoadTaskInfo streamLoadInfo;

    protected volatile TUniqueId queryId = null;
    protected volatile String traceId;
    // id for this connection
    protected volatile int connectionId;
    // Timestamp when the connection is make
    protected volatile long loginTime;
    // for arrow flight
    protected volatile String peerIdentity;
    private final Map<String, String> preparedQuerys = new HashMap<>();
    private String runningQuery;
    private TNetworkAddress resultFlightServerAddr;
    private TNetworkAddress resultInternalServiceAddr;
    private ArrayList<Expr> resultOutputExprs;
    private TUniqueId finstId;
    private boolean returnResultFromLocal = true;
    // mysql net
    protected volatile MysqlChannel mysqlChannel;
    // state
    protected volatile QueryState state;
    protected volatile long returnRows;
    // the protocol capability which server say it can support
    protected volatile MysqlCapability serverCapability;
    // the protocol capability after server and client negotiate
    protected volatile MysqlCapability capability;
    // Indicate if this client is killed.
    protected volatile boolean isKilled;
    // Db
    protected volatile String currentDb = "";
    protected volatile long currentDbId = -1;
    // Transaction
    protected volatile TransactionEntry txnEntry = null;
    // cluster name
    protected volatile String clusterName = "";
    // used for ShowSqlAction which don't allow a user account
    protected volatile boolean noAuth = false;
    // username@host of current login user
    protected volatile String qualifiedUser;
    // LDAP authenticated but the Doris account does not exist,
    // set the flag, and the user login Doris as Temporary user.
    protected volatile boolean isTempUser = false;
    // username@host combination for the Doris account
    // that the server used to authenticate the current client.
    // In other word, currentUserIdentity is the entry that matched in Doris auth table.
    // This account determines user's access privileges.
    protected volatile UserIdentity currentUserIdentity;
    // Variables belong to this session.
    protected volatile SessionVariable sessionVariable;
    // Store user variable in this connection
    private Map<String, LiteralExpr> userVars = new HashMap<>();
    // Scheduler this connection belongs to
    protected volatile ConnectScheduler connectScheduler;
    // Executor
    protected volatile StmtExecutor executor;
    // Command this connection is processing.
    protected volatile MysqlCommand command;
    // Timestamp in millisecond last command starts at
    protected volatile long startTime;
    // Cache thread info for this connection.
    protected volatile ThreadInfo threadInfo;

    // Catalog: put catalog here is convenient for unit test,
    // because catalog is singleton, hard to mock
    protected Env env;
    protected String defaultCatalog = InternalCatalog.INTERNAL_CATALOG_NAME;
    protected boolean isSend;

    protected AuditEventBuilder auditEventBuilder = new AuditEventBuilder();

    protected String remoteIP;

    // This is used to statistic the current query details.
    // This property will only be set when the query starts to execute.
    // So in the query planning stage, do not use any value in this attribute.
    protected QueryDetail queryDetail = null;

    // cloud cluster name
    protected volatile String cloudCluster = null;

    // If set to true, the nondeterministic function will not be rewrote to constant.
    private boolean notEvalNondeterministicFunction = false;
    // The resource tag is used to limit the node resources that the user can use for query.
    // The default is empty, that is, unlimited.
    // This property is obtained from UserProperty when the client connection is created.
    // Only when the connection is created again, the new resource tags will be retrieved from the UserProperty
    private Set<Tag> resourceTags = Sets.newHashSet();
    // If set to true, the resource tags set in resourceTags will be used to limit the query resources.
    // If set to false, the system will not restrict query resources.
    private boolean isResourceTagsSet = false;

    private String sqlHash;

    private JSONObject minidump = null;

    // The FE ip current connected
    private String currentConnectedFEIp = "";

    private InsertResult insertResult;

    private SessionContext sessionContext;


    // This context is used for SSL connection between server and mysql client.
    private final MysqlSslContext mysqlSslContext = new MysqlSslContext(SSL_PROTOCOL);

    private StatsErrorEstimator statsErrorEstimator;

    private Map<String, String> resultAttachedInfo;

    private String workloadGroupName = "";
    private Map<Long, Backend> insertGroupCommitTableToBeMap = new HashMap<>();

    private TResultSinkType resultSinkType = TResultSinkType.MYSQL_PROTOCAL;

    // internal call like `insert overwrite` need skipAuth
    // For example, `insert overwrite` only requires load permission,
    // but the internal implementation will call the logic of `AlterTable`.
    // In this case, `skipAuth` needs to be set to `true` to skip the permission check of `AlterTable`
    private boolean skipAuth = false;

    public void setUserQueryTimeout(int queryTimeout) {
        if (queryTimeout > 0) {
            sessionVariable.setQueryTimeoutS(queryTimeout);
        }
    }

    public void setUserInsertTimeout(int insertTimeout) {
        if (insertTimeout > 0) {
            sessionVariable.setInsertTimeoutS(insertTimeout);
        }
    }

    private StatementContext statementContext;
    private Map<String, PrepareStmtContext> preparedStmtCtxs = Maps.newHashMap();

    private List<TableIf> tables = null;

    private Map<String, ColumnStatistic> totalColumnStatisticMap = new HashMap<>();

    public Map<String, ColumnStatistic> getTotalColumnStatisticMap() {
        return totalColumnStatisticMap;
    }

    public void setTotalColumnStatisticMap(Map<String, ColumnStatistic> totalColumnStatisticMap) {
        this.totalColumnStatisticMap = totalColumnStatisticMap;
    }

    private Map<String, Histogram> totalHistogramMap = new HashMap<>();

    public Map<String, Histogram> getTotalHistogramMap() {
        return totalHistogramMap;
    }

    public void setTotalHistogramMap(Map<String, Histogram> totalHistogramMap) {
        this.totalHistogramMap = totalHistogramMap;
    }

    public SessionContext getSessionContext() {
        return sessionContext;
    }

    public MysqlSslContext getMysqlSslContext() {
        return mysqlSslContext;
    }

    public TResultSinkType getResultSinkType() {
        return resultSinkType;
    }

    public void setOrUpdateInsertResult(long txnId, String label, String db, String tbl,
            TransactionStatus txnStatus, long loadedRows, int filteredRows) {
        if (isTxnModel() && insertResult != null) {
            insertResult.updateResult(txnStatus, loadedRows, filteredRows);
        } else {
            insertResult = new InsertResult(txnId, label, db, tbl, txnStatus, loadedRows, filteredRows);
        }
    }

    public InsertResult getInsertResult() {
        return insertResult;
    }

    public static ConnectContext get() {
        return threadLocalInfo.get();
    }

    public static void remove() {
        threadLocalInfo.remove();
    }

    public void setIsSend(boolean isSend) {
        this.isSend = isSend;
    }

    public boolean isSend() {
        return this.isSend;
    }

    public void setNotEvalNondeterministicFunction(boolean notEvalNondeterministicFunction) {
        this.notEvalNondeterministicFunction = notEvalNondeterministicFunction;
    }

    public boolean notEvalNondeterministicFunction() {
        return notEvalNondeterministicFunction;
    }

    public ConnectType getConnectType() {
        return connectType;
    }

    public void init() {
        state = new QueryState();
        returnRows = 0;
        isKilled = false;
        sessionVariable = VariableMgr.newSessionVariable();
        userVars = new HashMap<>();
        command = MysqlCommand.COM_SLEEP;
        if (Config.use_fuzzy_session_variable) {
            sessionVariable.initFuzzyModeVariables();
        }
    }

    public ConnectContext() {
        this((StreamConnection) null);
    }

    public ConnectContext(StreamConnection connection) {
        connectType = ConnectType.MYSQL;
        serverCapability = MysqlCapability.DEFAULT_CAPABILITY;
        if (connection != null) {
            mysqlChannel = new MysqlChannel(connection, this);
        } else {
            mysqlChannel = new DummyMysqlChannel();
        }
        init();
    }

    public boolean isTxnModel() {
        return txnEntry != null && txnEntry.isTxnModel();
    }

    public boolean isTxnIniting() {
        return txnEntry != null && txnEntry.isTxnIniting();
    }

    public boolean isTxnBegin() {
        return txnEntry != null && txnEntry.isTxnBegin();
    }

    public void addPreparedStmt(String stmtName, PrepareStmtContext ctx) {
        this.preparedStmtCtxs.put(stmtName, ctx);
    }

    public void removePrepareStmt(String stmtName) {
        this.preparedStmtCtxs.remove(stmtName);
    }

    public PrepareStmtContext getPreparedStmt(String stmtName) {
        return this.preparedStmtCtxs.get(stmtName);
    }

    public List<TableIf> getTables() {
        return tables;
    }

    public void setTables(List<TableIf> tables) {
        this.tables = tables;
    }

    public void closeTxn() {
        if (isTxnModel()) {
            if (isTxnBegin()) {
                try {
                    InsertStreamTxnExecutor executor = new InsertStreamTxnExecutor(getTxnEntry());
                    executor.abortTransaction();
                } catch (Exception e) {
                    LOG.error("db: {}, txnId: {}, rollback error.", currentDb,
                            txnEntry.getTxnConf().getTxnId(), e);
                }
            }
            txnEntry = null;
        }
    }

    public long getStmtId() {
        return stmtId;
    }

    public long getBackendId() {
        return backendId;
    }

    public void setBackendId(long backendId) {
        this.backendId = backendId;
    }

    public TUniqueId getLoadId() {
        return loadId;
    }

    public void setLoadId(TUniqueId loadId) {
        this.loadId = loadId;
    }

    public void setStreamLoadInfo(LoadTaskInfo streamLoadInfo) {
        this.streamLoadInfo = streamLoadInfo;
    }

    public LoadTaskInfo getStreamLoadInfo() {
        return streamLoadInfo;
    }

    public void setStmtId(long stmtId) {
        this.stmtId = stmtId;
    }

    public long getForwardedStmtId() {
        return forwardedStmtId;
    }

    public void setForwardedStmtId(long forwardedStmtId) {
        this.forwardedStmtId = forwardedStmtId;
    }

    public String getRemoteIP() {
        return remoteIP;
    }

    public void setRemoteIP(String remoteIP) {
        this.remoteIP = remoteIP;
    }

    public void setQueryDetail(QueryDetail queryDetail) {
        this.queryDetail = queryDetail;
    }

    public QueryDetail getQueryDetail() {
        return queryDetail;
    }

    public AuditEventBuilder getAuditEventBuilder() {
        return auditEventBuilder;
    }

    public void setThreadLocalInfo() {
        threadLocalInfo.set(this);
    }

    public long getCurrentDbId() {
        return currentDbId;
    }

    public TransactionEntry getTxnEntry() {
        return txnEntry;
    }

    public void setTxnEntry(TransactionEntry txnEntry) {
        this.txnEntry = txnEntry;
    }

    public void setEnv(Env env) {
        this.env = env;
        defaultCatalog = env.getInternalCatalog().getName();
    }

    public void setUserVar(SetVar setVar) {
        userVars.put(setVar.getVariable().toLowerCase(), setVar.getResult());
    }

    public @Nullable Literal getLiteralForUserVar(String varName) {
        varName = varName.toLowerCase();
        if (userVars.containsKey(varName)) {
            LiteralExpr literalExpr = userVars.get(varName);
            if (literalExpr instanceof BoolLiteral) {
                return Literal.of(((BoolLiteral) literalExpr).getValue());
            } else if (literalExpr instanceof IntLiteral) {
                return Literal.of(((IntLiteral) literalExpr).getValue());
            } else if (literalExpr instanceof FloatLiteral) {
                return Literal.of(((FloatLiteral) literalExpr).getValue());
            } else if (literalExpr instanceof DecimalLiteral) {
                return Literal.of(((DecimalLiteral) literalExpr).getValue());
            } else if (literalExpr instanceof StringLiteral) {
                return Literal.of(((StringLiteral) literalExpr).getValue());
            } else if (literalExpr instanceof NullLiteral) {
                return Literal.of(null);
            } else {
                return Literal.of("");
            }
        } else {
            // If there are no such user defined var, just return the NULL value.
            return Literal.of(null);
        }
    }

    // Get variable value through variable name, used to satisfy statement like `SELECT @@comment_version`
    public void fillValueForUserDefinedVar(VariableExpr desc) {
        String varName = desc.getName().toLowerCase();
        if (userVars.containsKey(varName)) {
            LiteralExpr literalExpr = userVars.get(varName);
            desc.setType(literalExpr.getType());
            if (literalExpr instanceof BoolLiteral) {
                desc.setBoolValue(((BoolLiteral) literalExpr).getValue());
            } else if (literalExpr instanceof IntLiteral) {
                desc.setIntValue(((IntLiteral) literalExpr).getValue());
            } else if (literalExpr instanceof FloatLiteral) {
                desc.setFloatValue(((FloatLiteral) literalExpr).getValue());
            } else if (literalExpr instanceof DecimalLiteral) {
                desc.setDecimalValue(((DecimalLiteral) literalExpr).getValue());
            } else if (literalExpr instanceof StringLiteral) {
                desc.setStringValue(((StringLiteral) literalExpr).getValue());
            } else if (literalExpr instanceof NullLiteral) {
                desc.setType(Type.NULL);
                desc.setIsNull();
            } else {
                desc.setType(Type.VARCHAR);
                desc.setStringValue("");
            }
        } else {
            // If there are no such user defined var, just fill the NULL value.
            desc.setType(Type.NULL);
            desc.setIsNull();
        }
    }

    public Env getEnv() {
        return env;
    }

    public boolean getNoAuth() {
        return noAuth;
    }

    public void setNoAuth(boolean noAuth) {
        this.noAuth = noAuth;
    }

    public String getQualifiedUser() {
        return qualifiedUser;
    }

    public void setQualifiedUser(String qualifiedUser) {
        this.qualifiedUser = qualifiedUser;
    }

    public boolean getIsTempUser() {
        return isTempUser;
    }

    public void setIsTempUser(boolean isTempUser) {
        this.isTempUser = isTempUser;
    }

    // for USER() function
    public UserIdentity getUserIdentity() {
        return new UserIdentity(qualifiedUser, remoteIP);
    }

    public UserIdentity getCurrentUserIdentity() {
        return currentUserIdentity;
    }

    public void setCurrentUserIdentity(UserIdentity currentUserIdentity) {
        this.currentUserIdentity = currentUserIdentity;
    }

    public SessionVariable getSessionVariable() {
        return sessionVariable;
    }

    public void setSessionVariable(SessionVariable sessionVariable) {
        this.sessionVariable = sessionVariable;
    }

    public ConnectScheduler getConnectScheduler() {
        return connectScheduler;
    }

    public void setConnectScheduler(ConnectScheduler connectScheduler) {
        this.connectScheduler = connectScheduler;
    }

    public MysqlCommand getCommand() {
        return command;
    }

    public void setCommand(MysqlCommand command) {
        this.command = command;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime() {
        startTime = System.currentTimeMillis();
        returnRows = 0;
    }

    public void updateReturnRows(int returnRows) {
        this.returnRows += returnRows;
    }

    public long getReturnRows() {
        return returnRows;
    }

    public void resetReturnRows() {
        returnRows = 0;
    }

    public int getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(int connectionId) {
        this.connectionId = connectionId;
    }

    public void resetLoginTime() {
        this.loginTime = System.currentTimeMillis();
    }

    public void addPreparedQuery(String preparedStatementId, String preparedQuery) {
        preparedQuerys.put(preparedStatementId, preparedQuery);
    }

    public String getPreparedQuery(String preparedStatementId) {
        return preparedQuerys.get(preparedStatementId);
    }

    public void removePreparedQuery(String preparedStatementId) {
        preparedQuerys.remove(preparedStatementId);
    }

    public void setRunningQuery(String runningQuery) {
        this.runningQuery = runningQuery;
    }

    public String getRunningQuery() {
        return runningQuery;
    }

    public void setResultFlightServerAddr(TNetworkAddress resultFlightServerAddr) {
        this.resultFlightServerAddr = resultFlightServerAddr;
    }

    public TNetworkAddress getResultFlightServerAddr() {
        return resultFlightServerAddr;
    }

    public void setResultInternalServiceAddr(TNetworkAddress resultInternalServiceAddr) {
        this.resultInternalServiceAddr = resultInternalServiceAddr;
    }

    public TNetworkAddress getResultInternalServiceAddr() {
        return resultInternalServiceAddr;
    }

    public void setResultOutputExprs(ArrayList<Expr> resultOutputExprs) {
        this.resultOutputExprs = resultOutputExprs;
    }

    public ArrayList<Expr> getResultOutputExprs() {
        return resultOutputExprs;
    }

    public void setFinstId(TUniqueId finstId) {
        this.finstId = finstId;
    }

    public TUniqueId getFinstId() {
        return finstId;
    }

    public void setReturnResultFromLocal(boolean returnResultFromLocal) {
        this.returnResultFromLocal = returnResultFromLocal;
    }

    public boolean isReturnResultFromLocal() {
        return returnResultFromLocal;
    }

    public String getPeerIdentity() {
        return peerIdentity;
    }

    public FlightSqlChannel getFlightSqlChannel() {
        throw new RuntimeException("getFlightSqlChannel not in flight sql connection");
    }

    public MysqlChannel getMysqlChannel() {
        return mysqlChannel;
    }

    public String getClientIP() {
        return getMysqlChannel().getRemoteHostPortString();
    }

    public QueryState getState() {
        return state;
    }

    public void setState(QueryState state) {
        this.state = state;
    }

    public MysqlCapability getCapability() {
        return capability;
    }

    public void setCapability(MysqlCapability capability) {
        this.capability = capability;
    }

    public MysqlCapability getServerCapability() {
        return serverCapability;
    }

    public String getDefaultCatalog() {
        return defaultCatalog;
    }

    public CatalogIf getCurrentCatalog() {
        // defaultCatalog is switched by SwitchStmt, so we don't need to check to exist of catalog.
        return getCatalog(defaultCatalog);
    }

    /**
     * Maybe return when catalogName is not exist. So need to check nullable.
     */
    public CatalogIf getCatalog(String catalogName) {
        String realCatalogName = catalogName == null ? defaultCatalog : catalogName;
        if (env == null) {
            return Env.getCurrentEnv().getCatalogMgr().getCatalog(realCatalogName);
        }
        return env.getCatalogMgr().getCatalog(realCatalogName);
    }

    public FunctionRegistry getFunctionRegistry() {
        if (env == null) {
            return Env.getCurrentEnv().getFunctionRegistry();
        }
        return env.getFunctionRegistry();
    }

    public void changeDefaultCatalog(String catalogName) {
        defaultCatalog = catalogName;
        currentDb = "";
        currentDbId = -1;
    }

    public String getDatabase() {
        return currentDb;
    }

    public void setDatabase(String db) {
        currentDb = db;
        Optional<DatabaseIf> dbInstance = getCurrentCatalog().getDb(db);
        currentDbId = dbInstance.map(DatabaseIf::getId).orElse(-1L);
    }

    public void setExecutor(StmtExecutor executor) {
        this.executor = executor;
    }

    public StmtExecutor getExecutor() {
        return executor;
    }

    protected void closeChannel() {
        if (mysqlChannel != null) {
            mysqlChannel.close();
        }
    }

    public void cleanup() {
        closeChannel();
        threadLocalInfo.remove();
        returnRows = 0;
    }

    public boolean isKilled() {
        return isKilled;
    }

    // Set kill flag to true;
    public void setKilled() {
        isKilled = true;
    }

    public void setQueryId(TUniqueId queryId) {
        this.queryId = queryId;
        if (connectScheduler != null && !Strings.isNullOrEmpty(traceId)) {
            connectScheduler.putTraceId2QueryId(traceId, queryId);
        }
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String traceId() {
        return traceId;
    }

    public TUniqueId queryId() {
        return queryId;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setCluster(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getSqlHash() {
        return sqlHash;
    }

    public void setSqlHash(String sqlHash) {
        this.sqlHash = sqlHash;
    }

    public JSONObject getMinidump() {
        return minidump;
    }

    public void setMinidump(JSONObject minidump) {
        this.minidump = minidump;
    }

    public StatementContext getStatementContext() {
        return statementContext;
    }

    public void setStatementContext(StatementContext statementContext) {
        this.statementContext = statementContext;
    }

    public void setResultSinkType(TResultSinkType resultSinkType) {
        this.resultSinkType = resultSinkType;
    }

    public String getRemoteHostPortString() {
        return getMysqlChannel().getRemoteHostPortString();
    }

    // kill operation with no protect.
    public void kill(boolean killConnection) {
        LOG.warn("kill query from {}, kill mysql connection: {}", getRemoteHostPortString(), killConnection);

        if (killConnection) {
            isKilled = true;
            // Close channel to break connection with client
            closeChannel();
        }
        // Now, cancel running query.
        cancelQuery();
    }

    public void cancelQuery() {
        StmtExecutor executorRef = executor;
        if (executorRef != null) {
            executorRef.cancel();
        }
    }

    public void checkTimeout(long now) {
        if (startTime <= 0) {
            return;
        }

        long delta = now - startTime;
        boolean killFlag = false;
        boolean killConnection = false;
        if (command == MysqlCommand.COM_SLEEP) {
            if (delta > sessionVariable.getWaitTimeoutS() * 1000L) {
                // Need kill this connection.
                LOG.warn("kill wait timeout connection, remote: {}, wait timeout: {}",
                        getRemoteHostPortString(), sessionVariable.getWaitTimeoutS());

                killFlag = true;
                killConnection = true;
            }
        } else {
            String timeoutTag = "query";
            // insert stmt particularly
            if (executor != null && executor.isSyncLoadKindStmt()) {
                timeoutTag = "insert";
            }
            // to ms
            long timeout = getExecTimeout() * 1000L;
            if (delta > timeout) {
                LOG.warn("kill {} timeout, remote: {}, query timeout: {}",
                        timeoutTag, getRemoteHostPortString(), timeout);
                killFlag = true;
            }
        }

        if (killFlag) {
            kill(killConnection);
        }
    }

    // Helper to dump connection information.
    public ThreadInfo toThreadInfo(boolean isFull) {
        if (threadInfo == null) {
            threadInfo = new ThreadInfo();
        }
        threadInfo.isFull = isFull;
        return threadInfo;
    }

    public boolean isResourceTagsSet() {
        return isResourceTagsSet;
    }

    public Set<Tag> getResourceTags() {
        return resourceTags;
    }

    public void setResourceTags(Set<Tag> resourceTags) {
        this.resourceTags = resourceTags;
        this.isResourceTagsSet = !this.resourceTags.isEmpty();
    }

    public void setCurrentConnectedFEIp(String ip) {
        this.currentConnectedFEIp = ip;
    }

    public String getCurrentConnectedFEIp() {
        return currentConnectedFEIp;
    }

    /**
     * We calculate and get the exact execution timeout here, rather than setting
     * execution timeout in many other places.
     *
     * @return exact execution timeout
     */
    public int getExecTimeout() {
        if (executor != null && executor.isSyncLoadKindStmt()) {
            // particular for insert stmt, we can expand other type of timeout in the same way
            return Math.max(sessionVariable.getInsertTimeoutS(), sessionVariable.getQueryTimeoutS());
        } else if (executor != null && executor.isAnalyzeStmt()) {
            return sessionVariable.getAnalyzeTimeoutS();
        } else {
            // normal query stmt
            return sessionVariable.getQueryTimeoutS();
        }
    }

    public void setResultAttachedInfo(Map<String, String> resultAttachedInfo) {
        this.resultAttachedInfo = resultAttachedInfo;
    }

    public Map<String, String> getResultAttachedInfo() {
        return resultAttachedInfo;
    }

    public class ThreadInfo {
        public boolean isFull;

        public List<String> toRow(int connId, long nowMs, boolean showFe) {
            List<String> row = Lists.newArrayList();
            if (showFe) {
                row.add(Env.getCurrentEnv().getSelfNode().getHost());
            }
            if (connId == connectionId) {
                row.add("Yes");
            } else {
                row.add("");
            }
            row.add("" + connectionId);
            row.add(ClusterNamespace.getNameFromFullName(qualifiedUser));
            row.add(getRemoteHostPortString());
            row.add(TimeUtils.longToTimeString(loginTime));
            row.add(defaultCatalog);
            row.add(ClusterNamespace.getNameFromFullName(currentDb));
            row.add(command.toString());
            row.add("" + (nowMs - startTime) / 1000);
            row.add(state.toString());
            row.add(DebugUtil.printId(queryId));
            if (state.getStateType() == QueryState.MysqlStateType.ERR) {
                row.add(state.getErrorMessage());
            } else if (executor != null) {
                String sql = executor.getOriginStmtInString();
                if (!isFull) {
                    sql = sql.substring(0, Math.min(sql.length(), 100));
                }
                row.add(sql);
            } else {
                row.add("");
            }
            return row;
        }
    }


    public void startAcceptQuery(ConnectProcessor connectProcessor) {
        mysqlChannel.startAcceptQuery(this, connectProcessor);
    }

    public void suspendAcceptQuery() {
        mysqlChannel.suspendAcceptQuery();
    }

    public void resumeAcceptQuery() {
        mysqlChannel.resumeAcceptQuery();
    }

    public void stopAcceptQuery() throws IOException {
        mysqlChannel.stopAcceptQuery();
    }

    public String getQueryIdentifier() {
        return "stmt[" + stmtId + ", " + DebugUtil.printId(queryId) + "]";
    }

    // maybe user set cluster by SQL hint of session variable: cloud_cluster
    // so first check it and then get from connect context.
    public String getCurrentCloudCluster() {
        String cluster = getSessionVariable().getCloudCluster();
        if (Strings.isNullOrEmpty(cluster)) {
            cluster = getCloudCluster();
        }
        return cluster;
    }

    // Set cloud cluster by `use @clusterName`
    public void setCloudCluster(String cluster) {
        this.cloudCluster = cluster;
    }

    // The returned cluster is set by `use @clusterName`
    public String getCloudCluster() {
        return cloudCluster;
    }

    public StatsErrorEstimator getStatsErrorEstimator() {
        return statsErrorEstimator;
    }

    public void setStatsErrorEstimator(StatsErrorEstimator statsErrorEstimator) {
        this.statsErrorEstimator = statsErrorEstimator;
    }

    public void setWorkloadGroupName(String workloadGroupName) {
        this.workloadGroupName = workloadGroupName;
    }

    public String getWorkloadGroupName() {
        return this.workloadGroupName;
    }

    public void setInsertGroupCommit(long tableId, Backend backend) {
        insertGroupCommitTableToBeMap.put(tableId, backend);
    }

    public Backend getInsertGroupCommit(long tableId) {
        return insertGroupCommitTableToBeMap.get(tableId);
    }

    public boolean isSkipAuth() {
        return skipAuth;
    }

    public void setSkipAuth(boolean skipAuth) {
        this.skipAuth = skipAuth;
    }

    public int getNetReadTimeout() {
        return this.sessionVariable.getNetReadTimeout();
    }

    public int getNetWriteTimeout() {
        return this.sessionVariable.getNetWriteTimeout();
    }
}

