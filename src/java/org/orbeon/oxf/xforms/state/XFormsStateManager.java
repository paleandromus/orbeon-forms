/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.state;

import org.apache.log4j.Logger;
import org.dom4j.Document;
import org.dom4j.Element;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.pipeline.api.ExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.util.IndentedLogger;
import org.orbeon.oxf.util.LoggerFactory;
import org.orbeon.oxf.util.PropertyContext;
import org.orbeon.oxf.xforms.XFormsConstants;
import org.orbeon.oxf.xforms.XFormsContainingDocument;
import org.orbeon.oxf.xforms.XFormsProperties;
import org.orbeon.oxf.xforms.XFormsUtils;
import org.orbeon.oxf.xforms.processor.XFormsServer;

/**
 * Centralize XForms state management.
 *
 */
public class XFormsStateManager {
    
    private static final String LOG_TYPE = "state manager";
    private static final String LOGGING_CATEGORY = "state";
    private static final Logger logger = LoggerFactory.createLogger(XFormsStateManager.class);
    private static final IndentedLogger indentedLogger = XFormsContainingDocument.getIndentedLogger(logger, XFormsServer.getLogger(), LOGGING_CATEGORY);

    // All prefixes must have the same length
    public static final String PERSISTENT_STATE_PREFIX = "pers:";
    private static final int PREFIX_COLON_POSITION = PERSISTENT_STATE_PREFIX.length() - 1;

    // Ideally we wouldn't want to force session creation, but it's hard to implement the more elaborate expiration
    // strategy without session.
    public static final boolean FORCE_SESSION_CREATION = true;

    public static IndentedLogger getIndentedLogger() {
        return indentedLogger;
    }

    private static XFormsStateManager instance = null;

    public synchronized static XFormsStateManager instance() {
        if (instance == null) {
            instance = new XFormsStateManager();
        }
        return instance;
    }

    private XFormsStateManager() {}

    public void afterInitialResponse(PropertyContext propertyContext, XFormsContainingDocument containingDocument) {
        if (XFormsProperties.isCacheDocument()) {
            // Cache the document
            XFormsDocumentCache.instance().storeDocument(propertyContext, containingDocument);
        }
    }

    /**
     * Find or restore a document based on an incoming request.
     *
     * @param pipelineContext   current context
     * @param request           incoming Ajax request document
     * @param session           session
     * @return                  document, either from cache or from state information
     */
    public XFormsContainingDocument findOrRestoreDocument(PipelineContext pipelineContext, Document request, ExternalContext.Session session) {

        // Get static state
        final String encodedStaticState;
        {
            final Element staticStateElement = request.getRootElement().element(XFormsConstants.XXFORMS_STATIC_STATE_QNAME);
            encodedStaticState = staticStateElement.getTextTrim();
        }

        // Get dynamic state
        final String encodedDynamicState;
        {
            final Element dynamicStateElement = request.getRootElement().element(XFormsConstants.XXFORMS_DYNAMIC_STATE_QNAME);
            encodedDynamicState = dynamicStateElement.getTextTrim();
        }

        // Check session if needed
        if (session == null && isSessionDependentState(encodedStaticState)) {
            throw new OXFException("Session has expired. Unable to process incoming request.");
        }

        return findOrRestoreDocument(pipelineContext, encodedStaticState, encodedDynamicState);
    }

    private XFormsContainingDocument findOrRestoreDocument(PipelineContext pipelineContext, String encodedClientStaticState, String encodedClientDynamicState) {

        // Get or create document
        final XFormsContainingDocument containingDocument;
        if (XFormsProperties.isCacheDocument()) {
            // Try to find the document in cache
            final XFormsContainingDocument cachedDocument
                    = XFormsDocumentCache.instance().getDocument(pipelineContext,
                        new XFormsState(removePrefix(encodedClientStaticState), removePrefix(encodedClientDynamicState)));

            if (cachedDocument != null) {
                // Found in cache
                containingDocument = cachedDocument;
            } else {
                // Not found in cache, must recreate from store
                containingDocument = createDocumentFromStore(pipelineContext, encodedClientStaticState, encodedClientDynamicState);
            }
        } else {
            // Must recreate from store
            containingDocument = createDocumentFromStore(pipelineContext, encodedClientStaticState, encodedClientDynamicState);
        }
        return containingDocument;
    }

    private XFormsContainingDocument createDocumentFromStore(PipelineContext pipelineContext, String encodedClientStaticState, String encodedClientDynamicState) {
        final XFormsState decodedState
                = decodeClientState(pipelineContext, encodedClientStaticState, encodedClientDynamicState);
        return new XFormsContainingDocument(pipelineContext, decodedState);
    }

    private boolean isSessionDependentState(String staticStateString) {
        return isIndirectState(staticStateString) && getPrefix(staticStateString).equals(PERSISTENT_STATE_PREFIX);
    }

    private boolean isIndirectState(String stateString) {
        return stateString.length() > PREFIX_COLON_POSITION && stateString.charAt(PREFIX_COLON_POSITION) == ':';
    }

    private String getPrefix(String stateString) {
        return stateString.substring(0, PREFIX_COLON_POSITION + 1);
    }

    private String removePrefix(String stateString) {
        return stateString.substring(PREFIX_COLON_POSITION + 1);
    }

    /**
     * Decode static and dynamic state strings coming from the client.
     *
     * @param propertyContext       current context
     * @param staticStateString     static state string as sent by client
     * @param dynamicStateString    dynamic state string as sent by client
     * @return                      decoded state
     */
    private XFormsState decodeClientState(PropertyContext propertyContext, String staticStateString, String dynamicStateString) {

        final ExternalContext externalContext = XFormsUtils.getExternalContext(propertyContext);

        final XFormsState xformsDecodedClientState;
        if (isIndirectState(staticStateString)) {
            // State doesn't come directly with request

            // Separate prefixes from UUIDs
            final String staticStatePrefix = getPrefix(staticStateString);
            final String staticStateUUID = removePrefix(staticStateString);

            final String dynamicStatePrefix = getPrefix(dynamicStateString);
            final String dynamicStateUUID = removePrefix(dynamicStateString);

            // Both prefixes must be the same
            if (!staticStatePrefix.equals(dynamicStatePrefix)) {
                final String message = "Inconsistent XForms state prefixes: " + staticStatePrefix + ", " + dynamicStatePrefix;
                indentedLogger.logDebug(LOG_TYPE, message);
                throw new OXFException(message);
            }

            // Get relevant store
            final XFormsStateStore stateStore;
            if (staticStatePrefix.equals(PERSISTENT_STATE_PREFIX)) {
                stateStore = XFormsPersistentApplicationStateStore.instance(externalContext);
            } else {
                // Invalid prefix
                final String message = "Invalid state prefix: " + staticStatePrefix;
                indentedLogger.logDebug(LOG_TYPE, message);
                throw new OXFException(message);
            }

            // Get state from store
            final XFormsState xformsState = stateStore.find(staticStateUUID, dynamicStateUUID);

            if (xformsState == null) {
                // Oops, we couldn't find the state in the store

                final String UNABLE_TO_RETRIEVE_XFORMS_STATE_MESSAGE = "Unable to retrieve XForms engine state.";
                final String PLEASE_RELOAD_PAGE_MESSAGE = "Please reload the current page. Note that you will lose any unsaved changes.";
                final String UUIDS_MESSAGE = "Static state key: " + staticStateUUID + ", dynamic state key: " + dynamicStateUUID;

                if (staticStatePrefix.equals(PERSISTENT_STATE_PREFIX)) {
                    final ExternalContext.Session currentSession =  externalContext.getSession(false);
                    if (currentSession == null || currentSession.isNew()) {
                        // This means that no session is currently existing, or a session exists but it is newly created
                        final String message = "Your session has expired. " + PLEASE_RELOAD_PAGE_MESSAGE;
                        indentedLogger.logError("", message);
                        throw new OXFException(message + " " + UUIDS_MESSAGE);
                    } else {
                        // There is a session and it is still known by the client
                        final String message = UNABLE_TO_RETRIEVE_XFORMS_STATE_MESSAGE + " " + PLEASE_RELOAD_PAGE_MESSAGE;
                        indentedLogger.logError("", message);
                        throw new OXFException(message + " " + UUIDS_MESSAGE);
                    }

                } else {
                    final String message = UNABLE_TO_RETRIEVE_XFORMS_STATE_MESSAGE + " " + PLEASE_RELOAD_PAGE_MESSAGE;
                    indentedLogger.logError("", message);
                    throw new OXFException(message + " " + UUIDS_MESSAGE);
                }
            }

            xformsDecodedClientState = xformsState;

        } else {
            // State comes directly with request
            xformsDecodedClientState = new XFormsState(staticStateString, dynamicStateString);
        }

        return xformsDecodedClientState;
    }

    /**
     * Return the static state string to send to the client in the HTML page.
     *
     * @param propertyContext       current context
     * @param containingDocument    document
     * @return                      encoded state
     */
    public String getClientEncodedStaticState(PropertyContext propertyContext, XFormsContainingDocument containingDocument) {
        final String staticStateString;
        {
            if (!XFormsProperties.isClientStateHandling(containingDocument)) {
                // Return UUID
                staticStateString = PERSISTENT_STATE_PREFIX + containingDocument.getStaticState().getUUID();
            } else {
                // Return full encoded state
                staticStateString = containingDocument.getStaticState().getEncodedStaticState(propertyContext);
            }
        }
        return staticStateString;
    }

    /**
     * Return the dynamic state string to send to the client in the HTML page.
     *
     * @param propertyContext       current context
     * @param containingDocument    document
     * @return                      encoded state
     */
    public String getClientEncodedDynamicState(PropertyContext propertyContext, XFormsContainingDocument containingDocument) {
        final String dynamicStateString;
        {
            if (!XFormsProperties.isClientStateHandling(containingDocument)) {
                // Return UUID
                dynamicStateString = PERSISTENT_STATE_PREFIX + containingDocument.getDynamicStateLatestUUID();
            } else {
                // Return full encoded state
                dynamicStateString = containingDocument.createEncodedDynamicState(propertyContext, true);
            }
        }
        return dynamicStateString;
    }

    public void beforeUpdateResponse(PropertyContext propertyContext, XFormsContainingDocument containingDocument) {
        if (containingDocument.isDirtySinceLastRequest()) {
            // The document is dirty
            indentedLogger.logDebug(LOG_TYPE, "Document is dirty: generate new dynamic state.");

            // Tell the document to update its state
            containingDocument.updateDynamicState();
        } else {
            // The document is not dirty: no real encoding takes place here
            indentedLogger.logDebug(LOG_TYPE, "Document is not dirty: keep existing dynamic state.");
        }
    }

    public void afterUpdateResponse(PropertyContext propertyContext, XFormsContainingDocument containingDocument) {
        if (XFormsProperties.isCacheDocument()) {
            // Cache the document
            XFormsDocumentCache.instance().storeDocument(propertyContext, containingDocument);
        }
    }

    /**
     * Return the delay for the session heartbeat event.
     *
     * @param containingDocument    containing document
     * @param externalContext       external context (for access to session and application scopes)
     * @return                      delay in ms, or -1 is not applicable
     */
    public static long getHeartbeatDelay(XFormsContainingDocument containingDocument, ExternalContext externalContext) {
        if (XFormsProperties.isClientStateHandling(containingDocument)) {
            return -1;
        } else {
            final long heartbeatDelay;
            final boolean isSessionHeartbeat = XFormsProperties.isSessionHeartbeat(containingDocument);
            final ExternalContext.Session session = externalContext.getSession(FORCE_SESSION_CREATION);
            if (isSessionHeartbeat && session != null)
                heartbeatDelay = session.getMaxInactiveInterval() * 800; // 80% of session expiration time, in ms
            else
                heartbeatDelay = -1;
            return heartbeatDelay;
        }
    }
}
