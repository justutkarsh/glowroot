/*
 * Copyright 2011-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.plugin.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.Span;
import org.glowroot.api.MetricName;
import org.glowroot.api.MetricTimer;
import org.glowroot.api.weaving.BindParameter;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.BindReturn;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.Mixin;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.plugin.jdbc.PreparedStatementMirror.ByteArrayParameterValue;
import org.glowroot.plugin.jdbc.PreparedStatementMirror.NullParameterValue;
import org.glowroot.plugin.jdbc.PreparedStatementMirror.StreamingParameterValue;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// many of the pointcuts are not restricted to pluginServices.isEnabled() because StatementMirrors
// must be tracked for their entire life
public class StatementAspect {

    private static final Logger logger = LoggerFactory.getLogger(StatementAspect.class);

    private static final PluginServices pluginServices = PluginServices.get("jdbc");

    private static final AtomicBoolean noSqlTextAvailableLoggedOnce = new AtomicBoolean();

    private static volatile boolean captureBindParameters;

    static {
        pluginServices.registerConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                captureBindParameters = pluginServices.getBooleanProperty("captureBindParameters");
            }
        });
        captureBindParameters = pluginServices.getBooleanProperty("captureBindParameters");
    }

    // ===================== Mixin =====================

    @Mixin(target = "java.sql.Statement")
    public static class HasStatementMirrorImpl implements HasStatementMirror {
        @Nullable
        private volatile StatementMirror statementMirror;
        @Override
        @Nullable
        public StatementMirror getGlowrootStatementMirror() {
            return statementMirror;
        }
        @Override
        public void setGlowrootStatementMirror(StatementMirror statementMirror) {
            this.statementMirror = statementMirror;
        }
    }

    // the method names are verbose to avoid conflict since they will become methods in all classes
    // that extend java.sql.Statement
    public interface HasStatementMirror {
        @Nullable
        StatementMirror getGlowrootStatementMirror();
        void setGlowrootStatementMirror(StatementMirror statementMirror);
    }

    // ===================== Statement Preparation =====================

    // capture the sql used to create the PreparedStatement
    @Pointcut(className = "java.sql.Connection", methodName = "prepare*",
            methodParameterTypes = {"java.lang.String", ".."}, ignoreSelfNested = true,
            metricName = "jdbc prepare")
    public static class PrepareAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(PrepareAdvice.class);
        @OnBefore
        @Nullable
        public static MetricTimer onBefore() {
            // don't capture if implementation detail of a DatabaseMetaData method
            // (can't use @IsEnabled since need @OnReturn to always execute)
            if (pluginServices.isEnabled()
                    && !DatabaseMetaDataAspect.isCurrentlyExecuting()) {
                return pluginServices.startMetric(metricName);
            } else {
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn PreparedStatement preparedStatement,
                @BindParameter String sql) {
            ((HasStatementMirror) preparedStatement)
                    .setGlowrootStatementMirror(new PreparedStatementMirror(sql));
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable MetricTimer metricTimer) {
            if (metricTimer != null) {
                metricTimer.stop();
            }
        }
    }

    // ================= Parameter Binding =================

    // capture the parameters that are bound to the PreparedStatement except
    // parameters bound via setNull(..)
    // see special case below to handle setNull()
    @Pointcut(className = "java.sql.PreparedStatement", methodName = "/(?!setNull$)set.*/",
            methodParameterTypes = {"int", "*", ".."}, ignoreSelfNested = true)
    public static class SetXAdvice {
        @OnReturn
        public static void onReturn(@BindReceiver PreparedStatement preparedStatement,
                @BindParameter int parameterIndex, @BindParameter Object x) {
            PreparedStatementMirror mirror = getPreparedStatementMirror(preparedStatement);
            if (x instanceof InputStream || x instanceof Reader) {
                mirror.setParameterValue(parameterIndex, new StreamingParameterValue(x));
            } else if (x instanceof byte[]) {
                boolean displayAsHex = JdbcPluginProperties.displayBinaryParameterAsHex(
                        mirror.getSql(), parameterIndex);
                mirror.setParameterValue(parameterIndex, new ByteArrayParameterValue((byte[]) x,
                        displayAsHex));
            } else {
                mirror.setParameterValue(parameterIndex, x);
            }
        }
    }

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "setNull",
            methodParameterTypes = {"int", "int", ".."}, ignoreSelfNested = true)
    public static class SetNullAdvice {
        @OnReturn
        public static void onReturn(@BindReceiver PreparedStatement preparedStatement,
                @BindParameter int parameterIndex) {
            getPreparedStatementMirror(preparedStatement).setParameterValue(parameterIndex,
                    new NullParameterValue());
        }
    }

    // ================== Statement Batching ==================

    @Pointcut(className = "java.sql.Statement", methodName = "addBatch",
            methodParameterTypes = {"java.lang.String"}, ignoreSelfNested = true)
    public static class StatementAddBatchAdvice {
        @OnReturn
        public static void onReturn(@BindReceiver Statement statement,
                @BindParameter String sql) {
            getStatementMirror(statement).addBatch(sql);
        }
    }

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "addBatch",
            ignoreSelfNested = true)
    public static class PreparedStatementAddBatchAdvice {
        @OnReturn
        public static void onReturn(@BindReceiver PreparedStatement preparedStatement) {
            getPreparedStatementMirror(preparedStatement).addBatch();
        }
    }

    // Statement.clearBatch() can be used to re-initiate a prepared statement
    // that has been cached from a previous usage
    @Pointcut(className = "java.sql.Statement", methodName = "clearBatch")
    public static class ClearBatchAdvice {
        @OnReturn
        public static void onReturn(@BindReceiver Statement statement) {
            StatementMirror mirror = getStatementMirror(statement);
            mirror.clearBatch();
        }
    }

    // =================== Statement Execution ===================

    @Pointcut(className = "java.sql.Statement", methodName = "execute*",
            methodParameterTypes = {"java.lang.String", ".."}, ignoreSelfNested = true,
            metricName = "jdbc execute")
    public static class StatementExecuteAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(StatementExecuteAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return !DatabaseMetaDataAspect.isCurrentlyExecuting();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@BindReceiver Statement statement,
                @BindParameter String sql) {
            StatementMirror mirror = getStatementMirror(statement);
            if (pluginServices.isEnabled()) {
                JdbcMessageSupplier jdbcMessageSupplier = JdbcMessageSupplier.create(sql);
                mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                return pluginServices.startSpan(jdbcMessageSupplier, metricName);
            } else {
                // clear lastJdbcMessageSupplier so that its numRows won't be updated if the plugin
                // is re-enabled in the middle of iterating over a different result set
                mirror.setLastJdbcMessageSupplier(null);
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                        MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithError(ErrorMessage.from(t));
            }
        }
    }

    // executeBatch is not included since it is handled separately (below)
    @Pointcut(className = "java.sql.PreparedStatement",
            methodName = "execute|executeQuery|executeUpdate", ignoreSelfNested = true,
            metricName = "jdbc execute")
    public static class PreparedStatementExecuteAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(PreparedStatementExecuteAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return !DatabaseMetaDataAspect.isCurrentlyExecuting();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@BindReceiver PreparedStatement preparedStatement) {
            PreparedStatementMirror mirror = getPreparedStatementMirror(preparedStatement);
            if (pluginServices.isEnabled()) {
                JdbcMessageSupplier jdbcMessageSupplier;
                if (captureBindParameters) {
                    jdbcMessageSupplier = JdbcMessageSupplier.createWithParameters(mirror);
                } else {
                    jdbcMessageSupplier = JdbcMessageSupplier.create(mirror.getSql());
                }
                mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                return pluginServices.startSpan(jdbcMessageSupplier, metricName);
            } else {
                // clear lastJdbcMessageSupplier so that its numRows won't be updated if the plugin
                // is re-enabled in the middle of iterating over a different result set
                mirror.setLastJdbcMessageSupplier(null);
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                        MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithError(ErrorMessage.from(t));
            }
        }
    }

    @Pointcut(className = "java.sql.Statement", methodName = "executeBatch",
            ignoreSelfNested = true, metricName = "jdbc execute")
    public static class StatementExecuteBatchAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(StatementExecuteBatchAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return !DatabaseMetaDataAspect.isCurrentlyExecuting();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@BindReceiver Statement statement) {
            if (statement instanceof PreparedStatement) {
                PreparedStatementMirror mirror =
                        getPreparedStatementMirror((PreparedStatement) statement);
                if (pluginServices.isEnabled()) {
                    JdbcMessageSupplier jdbcMessageSupplier;
                    if (captureBindParameters) {
                        jdbcMessageSupplier =
                                JdbcMessageSupplier.createWithBatchedParameters(mirror);
                    } else {
                        jdbcMessageSupplier = JdbcMessageSupplier.create(mirror.getSql());
                    }
                    mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                    return pluginServices.startSpan(jdbcMessageSupplier, metricName);
                } else {
                    // clear lastJdbcMessageSupplier so that its numRows won't be updated if the
                    // plugin is re-enabled in the middle of iterating over a different result set
                    mirror.setLastJdbcMessageSupplier(null);
                    return null;
                }
            } else {
                StatementMirror mirror = getStatementMirror(statement);
                if (pluginServices.isEnabled()) {
                    JdbcMessageSupplier jdbcMessageSupplier =
                            JdbcMessageSupplier.createWithBatchedSqls(mirror);
                    mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                    return pluginServices.startSpan(jdbcMessageSupplier, metricName);
                } else {
                    // clear lastJdbcMessageSupplier so that its numRows won't be updated if the
                    // plugin is re-enabled in the middle of iterating over a different result set
                    mirror.setLastJdbcMessageSupplier(null);
                    return null;
                }
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                        MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithError(ErrorMessage.from(t));
            }
        }
    }

    // ================== Statement Closing ==================

    @Pointcut(className = "java.sql.Statement", methodName = "close", ignoreSelfNested = true,
            metricName = "jdbc statement close")
    public static class CloseAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(CloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return pluginServices.isEnabled()
                    && !DatabaseMetaDataAspect.isCurrentlyExecuting();
        }
        @OnBefore
        public static MetricTimer onBefore(@BindReceiver Statement statement) {
            // help out gc a little by clearing the weak reference, don't want to solely rely on
            // this (and use strong reference) in case a jdbc driver implementation closes
            // statements in finalize by calling an internal method and not calling public close()
            getStatementMirror(statement).setLastJdbcMessageSupplier(null);
            return pluginServices.startMetric(metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler MetricTimer metricTimer) {
            metricTimer.stop();
        }
    }

    private static StatementMirror getStatementMirror(Statement statement) {
        StatementMirror mirror = ((HasStatementMirror) statement).getGlowrootStatementMirror();
        if (mirror == null) {
            mirror = new StatementMirror();
            ((HasStatementMirror) statement).setGlowrootStatementMirror(mirror);
        }
        return mirror;
    }

    private static PreparedStatementMirror getPreparedStatementMirror(
            PreparedStatement preparedStatement) {
        PreparedStatementMirror mirror = (PreparedStatementMirror)
                ((HasStatementMirror) preparedStatement).getGlowrootStatementMirror();
        if (mirror == null) {
            String databaseMetaDataMethodName =
                    DatabaseMetaDataAspect.getCurrentlyExecutingMethodName();
            if (databaseMetaDataMethodName != null) {
                // wrapping description in sql comment (/* */)
                mirror = new PreparedStatementMirror("/* internal prepared statement generated by"
                        + " java.sql.DatabaseMetaData." + databaseMetaDataMethodName + "() */");
                ((HasStatementMirror) preparedStatement).setGlowrootStatementMirror(mirror);
            } else {
                // wrapping description in sql comment (/* */)
                mirror = new PreparedStatementMirror("/* prepared statement generated outside of"
                        + " the java.sql.Connection.prepare*() public API, no sql text available"
                        + " */");
                ((HasStatementMirror) preparedStatement).setGlowrootStatementMirror(mirror);
                if (!noSqlTextAvailableLoggedOnce.getAndSet(true)) {
                    // this is only logged the first time it occurs
                    logger.warn("prepared statement generated outside of the"
                            + " java.sql.Connection.prepare*() public API, no sql text available",
                            new Throwable());
                }
            }
        }
        return mirror;
    }
}
