/*
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.catalog.ui.metacard;

import static ddf.catalog.util.impl.ResultIterable.resultIterable;
import static javax.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.codice.ddf.catalog.ui.metacard.query.util.QueryAttributes.QUERY_TAG;
import static org.codice.gsonsupport.GsonTypeAdapters.LIST_STRING;
import static org.codice.gsonsupport.GsonTypeAdapters.MAP_STRING_TO_OBJECT_TYPE;
import static spark.Spark.after;
import static spark.Spark.delete;
import static spark.Spark.exception;
import static spark.Spark.get;
import static spark.Spark.patch;
import static spark.Spark.post;
import static spark.Spark.put;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.ByteSource;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import ddf.catalog.CatalogFramework;
import ddf.catalog.content.data.ContentItem;
import ddf.catalog.content.data.impl.ContentItemImpl;
import ddf.catalog.content.operation.impl.CreateStorageRequestImpl;
import ddf.catalog.content.operation.impl.UpdateStorageRequestImpl;
import ddf.catalog.core.versioning.DeletedMetacard;
import ddf.catalog.core.versioning.MetacardVersion;
import ddf.catalog.core.versioning.MetacardVersion.Action;
import ddf.catalog.core.versioning.impl.MetacardVersionImpl;
import ddf.catalog.data.Attribute;
import ddf.catalog.data.AttributeDescriptor;
import ddf.catalog.data.AttributeRegistry;
import ddf.catalog.data.AttributeType;
import ddf.catalog.data.BinaryContent;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.MetacardType;
import ddf.catalog.data.Result;
import ddf.catalog.data.impl.AttributeImpl;
import ddf.catalog.data.impl.MetacardImpl;
import ddf.catalog.data.impl.ResultImpl;
import ddf.catalog.data.impl.types.SecurityAttributes;
import ddf.catalog.data.types.Core;
import ddf.catalog.federation.FederationException;
import ddf.catalog.filter.FilterBuilder;
import ddf.catalog.operation.DeleteResponse;
import ddf.catalog.operation.QueryResponse;
import ddf.catalog.operation.ResourceResponse;
import ddf.catalog.operation.UpdateResponse;
import ddf.catalog.operation.impl.CreateRequestImpl;
import ddf.catalog.operation.impl.DeleteRequestImpl;
import ddf.catalog.operation.impl.QueryImpl;
import ddf.catalog.operation.impl.QueryRequestImpl;
import ddf.catalog.operation.impl.ResourceRequestById;
import ddf.catalog.operation.impl.SourceResponseImpl;
import ddf.catalog.operation.impl.UpdateRequestImpl;
import ddf.catalog.resource.ResourceNotFoundException;
import ddf.catalog.resource.ResourceNotSupportedException;
import ddf.catalog.source.IngestException;
import ddf.catalog.source.SourceUnavailableException;
import ddf.catalog.source.UnsupportedQueryException;
import ddf.catalog.transform.QueryResponseTransformer;
import ddf.catalog.util.impl.ResultIterable;
import ddf.security.SubjectIdentity;
import ddf.security.SubjectOperations;
import ddf.security.audit.SecurityLogger;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.ws.rs.NotFoundException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.ExecutionException;
import org.codice.ddf.catalog.ui.config.ConfigurationApplication;
import org.codice.ddf.catalog.ui.enumeration.ExperimentalEnumerationExtractor;
import org.codice.ddf.catalog.ui.metacard.associations.Associated;
import org.codice.ddf.catalog.ui.metacard.edit.AttributeChange;
import org.codice.ddf.catalog.ui.metacard.edit.MetacardChanges;
import org.codice.ddf.catalog.ui.metacard.history.HistoryResponse;
import org.codice.ddf.catalog.ui.metacard.internal.OperationPropertySupplier;
import org.codice.ddf.catalog.ui.metacard.notes.NoteConstants;
import org.codice.ddf.catalog.ui.metacard.notes.NoteMetacard;
import org.codice.ddf.catalog.ui.metacard.notes.NoteUtil;
import org.codice.ddf.catalog.ui.metacard.query.data.metacard.QueryMetacardImpl;
import org.codice.ddf.catalog.ui.metacard.transformer.CsvTransformImpl;
import org.codice.ddf.catalog.ui.metacard.validation.Validator;
import org.codice.ddf.catalog.ui.metacard.workspace.WorkspaceConstants;
import org.codice.ddf.catalog.ui.metacard.workspace.WorkspaceMetacardImpl;
import org.codice.ddf.catalog.ui.metacard.workspace.transformer.WorkspaceTransformer;
import org.codice.ddf.catalog.ui.metacard.workspace.transformer.impl.AssociatedQueryMetacardsHandler;
import org.codice.ddf.catalog.ui.query.monitor.api.WorkspaceService;
import org.codice.ddf.catalog.ui.subscription.SubscriptionsPersistentStore;
import org.codice.ddf.catalog.ui.util.EndpointUtil;
import org.codice.ddf.security.Security;
import org.codice.gsonsupport.GsonTypeAdapters.DateLongFormatTypeAdapter;
import org.codice.gsonsupport.GsonTypeAdapters.LongDoubleTypeAdapter;
import org.opengis.filter.Filter;
import org.opengis.filter.sort.SortBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.servlet.SparkApplication;

public class MetacardApplication implements SparkApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(MetacardApplication.class);

  private static final String UPDATE_ERROR_MESSAGE = "Item is either restricted or not found.";

  private static final Set<Action> CONTENT_ACTIONS =
      ImmutableSet.of(Action.VERSIONED_CONTENT, Action.DELETED_CONTENT);

  private static final Set<Action> DELETE_ACTIONS =
      ImmutableSet.of(Action.DELETED, Action.DELETED_CONTENT);

  private static final String ERROR_RESPONSE_TYPE = "error";

  private static final String SUCCESS_RESPONSE_TYPE = "success";

  private static final MetacardType SECURITY_ATTRIBUTES = new SecurityAttributes();

  private static final Type METACARD_CHANGES_LIST_TYPE =
      new TypeToken<List<MetacardChanges>>() {}.getType();

  private static final Type ATTRIBUTE_CHANGE_TYPE = new TypeToken<AttributeChange>() {}.getType();

  private static final Type ASSOCIATED_EDGE_LIST_TYPE =
      new TypeToken<List<Associated.Edge>>() {}.getType();

  private static final int PAGE_SIZE = 250;

  private static final Gson GSON =
      new GsonBuilder()
          .disableHtmlEscaping()
          .serializeNulls()
          .registerTypeAdapterFactory(LongDoubleTypeAdapter.FACTORY)
          .registerTypeAdapter(Date.class, new DateLongFormatTypeAdapter())
          .create();

  private final CatalogFramework catalogFramework;
  private final FilterBuilder filterBuilder;
  private final EndpointUtil util;
  private final Validator validator;
  private final WorkspaceTransformer transformer;
  private final ExperimentalEnumerationExtractor enumExtractor;
  private final SubscriptionsPersistentStore subscriptions;
  private final List<MetacardType> types;
  private final Associated associated;
  private final QueryResponseTransformer csvQueryResponseTransformer;
  private final SubjectIdentity subjectIdentity;

  private final AttributeRegistry attributeRegistry;

  private final ConfigurationApplication configuration;

  private final NoteUtil noteUtil;

  private final WorkspaceService workspaceService;

  private final AssociatedQueryMetacardsHandler queryMetacardsHandler;

  private final Security security;

  private OperationPropertySupplier operationPropertySupplier;

  private SubjectOperations subjectOperations;

  private SecurityLogger securityLogger;

  public MetacardApplication(
      CatalogFramework catalogFramework,
      FilterBuilder filterBuilder,
      EndpointUtil endpointUtil,
      Validator validator,
      WorkspaceTransformer transformer,
      ExperimentalEnumerationExtractor enumExtractor,
      SubscriptionsPersistentStore subscriptions,
      List<MetacardType> types,
      Associated associated,
      QueryResponseTransformer csvQueryResponseTransformer,
      AttributeRegistry attributeRegistry,
      ConfigurationApplication configuration,
      NoteUtil noteUtil,
      SubjectIdentity subjectIdentity,
      WorkspaceService workspaceService,
      AssociatedQueryMetacardsHandler queryMetacardsHandler,
      Security security) {
    this.catalogFramework = catalogFramework;
    this.filterBuilder = filterBuilder;
    this.util = endpointUtil;
    this.validator = validator;
    this.transformer = transformer;
    this.enumExtractor = enumExtractor;
    this.subscriptions = subscriptions;
    this.types = types;
    this.associated = associated;
    this.csvQueryResponseTransformer = csvQueryResponseTransformer;
    this.attributeRegistry = attributeRegistry;
    this.configuration = configuration;
    this.noteUtil = noteUtil;
    this.subjectIdentity = subjectIdentity;
    this.workspaceService = workspaceService;
    this.queryMetacardsHandler = queryMetacardsHandler;
    this.security = security;
  }

  public void addOperationPropertySupplier(OperationPropertySupplier operationPropertySupplier) {
    this.operationPropertySupplier = operationPropertySupplier;
  }

  public void removeOperationPropertySupplier(OperationPropertySupplier operationPropertySupplier) {
    this.operationPropertySupplier = null;
  }

  private String getSubjectEmail() {
    return subjectOperations.getEmailAddress(SecurityUtils.getSubject());
  }

  private String getSubjectIdentifier() {
    return subjectIdentity.getUniqueIdentifier(SecurityUtils.getSubject());
  }

  @SuppressWarnings("unchecked")
  @Override
  public void init() {
    get("/metacardtype", (req, res) -> util.getJson(util.getMetacardTypeMap()));

    get(
        "/metacard/:id",
        (req, res) -> {
          String id = req.params(":id");
          String storeId = req.queryParams("storeId");
          return util.metacardToJson(id, storeId);
        });

    get(
        "/metacard/:id/attribute/validation",
        (req, res) -> {
          String id = req.params(":id");
          return util.getJson(validator.getValidation(util.getMetacardById(id)));
        });

    get(
        "/metacard/:id/validation",
        (req, res) -> {
          String id = req.params(":id");
          return util.getJson(validator.getFullValidation(util.getMetacardById(id)));
        });
    get(
        "/metacard/:id/:storeId/attribute/validation",
        (req, res) -> {
          String id = req.params(":id");
          String storeId = req.params(":storeId");
          return util.getJson(validator.getValidation(util.getMetacardById(id, storeId)));
        });

    get(
        "/metacard/:id/:storeId/validation",
        (req, res) -> {
          String id = req.params(":id");
          String storeId = req.params(":storeId");
          return util.getJson(validator.getFullValidation(util.getMetacardById(id, storeId)));
        });
    post(
        "/prevalidate",
        APPLICATION_JSON,
        (req, res) -> {
          Map<String, Object> stringObjectMap =
              GSON.fromJson(util.safeGetBody(req), MAP_STRING_TO_OBJECT_TYPE);
          MetacardImpl metacard = new MetacardImpl();
          stringObjectMap
              .keySet()
              .stream()
              .map(s -> new AttributeImpl(s, (List<Serializable>) stringObjectMap.get(s)))
              .forEach(metacard::setAttribute);
          return util.getJson(validator.getValidation(metacard));
        });

    post(
        "/metacards",
        APPLICATION_JSON,
        (req, res) -> {
          List<String> ids = GSON.fromJson(util.safeGetBody(req), LIST_STRING);
          List<Metacard> metacards =
              util.getMetacardsWithTagById(ids, "*")
                  .values()
                  .stream()
                  .map(Result::getMetacard)
                  .collect(Collectors.toList());

          return util.metacardsToJson(metacards);
        });

    delete(
        "/metacards",
        APPLICATION_JSON,
        (req, res) -> {
          List<String> ids = GSON.fromJson(util.safeGetBody(req), LIST_STRING);
          DeleteResponse deleteResponse =
              catalogFramework.delete(
                  new DeleteRequestImpl(new ArrayList<>(ids), Metacard.ID, null));
          if (deleteResponse.getProcessingErrors() != null
              && !deleteResponse.getProcessingErrors().isEmpty()) {
            res.status(500);
            return ImmutableMap.of("message", "Unable to archive metacards.");
          }

          return ImmutableMap.of("message", "Successfully archived metacards.");
        },
        util::getJson);

    patch(
        "/metacards",
        APPLICATION_JSON,
        (req, res) -> {
          String body = util.safeGetBody(req);
          List<MetacardChanges> metacardChanges = GSON.fromJson(body, METACARD_CHANGES_LIST_TYPE);
          Set<String> storeIds =
              Optional.ofNullable(req.queryParams("storeId"))
                  .map(sourceListCSV -> sourceListCSV.split(","))
                  .map(Arrays::asList)
                  .map(list -> list.stream().map(String::trim).collect(Collectors.toSet()))
                  .orElse(null);
          UpdateResponse updateResponse =
              patchMetacards(metacardChanges, getSubjectIdentifier(), storeIds);
          if (updateResponse.getProcessingErrors() != null
              && !updateResponse.getProcessingErrors().isEmpty()) {
            res.status(500);
            return updateResponse.getProcessingErrors();
          }

          return body;
        });

    put(
        "/validate/attribute/:attribute",
        TEXT_PLAIN,
        (req, res) -> {
          String attribute = req.params(":attribute");
          String value = util.safeGetBody(req);
          return util.getJson(validator.validateAttribute(attribute, value));
        });

    get(
        "/history/:id/:storeId",
        (req, res) -> {
          String id = req.params(":id");
          String storeId = req.params(":storeId");
          List<Result> queryResponse = getMetacardHistory(id, storeId);
          if (queryResponse.isEmpty()) {
            res.status(204);
            return "[]";
          }
          List<HistoryResponse> response =
              queryResponse
                  .stream()
                  .map(Result::getMetacard)
                  .map(
                      mc ->
                          new HistoryResponse(
                              mc.getId(),
                              (String) mc.getAttribute(MetacardVersion.EDITED_BY).getValue(),
                              (Date) mc.getAttribute(MetacardVersion.VERSIONED_ON).getValue()))
                  .sorted(Comparator.comparing(HistoryResponse::getVersioned))
                  .collect(Collectors.toList());
          return util.getJson(response);
        });

    get(
        "/history/revert/:id/:revertid/:storeId",
        (req, res) -> {
          String id = req.params(":id");
          String revertId = req.params(":revertid");
          String storeId = req.params(":storeId");
          Metacard versionMetacard = revert(id, revertId, storeId);
          return util.metacardToJson(MetacardVersionImpl.toMetacard(versionMetacard));
        });

    get(
        "/associations/:id",
        (req, res) -> {
          String id = req.params(":id");
          return util.getJson(associated.getAssociations(id));
        });

    put(
        "/associations/:id",
        (req, res) -> {
          String id = req.params(":id");
          String body = util.safeGetBody(req);
          List<Associated.Edge> edges = GSON.fromJson(body, ASSOCIATED_EDGE_LIST_TYPE);
          associated.putAssociations(id, edges);
          return body;
        });

    post(
        "/subscribe/:id",
        (req, res) -> {
          String userid = getSubjectIdentifier();
          String email = getSubjectEmail();
          if (isEmpty(email)) {
            throw new NotFoundException(
                "Unable to subscribe to workspace, " + userid + " has no email address.");
          }
          String id = req.params(":id");
          subscriptions.addEmail(id, email);
          return ImmutableMap.of(
              "message", String.format("Successfully subscribed to id = %s.", id));
        },
        util::getJson);

    post(
        "/unsubscribe/:id",
        (req, res) -> {
          String userid = getSubjectIdentifier();
          String email = getSubjectEmail();
          if (isEmpty(email)) {
            throw new NotFoundException(
                "Unable to un-subscribe from workspace, " + userid + " has no email address.");
          }
          String id = req.params(":id");
          if (StringUtils.isEmpty(req.body())) {
            subscriptions.removeEmail(id, email);
            return ImmutableMap.of(
                "message", String.format("Successfully un-subscribed to id = %s.", id));
          } else {
            String body = req.body();
            AttributeChange attributeChange = GSON.fromJson(body, ATTRIBUTE_CHANGE_TYPE);
            subscriptions.removeEmails(id, new HashSet<>(attributeChange.getValues()));
            return ImmutableMap.of(
                "message",
                String.format(
                    "Successfully un-subscribed emails %s id = %s.",
                    attributeChange.getValues().toString(), id));
          }
        },
        util::getJson);

    get(
        "/workspaces/:id",
        (req, res) -> {
          String id = req.params(":id");
          String email = getSubjectEmail();
          Metacard metacard = util.getMetacardById(id);

          // NOTE: the isEmpty is to guard against users with no email (such as guest).
          boolean isSubscribed =
              !isEmpty(email) && subscriptions.getEmails(metacard.getId()).contains(email);

          return ImmutableMap.builder()
              .putAll(transformer.transform(metacard))
              .put("subscribed", isSubscribed)
              .build();
        },
        util::getJson);

    get(
        "/workspaces",
        (req, res) -> {
          String email = getSubjectEmail();
          // NOTE: the isEmpty is to guard against users with no email (such as guest).
          Set<String> ids =
              isEmpty(email) ? Collections.emptySet() : subscriptions.getSubscriptions(email);

          return util.getMetacardsByTag(WorkspaceConstants.WORKSPACE_TAG)
              .values()
              .stream()
              .map(Result::getMetacard)
              .map(
                  metacard -> {
                    boolean isSubscribed = ids.contains(metacard.getId());
                    try {
                      return ImmutableMap.builder()
                          .putAll(transformer.transform(metacard))
                          .put("subscribed", isSubscribed)
                          .build();
                    } catch (RuntimeException e) {
                      LOGGER.debug(
                          "Could not transform metacard. WARNING: This indicates there is invalid data in the system. Metacard title: '{}', id:'{}'",
                          metacard.getTitle(),
                          metacard.getId(),
                          e);
                    }
                    return null;
                  })
              .filter(Objects::nonNull)
              .collect(Collectors.toList());
        },
        util::getJson);

    post(
        "/workspaces",
        APPLICATION_JSON,
        (req, res) -> {
          Map<String, Object> incoming =
              GSON.fromJson(util.safeGetBody(req), MAP_STRING_TO_OBJECT_TYPE);

          List<QueryMetacardImpl> queries =
              ((List<Map<String, Object>>)
                      incoming.getOrDefault(
                          WorkspaceConstants.WORKSPACE_QUERIES, Collections.emptyList()))
                  .stream()
                  .map(this::jsonToQueryMetacard)
                  .collect(Collectors.toList());

          queryMetacardsHandler.create(Collections.emptyList(), queries);

          Metacard saved = saveMetacard(transformer.transform(incoming));
          Map<String, Object> response = transformer.transform(saved);

          res.status(201);
          return util.getJson(response);
        });

    put(
        "/workspaces/:id",
        APPLICATION_JSON,
        (req, res) -> {
          String id = req.params(":id");

          WorkspaceMetacardImpl existingWorkspace = workspaceService.getWorkspaceMetacard(id);
          List<String> existingQueryIds = existingWorkspace.getQueries();

          Map<String, Object> updatedWorkspace =
              GSON.fromJson(util.safeGetBody(req), MAP_STRING_TO_OBJECT_TYPE);

          List<QueryMetacardImpl> updatedQueryMetacards =
              ((List<Map<String, Object>>)
                      updatedWorkspace.getOrDefault(
                          WorkspaceConstants.WORKSPACE_QUERIES, Collections.emptyList()))
                  .stream()
                  .map(this::jsonToQueryMetacard)
                  .collect(Collectors.toList());

          List<String> updatedQueryIds =
              updatedQueryMetacards.stream().map(Metacard::getId).collect(Collectors.toList());

          List<QueryMetacardImpl> existingQueryMetacards =
              workspaceService.getQueryMetacards(existingWorkspace);

          queryMetacardsHandler.create(existingQueryIds, updatedQueryMetacards);
          queryMetacardsHandler.delete(existingQueryIds, updatedQueryIds);
          queryMetacardsHandler.update(
              existingQueryIds, existingQueryMetacards, updatedQueryMetacards);

          List<Map<String, String>> queryIdModel =
              updatedQueryIds
                  .stream()
                  .map(queryId -> ImmutableMap.of("id", queryId))
                  .collect(Collectors.toList());

          updatedWorkspace.put("queries", queryIdModel);
          Metacard metacard = transformer.transform(updatedWorkspace);
          metacard.setAttribute(new AttributeImpl(Core.ID, id));
          Metacard updated = updateMetacard(id, metacard);

          return transformer.transform(updated);
        },
        util::getJson);

    delete(
        "/workspaces/:id",
        APPLICATION_JSON,
        (req, res) -> {
          String id = req.params(":id");
          WorkspaceMetacardImpl workspace = workspaceService.getWorkspaceMetacard(id);

          String[] queryIds = workspace.getQueries().toArray(new String[0]);

          if (queryIds.length > 0) {
            catalogFramework.delete(new DeleteRequestImpl(queryIds));
          }

          catalogFramework.delete(new DeleteRequestImpl(id));

          subscriptions.removeSubscriptions(id);
          return ImmutableMap.of("message", "Successfully deleted.");
        },
        util::getJson);

    get(
        "/workspaces/:id/queries",
        (req, res) -> {
          String workspaceId = req.params(":id");
          WorkspaceMetacardImpl workspace = workspaceService.getWorkspaceMetacard(workspaceId);

          List<String> queryIds = workspace.getQueries();

          return util.getMetacardsWithTagById(queryIds, QUERY_TAG)
              .values()
              .stream()
              .map(Result::getMetacard)
              .map(transformer::transform)
              .collect(Collectors.toList());
        },
        util::getJson);

    get(
        "/enumerations/deprecated/:type",
        APPLICATION_JSON,
        (req, res) -> util.getJson(enumExtractor.getDeprecatedEnumerations(req.params(":type"))));

    get(
        "/enumerations/metacardtype/:type",
        APPLICATION_JSON,
        (req, res) -> util.getJson(enumExtractor.getEnumerations(req.params(":type"))));

    get(
        "/enumerations/attribute/:attribute",
        APPLICATION_JSON,
        (req, res) ->
            util.getJson(enumExtractor.getAttributeEnumerations(req.params(":attribute"))));

    get(
        "/localcatalogid",
        (req, res) ->
            String.format("{\"%s\":\"%s\"}", "local-catalog-id", catalogFramework.getId()));

    post(
        "/transform/csv",
        APPLICATION_JSON,
        (req, res) -> {
          String body = util.safeGetBody(req);
          CsvTransformImpl queryTransform = GSON.fromJson(body, CsvTransformImpl.class);
          Map<String, Object> transformMap = GSON.fromJson(body, MAP_STRING_TO_OBJECT_TYPE);
          queryTransform.setMetacards((List<Map<String, Object>>) transformMap.get("metacards"));

          List<Result> metacards =
              queryTransform
                  .getTransformedMetacards(types, attributeRegistry)
                  .stream()
                  .map(ResultImpl::new)
                  .collect(Collectors.toList());

          Set<String> matchedHiddenFields = Collections.emptySet();
          if (queryTransform.isApplyGlobalHidden()) {
            matchedHiddenFields = getHiddenFields(metacards);
          }

          SourceResponseImpl response =
              new SourceResponseImpl(null, metacards, Long.valueOf(metacards.size()));

          Map<String, Serializable> arguments =
              ImmutableMap.<String, Serializable>builder()
                  .put(
                      "hiddenFields",
                      new HashSet<>(
                          Sets.union(matchedHiddenFields, queryTransform.getHiddenFields())))
                  .put("columnOrder", new ArrayList<>(queryTransform.getColumnOrder()))
                  .put("aliases", new HashMap<>(queryTransform.getColumnAliasMap()))
                  .build();

          BinaryContent content = csvQueryResponseTransformer.transform(response, arguments);

          // Respond with content
          res.type("text/csv");
          String attachment =
              String.format("attachment;filename=export-%s.csv", Instant.now().toString());
          res.header("Content-Disposition", attachment);

          try (OutputStream servletOutputStream = res.raw().getOutputStream();
              InputStream resultStream = content.getInputStream()) {
            IOUtils.copy(resultStream, servletOutputStream);
          }
          return "";
        });

    post(
        "/annotations",
        (req, res) -> {
          Map<String, Object> incoming =
              GSON.fromJson(util.safeGetBody(req), MAP_STRING_TO_OBJECT_TYPE);
          String workspaceId = incoming.get("workspace").toString();
          String queryId = incoming.get("parent").toString();
          String annotation = incoming.get("note").toString();
          String user = getSubjectIdentifier();
          if (user == null) {
            res.status(401);
            return util.getResponseWrapper(
                ERROR_RESPONSE_TYPE,
                "You are not authorized to create notes! A user email is required. "
                    + "Please ensure you are logged in and/or have a valid email registered in the system.");
          }
          if (StringUtils.isBlank(annotation)) {
            res.status(400);
            return util.getResponseWrapper(ERROR_RESPONSE_TYPE, "No annotation!");
          }
          NoteMetacard noteMetacard = new NoteMetacard(queryId, user, annotation);

          Metacard workspaceMetacard = util.findWorkspace(workspaceId);

          if (workspaceMetacard == null) {
            res.status(404);
            return util.getResponseWrapper(
                ERROR_RESPONSE_TYPE, "Cannot find the workspace metacard!");
          }

          util.copyAttributes(workspaceMetacard, SECURITY_ATTRIBUTES, noteMetacard);

          Metacard note = saveMetacard(noteMetacard);

          securityLogger.auditWarn(
              "Attaching an annotation to a resource: resource={} annotation={}",
              SecurityUtils.getSubject(),
              workspaceId,
              noteMetacard.getId());

          Map<String, String> responseNote = noteUtil.getResponseNote(note);
          if (responseNote == null) {
            res.status(500);
            return util.getResponseWrapper(
                ERROR_RESPONSE_TYPE, "Cannot serialize note metacard to json!");
          }
          return util.getResponseWrapper(SUCCESS_RESPONSE_TYPE, util.getJson(responseNote));
        });

    get(
        "/annotations/:queryid",
        (req, res) -> {
          String queryId = req.params(":queryid");

          List<Metacard> retrievedMetacards =
              noteUtil.getAssociatedMetacardsByTwoAttributes(
                  NoteConstants.PARENT_ID, Core.METACARD_TAGS, queryId, "note");
          ArrayList<String> getResponse = new ArrayList<>();
          retrievedMetacards.sort(Comparator.comparing(Metacard::getCreatedDate));
          for (Metacard metacard : retrievedMetacards) {
            Map<String, String> responseNote = noteUtil.getResponseNote(metacard);
            if (responseNote != null) {
              getResponse.add(util.getJson(responseNote));
            }
          }
          return util.getResponseWrapper(SUCCESS_RESPONSE_TYPE, getResponse.toString());
        });

    put(
        "/annotations/:id",
        APPLICATION_JSON,
        (req, res) -> {
          Map<String, Object> incoming =
              GSON.fromJson(util.safeGetBody(req), MAP_STRING_TO_OBJECT_TYPE);
          String noteMetacardId = req.params(":id");
          String note = incoming.get("note").toString();
          Metacard metacard;
          try {
            metacard = util.getMetacardById(noteMetacardId);
          } catch (NotFoundException e) {
            LOGGER.debug("Note metacard was not found for updating. id={}", noteMetacardId);
            res.status(404);
            return util.getResponseWrapper(ERROR_RESPONSE_TYPE, "Note metacard was not found!");
          }

          Attribute attribute = metacard.getAttribute(Core.METACARD_OWNER);
          if (attribute != null
              && attribute.getValue() != null
              && !attribute.getValue().equals(getSubjectIdentifier())) {
            res.status(401);
            return util.getResponseWrapper(
                ERROR_RESPONSE_TYPE, "Owner of note metacard is invalid!");
          }
          metacard.setAttribute(new AttributeImpl(NoteConstants.COMMENT, note));
          metacard = updateMetacard(metacard.getId(), metacard);
          Map<String, String> responseNote = noteUtil.getResponseNote(metacard);
          return util.getResponseWrapper(SUCCESS_RESPONSE_TYPE, util.getJson(responseNote));
        });

    delete(
        "/annotations/:id",
        (req, res) -> {
          String noteToDeleteMetacardId = req.params(":id");
          Metacard metacard;
          try {
            metacard = util.getMetacardById(noteToDeleteMetacardId);
          } catch (NotFoundException e) {
            LOGGER.debug("Note metacard was not found for deleting. id={}", noteToDeleteMetacardId);
            res.status(404);
            return util.getResponseWrapper(ERROR_RESPONSE_TYPE, "Note metacard was not found!");
          }
          Attribute attribute = metacard.getAttribute(Core.METACARD_OWNER);
          if (attribute != null
              && attribute.getValue() != null
              && !attribute.getValue().equals(getSubjectIdentifier())) {
            res.status(401);
            return util.getResponseWrapper(
                ERROR_RESPONSE_TYPE, "Owner of note metacard is invalid!");
          }
          DeleteResponse deleteResponse =
              catalogFramework.delete(new DeleteRequestImpl(noteToDeleteMetacardId));
          if (deleteResponse.getDeletedMetacards() != null
              && !deleteResponse.getDeletedMetacards().isEmpty()) {
            Map<String, String> responseNote =
                noteUtil.getResponseNote(deleteResponse.getDeletedMetacards().get(0));
            return util.getResponseWrapper(SUCCESS_RESPONSE_TYPE, util.getJson(responseNote));
          }
          res.status(500);
          return util.getResponseWrapper(ERROR_RESPONSE_TYPE, "Could not delete note metacard!");
        });

    after((req, res) -> res.type(APPLICATION_JSON));

    exception(
        IngestException.class,
        (ex, req, res) -> {
          LOGGER.debug("Failed to ingest metacard", ex);
          res.status(404);
          res.header(CONTENT_TYPE, APPLICATION_JSON);
          res.body(util.getJson(ImmutableMap.of("message", UPDATE_ERROR_MESSAGE)));
        });

    exception(
        NotFoundException.class,
        (ex, req, res) -> {
          LOGGER.debug("Failed to find metacard.", ex);
          res.status(404);
          res.header(CONTENT_TYPE, APPLICATION_JSON);
          res.body(util.getJson(ImmutableMap.of("message", ex.getMessage())));
        });

    exception(
        NumberFormatException.class,
        (ex, req, res) -> {
          res.status(400);
          res.header(CONTENT_TYPE, APPLICATION_JSON);
          res.body(util.getJson(ImmutableMap.of("message", "Invalid values for numbers")));
        });

    exception(EntityTooLargeException.class, util::handleEntityTooLargeException);

    exception(IOException.class, util::handleIOException);

    exception(RuntimeException.class, util::handleRuntimeException);
  }

  private Metacard revert(String id, String revertId, String storeId)
      throws UnsupportedQueryException, SourceUnavailableException, FederationException,
          IngestException, ResourceNotFoundException, IOException, ResourceNotSupportedException {
    try {
      Map<String, Serializable> properties =
          operationPropertySupplier == null
              ? new HashMap<>()
              : operationPropertySupplier.properties(OperationPropertySupplier.QUERY_TYPE);

      Metacard versionMetacard = util.getMetacardById(revertId, properties);

      List<Result> queryResponse = getMetacardHistory(id, storeId);
      if (queryResponse.isEmpty()) {
        throw new NotFoundException("Could not find metacard with id: " + id);
      }

      Optional<Metacard> contentVersion =
          queryResponse
              .stream()
              .map(Result::getMetacard)
              .filter(
                  mc ->
                      getVersionedOnDate(mc).isAfter(getVersionedOnDate(versionMetacard))
                          || getVersionedOnDate(mc).equals(getVersionedOnDate(versionMetacard)))
              .filter(mc -> CONTENT_ACTIONS.contains(Action.ofMetacard(mc)))
              .filter(mc -> mc.getResourceURI() != null)
              .filter(mc -> ContentItem.CONTENT_SCHEME.equals(mc.getResourceURI().getScheme()))
              .min(
                  Comparator.comparing(
                      mc ->
                          util.parseToDate(
                              mc.getAttribute(MetacardVersion.VERSIONED_ON).getValue())));

      if (!contentVersion.isPresent()) {
        /* no content versions, just restore metacard */
        revertMetacard(versionMetacard, id, false);
      } else {
        revertContentandMetacard(contentVersion.get(), versionMetacard, id);
      }

      securityLogger.audit("Reverted {} to metacard {}", revertId, id);

      return versionMetacard;
    } catch (UnsupportedQueryException
        | SourceUnavailableException
        | FederationException
        | IngestException
        | ResourceNotFoundException
        | IOException
        | ResourceNotSupportedException e) {
      securityLogger.audit("Failed to revert {} to metcard {}", revertId, id, e);
      throw e;
    }
  }

  private Set<String> getHiddenFields(List<Result> metacards) {
    Set<String> matchedHiddenFields;
    List<Pattern> hiddenFieldPatterns =
        configuration
            .getHiddenAttributes()
            .stream()
            .map(Pattern::compile)
            .collect(Collectors.toList());
    matchedHiddenFields =
        metacards
            .stream()
            .map(Result::getMetacard)
            .map(Metacard::getMetacardType)
            .map(MetacardType::getAttributeDescriptors)
            .flatMap(Collection::stream)
            .map(AttributeDescriptor::getName)
            .filter(
                attr ->
                    hiddenFieldPatterns
                        .stream()
                        .map(Pattern::asPredicate)
                        .anyMatch(pattern -> pattern.test(attr)))
            .collect(Collectors.toSet());
    return matchedHiddenFields;
  }

  private void revertMetacard(Metacard versionMetacard, String id, boolean alreadyCreated)
      throws SourceUnavailableException, IngestException {
    LOGGER.trace("Reverting metacard [{}] to version [{}]", id, versionMetacard.getId());
    Metacard revertMetacard = MetacardVersionImpl.toMetacard(versionMetacard);
    Action action =
        Action.fromKey((String) versionMetacard.getAttribute(MetacardVersion.ACTION).getValue());

    if (DELETE_ACTIONS.contains(action)) {
      attemptDeleteDeletedMetacard(id);
      if (!alreadyCreated) {
        Map<String, Serializable> properties =
            operationPropertySupplier == null
                ? new HashMap<>()
                : operationPropertySupplier.properties(OperationPropertySupplier.CREATE_TYPE);

        catalogFramework.create(
            new CreateRequestImpl(Collections.singletonList(revertMetacard), properties));
      }
    } else {
      tryUpdate(
          4,
          () -> {
            catalogFramework.update(new UpdateRequestImpl(id, revertMetacard));
            return true;
          });
    }
  }

  private void revertContentandMetacard(Metacard latestContent, Metacard versionMetacard, String id)
      throws SourceUnavailableException, IngestException, ResourceNotFoundException, IOException,
          ResourceNotSupportedException {
    LOGGER.trace(
        "Reverting content and metacard for metacard [{}]. \nLatest content: [{}] \nVersion metacard: [{}]",
        id,
        latestContent.getId(),
        versionMetacard.getId());
    Map<String, Serializable> properties = new HashMap<>();
    properties.put("no-default-tags", true);
    ResourceResponse latestResource =
        catalogFramework.getLocalResource(
            new ResourceRequestById(latestContent.getId(), properties));

    ContentItemImpl contentItem =
        new ContentItemImpl(
            id,
            new ByteSourceWrapper(() -> latestResource.getResource().getInputStream()),
            latestResource.getResource().getMimeTypeValue(),
            latestResource.getResource().getName(),
            latestResource.getResource().getSize(),
            MetacardVersionImpl.toMetacard(versionMetacard));

    // Try to delete the "deleted metacard" marker first.
    boolean alreadyCreated = false;
    Action action =
        Action.fromKey((String) versionMetacard.getAttribute(MetacardVersion.ACTION).getValue());
    if (DELETE_ACTIONS.contains(action)) {
      alreadyCreated = true;
      catalogFramework.create(
          new CreateStorageRequestImpl(
              Collections.singletonList(contentItem), id, new HashMap<>()));
    } else {
      // Currently we can't guarantee the metacard will exist yet because of the 1 second
      // soft commit in solr. this busy wait loop should be fixed when alternate solution
      // is found.
      tryUpdate(
          4,
          () -> {
            catalogFramework.update(
                new UpdateStorageRequestImpl(
                    Collections.singletonList(contentItem), id, new HashMap<>()));
            return true;
          });
    }
    LOGGER.trace("Successfully reverted metacard content for [{}]", id);
    revertMetacard(versionMetacard, id, alreadyCreated);
  }

  private void trySleep() {
    try {
      Thread.sleep(350);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void tryUpdate(int retries, Callable<Boolean> func) throws IngestException {
    if (retries <= 0) {
      throw new IngestException("Could not update metacard!");
    }
    LOGGER.trace("Trying to update metacard.");
    try {
      func.call();
      LOGGER.trace("Successfully updated metacard.");
    } catch (Exception e) {
      LOGGER.trace("Failed to update metacard");
      trySleep();
      tryUpdate(retries - 1, func);
    }
  }

  private void attemptDeleteDeletedMetacard(String id) {
    LOGGER.trace("Attemping to delete metacard [{}]", id);
    Filter tags =
        filterBuilder.attribute(Metacard.TAGS).is().like().text(DeletedMetacard.DELETED_TAG);
    Filter deletion =
        filterBuilder.attribute(DeletedMetacard.DELETION_OF_ID).is().equalTo().text(id);
    Filter filter = filterBuilder.allOf(tags, deletion);

    Map<String, Serializable> properties =
        operationPropertySupplier == null
            ? new HashMap<>()
            : operationPropertySupplier.properties(OperationPropertySupplier.QUERY_TYPE);

    QueryResponse response = null;
    try {
      response =
          catalogFramework.query(
              new QueryRequestImpl(new QueryImpl(filter), false, null, properties));
    } catch (UnsupportedQueryException | SourceUnavailableException | FederationException e) {
      LOGGER.debug("Could not find the deleted metacard marker to delete", e);
    }

    if (response == null || response.getResults() == null || response.getResults().size() != 1) {
      LOGGER.debug("There should have been one deleted metacard marker");
      return;
    }

    final DeleteRequestImpl deleteRequest =
        new DeleteRequestImpl(response.getResults().get(0).getMetacard().getId());
    deleteRequest.getProperties().put("operation.query-tags", ImmutableSet.of("*"));
    try {
      executeAsSystem(() -> catalogFramework.delete(deleteRequest));
    } catch (ExecutionException e) {
      LOGGER.debug("Could not delete the deleted metacard marker", e);
    }
    LOGGER.trace("Deleted delete marker metacard successfully");
  }

  /**
   * Caution should be used with this, as it elevates the permissions to the System user.
   *
   * @param func What to execute as the System
   * @param <T> Generic return type of func
   */
  private <T> void executeAsSystem(Callable<T> func) {
    security.runAsAdmin(() -> security.getSystemSubject().execute(func));
  }

  private Instant getVersionedOnDate(Metacard mc) {
    return util.parseToDate(mc.getAttribute(MetacardVersion.VERSIONED_ON).getValue());
  }

  private AttributeDescriptor getDescriptor(Metacard target, String attribute) {
    return Optional.ofNullable(target)
        .map(Metacard::getMetacardType)
        .map(mt -> mt.getAttributeDescriptor(attribute))
        .orElseThrow(
            () -> new RuntimeException("Could not find attribute descriptor for: " + attribute));
  }

  protected UpdateResponse patchMetacards(
      List<MetacardChanges> metacardChanges, String subjectIdentifer, Set<String> storeIds)
      throws SourceUnavailableException, IngestException {
    Set<String> changedIds =
        metacardChanges.stream().flatMap(mc -> mc.getIds().stream()).collect(Collectors.toSet());

    Map<String, Result> results =
        util.getMetacardsWithTagByAttributes(Core.ID, changedIds, "*", storeIds);

    for (MetacardChanges changeset : metacardChanges) {
      for (AttributeChange attributeChange : changeset.getAttributes()) {
        for (String id : changeset.getIds()) {
          List<String> values = attributeChange.getValues();
          Result result = results.get(id);
          if (result == null) {
            LOGGER.debug(
                "Metacard {} either does not exist or user {} does not have permission to see it",
                id,
                subjectIdentifer);
            throw new NotFoundException("Result was not found");
          }
          Metacard resultMetacard = result.getMetacard();

          Function<Serializable, Serializable> mapFunc = Function.identity();
          if (isChangeTypeDate(attributeChange, resultMetacard)) {
            mapFunc = mapFunc.andThen(serializable -> Date.from(util.parseDate(serializable)));
          }

          resultMetacard.setAttribute(
              new AttributeImpl(
                  attributeChange.getAttribute(),
                  values
                      .stream()
                      .filter(Objects::nonNull)
                      .map(mapFunc)
                      .collect(Collectors.toList())));
        }
      }
    }

    List<Metacard> changedMetacards =
        results.values().stream().map(Result::getMetacard).collect(Collectors.toList());
    return catalogFramework.update(
        new UpdateRequestImpl(
            changedMetacards
                .stream()
                .map(
                    metacard ->
                        new AbstractMap.SimpleEntry<Serializable, Metacard>(
                            metacard.getId(), metacard))
                .collect(Collectors.toList()),
            Core.ID,
            new HashMap<>(),
            storeIds));
  }

  private boolean isChangeTypeDate(AttributeChange attributeChange, Metacard result) {
    return getDescriptor(result, attributeChange.getAttribute())
        .getType()
        .getAttributeFormat()
        .equals(AttributeType.AttributeFormat.DATE);
  }

  private List<Result> getMetacardHistory(String id, String sourceId) {
    Map<String, Serializable> properties =
        operationPropertySupplier == null
            ? new HashMap<>()
            : operationPropertySupplier.properties(OperationPropertySupplier.QUERY_TYPE);

    Filter historyFilter =
        filterBuilder.attribute(Metacard.TAGS).is().equalTo().text(MetacardVersion.VERSION_TAG);
    Filter idFilter =
        filterBuilder.attribute(MetacardVersion.VERSION_OF_ID).is().equalTo().text(id);

    Filter filter = filterBuilder.allOf(historyFilter, idFilter);
    Set<String> sources = sourceId != null ? Collections.singleton(sourceId) : null;
    ResultIterable resultIterable =
        resultIterable(
            catalogFramework,
            new QueryRequestImpl(
                new QueryImpl(
                    filter,
                    1,
                    PAGE_SIZE,
                    SortBy.NATURAL_ORDER,
                    false,
                    TimeUnit.SECONDS.toMillis(10)),
                false,
                sources,
                properties));
    return Lists.newArrayList(resultIterable);
  }

  private Metacard updateMetacard(String id, Metacard metacard)
      throws SourceUnavailableException, IngestException {
    return catalogFramework
        .update(new UpdateRequestImpl(id, metacard))
        .getUpdatedMetacards()
        .get(0)
        .getNewMetacard();
  }

  private Metacard saveMetacard(Metacard metacard)
      throws IngestException, SourceUnavailableException {
    return catalogFramework.create(new CreateRequestImpl(metacard)).getCreatedMetacards().get(0);
  }

  private QueryMetacardImpl jsonToQueryMetacard(final Map<String, Object> queryJson) {
    final QueryMetacardImpl queryMetacard = new QueryMetacardImpl();
    transformer.transformIntoMetacard(queryJson, queryMetacard);
    return queryMetacard;
  }

  public void setSubjectOperations(SubjectOperations subjectOperations) {
    this.subjectOperations = subjectOperations;
  }

  public void setSecurityLogger(SecurityLogger securityLogger) {
    this.securityLogger = securityLogger;
  }

  private static class ByteSourceWrapper extends ByteSource {
    Supplier<InputStream> supplier;

    ByteSourceWrapper(Supplier<InputStream> supplier) {
      this.supplier = supplier;
    }

    @Override
    public InputStream openStream() {
      return supplier.get();
    }
  }
}
