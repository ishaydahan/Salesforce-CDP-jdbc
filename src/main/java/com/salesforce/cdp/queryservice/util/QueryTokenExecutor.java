/*
 * Copyright (c) 2021, salesforce.com, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.salesforce.cdp.queryservice.util;

import com.salesforce.cdp.queryservice.core.QueryServiceConnection;
import com.salesforce.cdp.queryservice.enums.QueryEngineEnum;
import com.salesforce.cdp.queryservice.interceptors.MetadataCacheInterceptor;
import com.salesforce.cdp.queryservice.model.Token;
import com.salesforce.cdp.queryservice.util.internal.SFDefaultSocketFactoryWrapper;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeException;
import net.jodah.failsafe.RetryPolicy;
import okhttp3.OkHttpClient;

import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Slf4j
public class QueryTokenExecutor {

    protected static final Integer DEFAULT_MAX_RETRY = 3;
    private static final String DEFAULT_MAX_RETRY_STR = DEFAULT_MAX_RETRY.toString();
    protected static final OkHttpClient DEFAULT_CLIENT;

    static {
        DEFAULT_CLIENT = new OkHttpClient().newBuilder()
                .readTimeout(Constants.REST_TIME_OUT, TimeUnit.SECONDS)
                .connectTimeout(Constants.REST_TIME_OUT, TimeUnit.SECONDS)
                .callTimeout(Constants.REST_TIME_OUT, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .socketFactory(new SFDefaultSocketFactoryWrapper(false))
                .addInterceptor(new MetadataCacheInterceptor())
                .build();
    }

    protected final QueryServiceConnection connection;
    private final OkHttpClient client;

    public QueryTokenExecutor(QueryServiceConnection connection) {
        this(connection, DEFAULT_CLIENT);
    }

    public QueryTokenExecutor(QueryServiceConnection connection, OkHttpClient client) {
        // fixme: even though constructor is public currently, it is not possible
        //  for users to specify custom Client as part of connection creation
        this.connection = connection;

        client = client == null ? DEFAULT_CLIENT : client;

        // this makes query executor not reuse across requests
        this.client = updateClientWithSocketFactory(client, connection.isSocksProxyDisabled());

        // set TenantUrl in connection. This is mandatory in gRPC flow.
        if(QueryEngineEnum.HYPER == connection.getQueryEngineEnum()) {
            try {
                Map<String, String> token = getTokenWithTenantUrl();
                connection.setTenantUrl(token.get(Constants.TENANT_URL));
            } catch (SQLException e) {
                log.error("Unable to get tenantUrl. Error - ", e);
            }
        }
    }

    @Deprecated
    protected OkHttpClient createClient() {
        // todo: remove in next iteration
        return DEFAULT_CLIENT.newBuilder()
                .build();
    }

    protected Map<String, String> getTokenWithTenantUrl() throws SQLException {
        if (connection.getToken() != null && TokenHelper.isAlive(connection.getToken())) {
            return TokenHelper.getTokenWithUrl(connection.getToken());
        }
        // todo: add a wrapper for retry mechanism
        //  check if delay or backoff need to introduced b/w each retry
        RetryPolicy<Object> retryPolicy = new RetryPolicy<>()
                .handle(TokenException.class)
                .onRetry(e -> log.warn("Failure #{}. Retrying.", e.getAttemptCount()))
                .onRetriesExceeded(e -> log.warn("Failed to connect. Max retries exceeded."))
                .withMaxRetries(getMaxRetries(connection.getClientInfo()));
        try {
            // failsafe executes the given block and returns the results
            // the failures are handled according to the policies specified
            // Here, only one policy is used i.e, retry policy
            return Failsafe.with(retryPolicy)
                    .get(() -> {
                        Token token = TokenHelper.getToken(connection.getClientInfo(), client);
                        connection.setToken(token);
                        return TokenHelper.getTokenWithUrl(token);
                    });
        } catch (FailsafeException e) {
            if (e.getCause() != null) {
                throw new SQLException(e.getCause().getMessage(), e.getCause());
            }
            throw new SQLException(e);
        }
    }

    private int getMaxRetries(Properties properties) {
        try {
            return Integer.parseInt(properties.getProperty(Constants.MAX_RETRIES, DEFAULT_MAX_RETRY_STR));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_RETRY;
        }
    }

    protected static OkHttpClient updateClientWithSocketFactory(OkHttpClient client, boolean isSocksProxyDisabled) {
        if (isSocksProxyDisabled) {
            return client.newBuilder()
                    .socketFactory(new SFDefaultSocketFactoryWrapper(true))
                    .build();
        }
        return client;
    }

}
