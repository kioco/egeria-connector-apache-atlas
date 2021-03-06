/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.egeria.connectors.apache.atlas.repositoryconnector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.atlas.AtlasBaseClient;
import org.apache.atlas.AtlasServiceException;
import org.apache.atlas.model.SearchFilter;
import org.apache.atlas.model.discovery.AtlasSearchResult;
import org.apache.atlas.model.discovery.SearchParameters;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasRelationship;
import org.apache.atlas.model.instance.EntityMutationResponse;
import org.apache.atlas.model.typedef.AtlasRelationshipDef;
import org.apache.atlas.model.typedef.AtlasStructDef;
import org.apache.atlas.model.typedef.AtlasTypesDef;
import org.odpi.openmetadata.frameworks.connectors.properties.ConnectionProperties;
import org.odpi.openmetadata.frameworks.connectors.properties.EndpointProperties;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.typedefs.TypeDefCategory;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.repositoryconnector.OMRSRepositoryConnector;
import org.odpi.openmetadata.repositoryservices.ffdc.exception.OMRSRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.atlas.AtlasClientV2;
import org.springframework.core.io.ClassPathResource;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;

public class ApacheAtlasOMRSRepositoryConnector extends OMRSRepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(ApacheAtlasOMRSRepositoryConnector.class);

    public static final String EP_ENTITY = "/api/atlas/v2/entity/guid/";

    private String url;
    private AtlasClientV2 atlasClient;
    private boolean successfulInit = false;

    /**
     * Default constructor used by the OCF Connector Provider.
     */
    public ApacheAtlasOMRSRepositoryConnector() {
        // Nothing to do...
    }

    /**
     * Call made by the ConnectorProvider to initialize the Connector with the base services.
     *
     * @param connectorInstanceId   unique id for the connector instance   useful for messages etc
     * @param connectionProperties   POJO for the configuration used to create the connector.
     */
    @Override
    public void initialize(String               connectorInstanceId,
                           ConnectionProperties connectionProperties) {
        super.initialize(connectorInstanceId, connectionProperties);

        final String methodName = "initialize";
        if (log.isDebugEnabled()) { log.debug("Initializing ApacheAtlasOMRSRepositoryConnector..."); }

        EndpointProperties endpointProperties = connectionProperties.getEndpoint();
        if (endpointProperties == null) {
            ApacheAtlasOMRSErrorCode errorCode = ApacheAtlasOMRSErrorCode.REST_CLIENT_FAILURE;
            String errorMessage = errorCode.getErrorMessageId() + errorCode.getFormattedErrorMessage("null");
            throw new OMRSRuntimeException(
                    errorCode.getHTTPErrorCode(),
                    this.getClass().getName(),
                    methodName,
                    errorMessage,
                    errorCode.getSystemAction(),
                    errorCode.getUserAction()
            );
        }
        this.url = endpointProperties.getProtocol() + "://" + endpointProperties.getAddress();

        String username = connectionProperties.getUserId();
        String password = connectionProperties.getClearPassword();

        this.atlasClient = new AtlasClientV2(new String[]{ getBaseURL() }, new String[]{ username, password });

        // Test REST API connection by attempting to retrieve types list
        AtlasTypesDef atlasTypes = null;
        try {
            atlasTypes = atlasClient.getAllTypeDefs(new SearchFilter());
            successfulInit = (atlasTypes != null && atlasTypes.hasEntityDef("Referenceable"));
        } catch (AtlasServiceException e) {
            log.error("Unable to retrieve types from Apache Atlas.", e);
        }

        ClassPathResource mappingResource = new ClassPathResource("ApacheAtlasNativeTypesPatch.json");

        try {

            // Apply Open Metadata patch to the out-of-the-box Apache Atlas types
            InputStream stream = mappingResource.getInputStream();
            ObjectMapper mapper = new ObjectMapper();
            AtlasTypesDef atlasTypesDef = mapper.readValue(stream, AtlasTypesDef.class);
            atlasClient.updateAtlasTypeDefs(atlasTypesDef);

        } catch (IOException e) {
            log.error("Unable to load ApacheAtlasNativeTypesPatch.json from jar file -- cannot patch default Apache Atlas types.", e);
        } catch (AtlasServiceException e) {
            log.error("Unable to patch default Apache Atlas types.", e);
        }

        if (!successfulInit) {
            ApacheAtlasOMRSErrorCode errorCode = ApacheAtlasOMRSErrorCode.REST_CLIENT_FAILURE;
            String errorMessage = errorCode.getErrorMessageId() + errorCode.getFormattedErrorMessage(this.url);
            throw new OMRSRuntimeException(
                    errorCode.getHTTPErrorCode(),
                    this.getClass().getName(),
                    methodName,
                    errorMessage,
                    errorCode.getSystemAction(),
                    errorCode.getUserAction()
            );
        }

    }

    /**
     * Set up the unique Id for this metadata collection.
     *
     * @param metadataCollectionId - String unique Id
     */
    @Override
    public void setMetadataCollectionId(String metadataCollectionId) {
        this.metadataCollectionId = metadataCollectionId;
        /*
         * Initialize the metadata collection only once the connector is properly set up.
         * (Meaning we will NOT initialise the collection if the connector failed to setup properly.)
         */
        if (successfulInit) {
            metadataCollection = new ApacheAtlasOMRSMetadataCollection(this,
                    serverName,
                    repositoryHelper,
                    repositoryValidator,
                    metadataCollectionId);
        }
    }

    /**
     * Retrieve the base URL of the Apache Atlas environment.
     *
     * @return String
     */
    public String getBaseURL() {
        return this.url;
    }

    /**
     * Indicates whether the provided TypeDef exists in this Apache Atlas environment.
     *
     * @param name the name of the TypeDef in Apache Atlas to check
     * @return boolean
     */
    public boolean typeDefExistsByName(String name) {
        return atlasClient.typeWithNameExists(name);
    }

    /**
     * Retrieves the Apache Atlas typedef specified from the Apache Atlas environment.
     *
     * @param name the name of the TypeDef to retrieve
     * @param typeDefCategory the type (in OMRS terms) of the TypeDef to retrieve
     * @return AtlasStructDef
     */
    public AtlasStructDef getTypeDefByName(String name, TypeDefCategory typeDefCategory) {

        AtlasStructDef result = null;
        try {
            switch(typeDefCategory) {
                case CLASSIFICATION_DEF:
                    result = atlasClient.getClassificationDefByName(name);
                    break;
                case ENTITY_DEF:
                    result = atlasClient.getEntityDefByName(name);
                    break;
                case RELATIONSHIP_DEF:
                    // For whatever reason, relationshipdef retrieval is not in the Atlas client, so writing our own
                    // API call for this one
                    String atlasPath = "relationshipdef";
                    AtlasBaseClient.API api = new AtlasBaseClient.API(String.format(AtlasClientV2.TYPES_API + "%s/name/%s", atlasPath, name), HttpMethod.GET, Response.Status.OK);
                    result = atlasClient.callAPI(api, AtlasRelationshipDef.class, null);
                    break;
                default:
                    break;
            }
        } catch (AtlasServiceException e) {
            log.error("Unable to retrieve type by name: {}", name, e);
        }
        return result;
    }

    /**
     * Retrieves an Apache Atlas Entity instance by its GUID, including all of its relationships.
     *
     * @param guid the GUID of the entity instance to retrieve
     * @return AtlasEntityWithExtInfo
     */
    public AtlasEntity.AtlasEntityWithExtInfo getEntityByGUID(String guid) {
        return getEntityByGUID(guid, false, true);
    }

    /**
     * Retrieve an Apache Atlas Entity instance by its GUID.
     *
     * @param guid the GUID of the entity instance to retrieve
     * @param minimalExtraInfo if true, minimize the amount of extra information retrieved about the GUID
     * @param ignoreRelationships if true, will return only the entity (none of its relationships)
     * @return AtlasEntityWithExtInfo
     */
    public AtlasEntity.AtlasEntityWithExtInfo getEntityByGUID(String guid, boolean minimalExtraInfo, boolean ignoreRelationships) {
        return getEntityByGUID(guid, minimalExtraInfo, ignoreRelationships, true);
    }

    /**
     * Retrieve an Apache Atlas Entity instance by its GUID.
     *
     * @param guid the GUID of the entity instance to retrieve
     * @param minimalExtraInfo if true, minimize the amount of extra information retrieved about the GUID
     * @param ignoreRelationships if true, will return only the entity (none of its relationships)
     * @param logIfNotFound if true, will log any exception where the entity is not found, otherwise will not
     * @return AtlasEntityWithExtInfo
     */
    public AtlasEntity.AtlasEntityWithExtInfo getEntityByGUID(String guid, boolean minimalExtraInfo, boolean ignoreRelationships, boolean logIfNotFound) {
        AtlasEntity.AtlasEntityWithExtInfo entity = null;
        try {
            entity = atlasClient.getEntityByGuid(guid, minimalExtraInfo, ignoreRelationships);
        } catch (AtlasServiceException e) {
            if (logIfNotFound) {
                log.error("Unable to retrieve entity by GUID: {}", guid, e);
            }
        }
        return entity;
    }

    /**
     * Retrieves an Apache Atlas Relationship instance by its GUID.
     *
     * @param guid the GUID of the relationship instance to retrieve
     * @return AtlasRelationshipWithExtInfo
     */
    public AtlasRelationship.AtlasRelationshipWithExtInfo getRelationshipByGUID(String guid) {
        return getRelationshipByGUID(guid, false);
    }

    /**
     * Retrieves an Apache Atlas Relationship instance by its GUID.
     *
     * @param guid the GUID of the relationship instance to retrieve
     * @param extendedInfo if true, will include extended info in the result
     * @return AtlasRelationshipWithExtInfo
     */
    public AtlasRelationship.AtlasRelationshipWithExtInfo getRelationshipByGUID(String guid, boolean extendedInfo) {
        AtlasRelationship.AtlasRelationshipWithExtInfo relationship = null;
        try {
            relationship = atlasClient.getRelationshipByGuid(guid, extendedInfo);
        } catch (AtlasServiceException e) {
            log.error("Unable to retrieve relationship by GUID: {}", guid, e);
        }
        return relationship;
    }

    /**
     * Adds the list of TypeDefs provided to Apache Atlas.
     *
     * @param typeDefs the TypeDefs to add to Apache Atlas
     * @return AtlasTypesDef
     */
    public AtlasTypesDef createTypeDef(AtlasTypesDef typeDefs) {
        AtlasTypesDef result = null;
        try {
            result = atlasClient.createAtlasTypeDefs(typeDefs);
        } catch (AtlasServiceException e) {
            log.error("Unable to create provided TypeDefs: {}", typeDefs, e);
        }
        return result;
    }

    /**
     * Search for entities based on the provided parameters.
     *
     * @param searchParameters the criteria by which to search
     * @return AtlasSearchResult
     */
    public AtlasSearchResult searchForEntities(SearchParameters searchParameters) {
        AtlasSearchResult result = null;
        try {
            if (log.isInfoEnabled()) { log.info("Searching Atlas with: {}", searchParameters); }
            result = atlasClient.facetedSearch(searchParameters);
        } catch (AtlasServiceException e) {
            log.error("Unable to search based on parameters: {}", searchParameters, e);
        }
        return result;
    }

    /**
     * Search for entities based one provided DSL query string.
     *
     * @param dslQuery the query to use for the search
     * @return AtlasSearchResult
     */
    public AtlasSearchResult searchWithDSL(String dslQuery) {
        AtlasSearchResult result = null;
        try {
            if (log.isInfoEnabled()) { log.info("Searching Atlas with: {}", dslQuery); }
            result = atlasClient.dslSearch(dslQuery);
        } catch (AtlasServiceException e) {
            log.error("Unable to search based on DSL query: {}", dslQuery, e);
        }
        return result;
    }

    /**
     * Save the entity provided to Apache Atlas.
     *
     * @param atlasEntity the Apache Atlas entity to save
     * @param create indicates whether the entity should be created (true) or updated (false)
     * @return EntityMutationResponse listing the details of the entity that was saved
     */
    public EntityMutationResponse saveEntity(AtlasEntity.AtlasEntityWithExtInfo atlasEntity, boolean create) {
        EntityMutationResponse result = null;
        try {
            if (create) {
                result = atlasClient.createEntity(atlasEntity);
            } else {
                result = atlasClient.updateEntity(atlasEntity);
            }
        } catch (AtlasServiceException e) {
            log.error("Unable to save entity: {}", atlasEntity, e);
        }
        return result;
    }

}
