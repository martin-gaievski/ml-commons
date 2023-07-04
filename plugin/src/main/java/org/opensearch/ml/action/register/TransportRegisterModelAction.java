/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.register;

import static org.opensearch.ml.common.MLTask.STATE_FIELD;
import static org.opensearch.ml.common.MLTaskState.FAILED;
import static org.opensearch.ml.settings.MLCommonsSettings.ML_COMMONS_TRUSTED_CONNECTOR_ENDPOINTS_REGEX;
import static org.opensearch.ml.settings.MLCommonsSettings.ML_COMMONS_TRUSTED_URL_REGEX;
import static org.opensearch.ml.task.MLTaskManager.TASK_SEMAPHORE_TIMEOUT;
import static org.opensearch.ml.utils.MLExceptionUtils.logException;

import java.time.Instant;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.util.Strings;
import org.opensearch.action.ActionListener;
import org.opensearch.action.ActionListenerResponseHandler;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.client.Client;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.commons.authuser.User;
import org.opensearch.ml.cluster.DiscoveryNodeHelper;
import org.opensearch.ml.common.FunctionName;
import org.opensearch.ml.common.MLTask;
import org.opensearch.ml.common.MLTaskState;
import org.opensearch.ml.common.MLTaskType;
import org.opensearch.ml.common.transport.connector.MLCreateConnectorAction;
import org.opensearch.ml.common.transport.connector.MLCreateConnectorInput;
import org.opensearch.ml.common.transport.connector.MLCreateConnectorRequest;
import org.opensearch.ml.common.transport.connector.MLCreateConnectorResponse;
import org.opensearch.ml.common.transport.forward.MLForwardAction;
import org.opensearch.ml.common.transport.forward.MLForwardInput;
import org.opensearch.ml.common.transport.forward.MLForwardRequest;
import org.opensearch.ml.common.transport.forward.MLForwardRequestType;
import org.opensearch.ml.common.transport.forward.MLForwardResponse;
import org.opensearch.ml.common.transport.register.MLRegisterModelAction;
import org.opensearch.ml.common.transport.register.MLRegisterModelInput;
import org.opensearch.ml.common.transport.register.MLRegisterModelRequest;
import org.opensearch.ml.common.transport.register.MLRegisterModelResponse;
import org.opensearch.ml.engine.ModelHelper;
import org.opensearch.ml.helper.ConnectorAccessControlHelper;
import org.opensearch.ml.helper.ModelAccessControlHelper;
import org.opensearch.ml.indices.MLIndicesHandler;
import org.opensearch.ml.model.MLModelManager;
import org.opensearch.ml.stats.MLNodeLevelStat;
import org.opensearch.ml.stats.MLStats;
import org.opensearch.ml.task.MLTaskDispatcher;
import org.opensearch.ml.task.MLTaskManager;
import org.opensearch.ml.utils.MLExceptionUtils;
import org.opensearch.ml.utils.RestActionUtils;
import org.opensearch.tasks.Task;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class TransportRegisterModelAction extends HandledTransportAction<ActionRequest, MLRegisterModelResponse> {
    TransportService transportService;
    ModelHelper modelHelper;
    MLIndicesHandler mlIndicesHandler;
    MLModelManager mlModelManager;
    MLTaskManager mlTaskManager;
    ClusterService clusterService;
    ThreadPool threadPool;
    Client client;
    DiscoveryNodeHelper nodeFilter;
    MLTaskDispatcher mlTaskDispatcher;
    MLStats mlStats;
    volatile String trustedUrlRegex;

    private String trustedConnectorEndpointsRegex;

    ModelAccessControlHelper modelAccessControlHelper;

    ConnectorAccessControlHelper connectorAccessControlHelper;

    @Inject
    public TransportRegisterModelAction(
        TransportService transportService,
        ActionFilters actionFilters,
        ModelHelper modelHelper,
        MLIndicesHandler mlIndicesHandler,
        MLModelManager mlModelManager,
        MLTaskManager mlTaskManager,
        ClusterService clusterService,
        Settings settings,
        ThreadPool threadPool,
        Client client,
        DiscoveryNodeHelper nodeFilter,
        MLTaskDispatcher mlTaskDispatcher,
        MLStats mlStats,
        ModelAccessControlHelper modelAccessControlHelper,
        ConnectorAccessControlHelper connectorAccessControlHelper
    ) {
        super(MLRegisterModelAction.NAME, transportService, actionFilters, MLRegisterModelRequest::new);
        this.transportService = transportService;
        this.modelHelper = modelHelper;
        this.mlIndicesHandler = mlIndicesHandler;
        this.mlModelManager = mlModelManager;
        this.mlTaskManager = mlTaskManager;
        this.clusterService = clusterService;
        this.threadPool = threadPool;
        this.client = client;
        this.nodeFilter = nodeFilter;
        this.mlTaskDispatcher = mlTaskDispatcher;
        this.mlStats = mlStats;
        this.modelAccessControlHelper = modelAccessControlHelper;
        this.connectorAccessControlHelper = connectorAccessControlHelper;

        trustedUrlRegex = ML_COMMONS_TRUSTED_URL_REGEX.get(settings);
        clusterService.getClusterSettings().addSettingsUpdateConsumer(ML_COMMONS_TRUSTED_URL_REGEX, it -> trustedUrlRegex = it);

        trustedConnectorEndpointsRegex = ML_COMMONS_TRUSTED_CONNECTOR_ENDPOINTS_REGEX.get(settings);
        clusterService
            .getClusterSettings()
            .addSettingsUpdateConsumer(ML_COMMONS_TRUSTED_CONNECTOR_ENDPOINTS_REGEX, it -> trustedConnectorEndpointsRegex = it);
    }

    @Override
    protected void doExecute(Task task, ActionRequest request, ActionListener<MLRegisterModelResponse> listener) {
        User user = RestActionUtils.getUserContext(client);
        MLRegisterModelRequest registerModelRequest = MLRegisterModelRequest.fromActionRequest(request);
        MLRegisterModelInput registerModelInput = registerModelRequest.getRegisterModelInput();
        modelAccessControlHelper
            .validateModelGroupAccess(user, registerModelInput.getModelGroupId(), client, ActionListener.wrap(access -> {
                if (!access) {
                    log.error("You don't have permissions to perform this operation on this model.");
                    listener.onFailure(new IllegalArgumentException("You don't have permissions to perform this operation on this model."));
                } else {
                    doRegister(registerModelInput, listener);
                }
            }, listener::onFailure));
    }

    private void doRegister(MLRegisterModelInput registerModelInput, ActionListener<MLRegisterModelResponse> listener) {
        FunctionName functionName = registerModelInput.getFunctionName();
        if (FunctionName.REMOTE == functionName) {
            if (Strings.isNotBlank(registerModelInput.getConnectorId())) {
                connectorAccessControlHelper.validateConnectorAccess(client, registerModelInput.getConnectorId(), ActionListener.wrap(r -> {
                    if (Boolean.TRUE.equals(r)) {
                        registerModel(registerModelInput, listener);
                    } else {
                        listener.onFailure(new IllegalArgumentException("You don't have permission to use the connector provided, connector id: " + registerModelInput.getConnectorId()));
                    }
                }, e -> {
                    log
                        .error(
                            "You don't have permission to use the connector provided, connector id: "
                                + registerModelInput.getConnectorId(),
                            e
                        );
                    listener.onFailure(e);
                }));
            } else {
                validateInternalConnector(registerModelInput);
                ActionListener<MLCreateConnectorResponse> dryRunResultListener = ActionListener.wrap(res -> {
                    log.info("Dry run create connector successfully");
                    registerModel(registerModelInput, listener);
                }, e -> {
                    log.error(e.getMessage(), e);
                    listener.onFailure(e);
                });
                MLCreateConnectorRequest mlCreateConnectorRequest = createConnectorRequest();
                client.execute(MLCreateConnectorAction.INSTANCE, mlCreateConnectorRequest, dryRunResultListener);
            }
        } else {
            registerModel(registerModelInput, listener);
        }
    }

    private MLCreateConnectorRequest createConnectorRequest() {
        MLCreateConnectorInput createConnectorInput = MLCreateConnectorInput.builder().name("dryRunConnector").build();
        return new MLCreateConnectorRequest(createConnectorInput);
    }

    private void validateInternalConnector(MLRegisterModelInput registerModelInput) {
        if (registerModelInput.getConnector() == null) {
            log.error("You must provide connector content when creating a remote model without providing connector id!");
            throw new IllegalArgumentException("You must provide connector content when creating a remote model without connector id!");
        }
        if (registerModelInput.getConnector().getPredictEndpoint() == null) {
            log.error("Connector endpoint is required when creating a remote model without connector id!");
            throw new IllegalArgumentException("Connector endpoint is required when creating a remote model without connector id!");
        }
        // check if the connector url is trusted
        Pattern pattern = Pattern.compile(trustedConnectorEndpointsRegex);
        Matcher matcher = pattern.matcher(registerModelInput.getConnector().getPredictEndpoint());
        if (!matcher.matches()) {
            log
                .error(
                    "Not allowed URL in connector for remote model, URL is: "
                        + registerModelInput.getConnector().getPredictEndpoint()
                        + ", trusted connector endpoint regex is: "
                        + trustedConnectorEndpointsRegex
                );
            throw new IllegalArgumentException(
                "Not allowed URL in connector for remote model, URL is: "
                    + registerModelInput.getConnector().getPredictEndpoint()
                    + ", trusted connector endpoint regex is: "
                    + trustedConnectorEndpointsRegex
            );
        }
    }

    private void registerModel(MLRegisterModelInput registerModelInput, ActionListener<MLRegisterModelResponse> listener) {
        Pattern pattern = Pattern.compile(trustedUrlRegex);
        String url = registerModelInput.getUrl();
        if (url != null) {
            boolean validUrl = pattern.matcher(url).find();
            if (!validUrl) {
                throw new IllegalArgumentException("URL can't match trusted url regex");
            }
        }
        // mlStats.getStat(MLNodeLevelStat.ML_NODE_EXECUTING_TASK_COUNT).increment();
        mlStats.getStat(MLNodeLevelStat.ML_NODE_TOTAL_REQUEST_COUNT).increment();
        // //TODO: track executing task; track register failures
        // mlStats.createCounterStatIfAbsent(FunctionName.TEXT_EMBEDDING,
        // ActionName.REGISTER,
        // MLActionLevelStat.ML_ACTION_REQUEST_COUNT).increment();
        boolean isAsync = registerModelInput.getFunctionName() != FunctionName.REMOTE;
        MLTask mlTask = MLTask
            .builder()
            .async(isAsync)
            .taskType(MLTaskType.REGISTER_MODEL)
            .functionName(registerModelInput.getFunctionName())
            .createTime(Instant.now())
            .lastUpdateTime(Instant.now())
            .state(MLTaskState.CREATED)
            .workerNodes(ImmutableList.of(clusterService.localNode().getId()))
            .build();

        if (!isAsync) {
            mlTaskManager.createMLTask(mlTask, ActionListener.wrap(response -> {
                String taskId = response.getId();
                mlTask.setTaskId(taskId);
                mlModelManager.registerMLModel(registerModelInput, mlTask, listener);
            }, e -> {
                logException("Failed to register model", e, log);
                listener.onFailure(e);
            }));
            return;
        }
        mlTaskDispatcher.dispatch(ActionListener.wrap(node -> {
            String nodeId = node.getId();
            mlTask.setWorkerNodes(ImmutableList.of(nodeId));

            mlTaskManager.createMLTask(mlTask, ActionListener.wrap(response -> {
                String taskId = response.getId();
                mlTask.setTaskId(taskId);
                listener.onResponse(new MLRegisterModelResponse(taskId, MLTaskState.CREATED.name()));

                ActionListener<MLForwardResponse> forwardActionListener = ActionListener.wrap(res -> {
                    log.debug("Register model response: " + res);
                    if (!clusterService.localNode().getId().equals(nodeId)) {
                        mlTaskManager.remove(taskId);
                    }
                }, ex -> {
                    logException("Failed to register model", ex, log);
                    mlTaskManager
                        .updateMLTask(
                            taskId,
                            ImmutableMap.of(MLTask.ERROR_FIELD, MLExceptionUtils.getRootCauseMessage(ex), STATE_FIELD, FAILED),
                            TASK_SEMAPHORE_TIMEOUT,
                            true
                        );
                });
                try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
                    mlTaskManager.add(mlTask, Arrays.asList(nodeId));
                    MLForwardInput forwardInput = MLForwardInput
                        .builder()
                        .requestType(MLForwardRequestType.REGISTER_MODEL)
                        .registerModelInput(registerModelInput)
                        .mlTask(mlTask)
                        .build();
                    MLForwardRequest forwardRequest = new MLForwardRequest(forwardInput);
                    transportService
                        .sendRequest(
                            node,
                            MLForwardAction.NAME,
                            forwardRequest,
                            new ActionListenerResponseHandler<>(forwardActionListener, MLForwardResponse::new)
                        );
                } catch (Exception e) {
                    forwardActionListener.onFailure(e);
                }
            }, e -> {
                logException("Failed to register model", e, log);
                listener.onFailure(e);
            }));
        }, e -> {
            logException("Failed to register model", e, log);
            listener.onFailure(e);
        }));
    }
}