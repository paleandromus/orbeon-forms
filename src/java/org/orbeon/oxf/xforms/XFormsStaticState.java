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
package org.orbeon.oxf.xforms;

import org.apache.log4j.Logger;
import org.dom4j.*;
import org.dom4j.io.DocumentSource;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.common.Version;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.properties.Properties;
import org.orbeon.oxf.properties.PropertySet;
import org.orbeon.oxf.util.*;
import org.orbeon.oxf.xforms.action.XFormsActions;
import org.orbeon.oxf.xforms.analysis.ControlAnalysisFactory;
import org.orbeon.oxf.xforms.analysis.IdGenerator;
import org.orbeon.oxf.xforms.analysis.XFormsAnnotatorContentHandler;
import org.orbeon.oxf.xforms.analysis.XFormsExtractorContentHandler;
import org.orbeon.oxf.xforms.analysis.controls.*;
import org.orbeon.oxf.xforms.analysis.model.Model;
import org.orbeon.oxf.xforms.control.XFormsControlFactory;
import org.orbeon.oxf.xforms.event.XFormsEventHandler;
import org.orbeon.oxf.xforms.event.XFormsEventHandlerImpl;
import org.orbeon.oxf.xforms.event.XFormsEvents;
import org.orbeon.oxf.xforms.processor.XFormsServer;
import org.orbeon.oxf.xforms.xbl.XBLBindings;
import org.orbeon.oxf.xml.SAXStore;
import org.orbeon.oxf.xml.TransformerUtils;
import org.orbeon.oxf.xml.XMLConstants;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;
import org.orbeon.oxf.xml.dom4j.ExtendedLocationData;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.saxon.Configuration;
import org.orbeon.saxon.dom4j.DocumentWrapper;
import org.orbeon.saxon.dom4j.NodeWrapper;
import org.orbeon.saxon.om.DocumentInfo;
import org.orbeon.saxon.om.Item;
import org.orbeon.saxon.om.NodeInfo;
import org.orbeon.saxon.tinytree.TinyBuilder;
import org.xml.sax.SAXException;

import javax.xml.transform.Transformer;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.TransformerHandler;
import java.util.*;

/**
 * This class encapsulates containing document static state information.
 *
 * All the information contained here must be constant, and never change as the XForms engine operates on a page. This
 * information can be shared between multiple running copies of an XForms pages.
 *
 * NOTE: This code will have to change a bit if we move towards TinyTree to store the static state.
 */
public class XFormsStaticState {

    public static final String LOGGING_CATEGORY = "analysis";
    private static final Logger logger = LoggerFactory.createLogger(XFormsStaticState.class);
    private final IndentedLogger indentedLogger = XFormsContainingDocument.getIndentedLogger(logger, XFormsServer.getLogger(), LOGGING_CATEGORY);

    private boolean initialized;

    private String uuid;
    private String encodedStaticState;      // encoded state
    private Document staticStateDocument;   // if present, stored there temporarily only until getEncodedStaticState() is called and encodedStaticState is produced

    // Global static-state Saxon Configuration
    // Would be nice to have one per static state maybe, but expressions in XPathCache are shared so NamePool must be shared
    private static Configuration xpathConfiguration = XPathCache.getGlobalConfiguration();
    private DocumentWrapper documentWrapper = new DocumentWrapper(Dom4jUtils.createDocument(), null, xpathConfiguration);

    private Document controlsDocument;                                      // controls document
    private SAXStore xhtmlDocument;                                         // entire XHTML document for noscript mode only

    // Static representation of models and instances
    private LinkedHashMap<XBLBindings.Scope, List<Model>> modelsByScope = new LinkedHashMap<XBLBindings.Scope, List<Model>>();
    private Map<String, Model> modelsByPrefixedId = new LinkedHashMap<String, Model>();

    private Map<String, String> xxformsScripts;                             // Map of id to script content

    private final Map<String, Object> nonDefaultProperties = new HashMap<String, Object>(); // Map of property name to property value (String, Integer, Boolean)
    private final Set<String> allowedExternalEvents = new HashSet<String>();        // Set<String eventName>

    private XFormsConstants.DeploymentType deploymentType;
    private String requestContextPath;
    private String baseURI;
    private String containerType;
    private String containerNamespace;
    private LocationData locationData;

    private List<URLRewriterUtils.PathMatcher> versionedPathMatchers;

    // Static analysis
    private boolean isAnalyzed;                                             // whether this document has been analyzed already

    private XFormsAnnotatorContentHandler.Metadata metadata;

    // Event handlers
    private Set<String> eventNames;                                         // used event names
    private Map<String, List<XFormsEventHandler>> eventHandlersMap;         // Map<String observerPrefixedId, List<XFormsEventHandler> eventHandler>: for all observers with handlers
    private Map<String, String> eventHandlerAncestorsMap;                   // Map<String actionPrefixId, String ancestorPrefixedId>
    private List<XFormsEventHandler> keyHandlers;

    // Controls
    private Map<String, Map<String, ControlAnalysis>> controlTypes;         // Map<String type, Map<String prefixedId, ControlAnalysis>>
    private Map<String, ControlAnalysis> controlAnalysisMap;                // Map<String controlPrefixedId, ControlAnalysis>: for all controls

    // xforms:repeat
    // TODO: move repeatChildrenMap to ControlAnalysis
    private Map<String, List<String>> repeatChildrenMap;                    // Map<String, List> of repeat id to List of children ids
    private String repeatHierarchyString;                                   // contains comma-separated list of space-separated repeat prefixed id and ancestor if any

    // XXFormsAttributeControl
    private Map<String, Map<String, ControlAnalysis>> attributeControls;        // Map<String forPrefixedId, Map<String name, ControlAnalysis info>>

    // Offline support
    private boolean hasOfflineSupport;                                      // whether the document requires offline support
    private List<String> offlineInsertTriggerIds;                           // List<String triggerPrefixedId> of triggers can do inserts

    // Commonly used properties (use getter to access them)
    private boolean propertiesRead;
    private boolean isNoscript;
    private boolean isXPathAnalysis;

    // Components
    private XBLBindings xblBindings;

    public static final Map<String, String> BASIC_NAMESPACE_MAPPINGS = new HashMap<String, String>();

    static {
        BASIC_NAMESPACE_MAPPINGS.put(XFormsConstants.XFORMS_PREFIX, XFormsConstants.XFORMS_NAMESPACE_URI);
        BASIC_NAMESPACE_MAPPINGS.put(XFormsConstants.XXFORMS_PREFIX, XFormsConstants.XXFORMS_NAMESPACE_URI);
        BASIC_NAMESPACE_MAPPINGS.put(XFormsConstants.XML_EVENTS_PREFIX, XFormsConstants.XML_EVENTS_NAMESPACE_URI);
        BASIC_NAMESPACE_MAPPINGS.put(XMLConstants.XHTML_PREFIX, XMLConstants.XHTML_NAMESPACE_URI);
    }

    /**
     * Create static state object from a Document. This constructor is used when creating an initial static state upon
     * producing an XForms page.
     *
     * @param propertyContext       current context
     * @param staticStateDocument   document containing the static state, may be modified by this constructor and must be discarded afterwards by the caller
     * @param metadata              metadata or null if not available
     * @param annotatedDocument     SAXStore containing the XHTML for noscript mode, null if not available
     */
    public XFormsStaticState(PropertyContext propertyContext, Document staticStateDocument, XFormsAnnotatorContentHandler.Metadata metadata, SAXStore annotatedDocument) {
        // Set XPath configuration
        propertyContext.setAttribute(XPathCache.XPATH_CACHE_CONFIGURATION_PROPERTY, getXPathConfiguration());
        initialize(propertyContext, staticStateDocument, metadata, annotatedDocument, null);
    }

    /**
     * Create static state object from an encoded version. This constructor is used when restoring a static state from
     * a serialized form.
     *
     * @param propertyContext       current context
     * @param encodedStaticState    encoded static state
     */
    public XFormsStaticState(PropertyContext propertyContext, String encodedStaticState) {

        // Set XPath configuration
        propertyContext.setAttribute(XPathCache.XPATH_CACHE_CONFIGURATION_PROPERTY, getXPathConfiguration());

        // Decode encodedStaticState into staticStateDocument
        final Document staticStateDocument = XFormsUtils.decodeXML(propertyContext, encodedStaticState);

        // Initialize
        initialize(propertyContext, staticStateDocument, null, null, encodedStaticState);
    }

    public Configuration getXPathConfiguration() {
        return xpathConfiguration;
    }

    public XFormsAnnotatorContentHandler.Metadata getMetadata() {
        return metadata;
    }

    public IndentedLogger getIndentedLogger() {
        return indentedLogger;
    }

    /**
     * Return path matchers for versioned resources mode.
     *
     * @return  List of PathMatcher
     */
    public List<URLRewriterUtils.PathMatcher> getVersionedPathMatchers() {
        return versionedPathMatchers;
    }

    /**
     * Initialize. Either there is:
     *
     * o staticStateDocument, topLevelStaticIds, namespaceMap, and optional xhtmlDocument
     * o staticStateDocument and encodedStaticState
     *
     * @param propertyContext       current context
     * @param staticStateDocument   document containing the static state, may be modified by this constructor and must be discarded afterwards by the caller
     * @param metadata              metadata or null if not available
     * @param xhtmlDocument         SAXStore containing the XHTML for noscript mode, null if not available
     * @param encodedStaticState    existing serialization of static state, null if not available
     */
    private void initialize(PropertyContext propertyContext, Document staticStateDocument,
                            XFormsAnnotatorContentHandler.Metadata metadata,
                            SAXStore xhtmlDocument, String encodedStaticState) {

        indentedLogger.startHandleOperation("", "initializing static state");

        final Element staticStateElement = staticStateDocument.getRootElement();

        // Remember UUID
        this.uuid = UUIDUtils.createPseudoUUID();

        // TODO: if staticStateDocument contains XHTML document, get controls and models from there

        // Extract top-level information

        final String deploymentAttribute = staticStateElement.attributeValue("deployment");
        deploymentType = (deploymentAttribute != null) ? XFormsConstants.DeploymentType.valueOf(deploymentAttribute) : XFormsConstants.DeploymentType.plain;
        requestContextPath = staticStateElement.attributeValue("context-path");
        baseURI = staticStateElement.attributeValue(XMLConstants.XML_BASE_QNAME);
        containerType = staticStateElement.attributeValue("container-type");
        containerNamespace = staticStateElement.attributeValue("container-namespace");
        if (containerNamespace == null)
            containerNamespace = "";

        {
            final String systemId = staticStateElement.attributeValue("system-id");
            if (systemId != null) {
                locationData = new LocationData(systemId, Integer.parseInt(staticStateElement.attributeValue("line")), Integer.parseInt(staticStateElement.attributeValue("column")));
            }
        }

        // Recompute namespace mappings if needed
        final Element htmlElement = staticStateElement.element(XMLConstants.XHTML_HTML_QNAME);
        if (metadata == null) {
            final IdGenerator idGenerator;
            {
                // Use the last id used for id generation. During state restoration, XBL components must start with this id.
                final Element currentIdElement = staticStateElement.element(XFormsExtractorContentHandler.LAST_ID_QNAME);
                assert currentIdElement != null;
                final String lastId = XFormsUtils.getElementStaticId(currentIdElement);
                assert lastId != null;
                idGenerator = new IdGenerator(Integer.parseInt(lastId));
            }
            final Map<String, Map<String, String>> namespacesMap = new HashMap<String, Map<String, String>>();
            this.metadata = new XFormsAnnotatorContentHandler.Metadata(idGenerator, namespacesMap);
            try {
//                if (xhtmlDocument == null) {
                    // Recompute from staticStateDocument
                    // TODO: Can there be in this case a nested xhtml:html element, thereby causing duplicate id exceptions?
                    final Transformer identity = TransformerUtils.getIdentityTransformer();

                    // Detach xhtml element as models and controls are enough to produce namespaces map
                    if (htmlElement != null)
                        htmlElement.detach();
                    // Compute namespaces map
                    identity.transform(new DocumentSource(staticStateDocument), new SAXResult(new XFormsAnnotatorContentHandler(this.metadata)));
                    // Re-attach xhtml element
                    if (htmlElement != null)
                        staticStateElement.add(htmlElement);
//                } else {
//                    // Recompute from xhtmlDocument
//                    final TransformerHandler identity = TransformerUtils.getIdentityTransformerHandler();
//                    identity.setResult(new SAXResult(new XFormsAnnotatorContentHandler(namespacesMap)));
//                    xhtmlDocument.replay(identity);
//                }
            } catch (Exception e) {
                throw new OXFException(e);
            }
        } else {
            // Use map that was passed
            this.metadata = metadata;
        }

        final List<Element> topLevelModelsElements = Dom4jUtils.elements(staticStateElement, XFormsConstants.XFORMS_MODEL_QNAME);

        // Extract properties information
        // Do this first so that e.g. extracted models know about properties
        extractProperties(staticStateElement, topLevelModelsElements);

        // Extract controls, models and components documents
        extractControlsModelsComponents(propertyContext, staticStateElement, topLevelModelsElements);

        // Extract XHTML if present and requested
        {
            if (xhtmlDocument == null && htmlElement != null) {
                // Get from static state document if available there
                final Document htmlDocument = Dom4jUtils.createDocument();
                htmlDocument.setRootElement((Element) htmlElement.detach());
                this.xhtmlDocument = TransformerUtils.dom4jToSAXStore(htmlDocument);
            } else if (isNoscript()) {
                // Use provided SAXStore ONLY if noscript mode is requested
                this.xhtmlDocument = xhtmlDocument;
            } else if (this.metadata.hasTopLevelMarks()) {
                // Keep XHTML if we have top-level marks
                this.xhtmlDocument = xhtmlDocument;
            } else {
                // Otherwise there is no need to keep XHTML
                this.xhtmlDocument = null;
            }

            if (this.xhtmlDocument != null && indentedLogger.isDebugEnabled()) {
                indentedLogger.logDebug("", "keeping XHTML tree", "approximate size (bytes)", Long.toString(this.xhtmlDocument.getApproximateSize()));
            }
        }

        // Extract versioned paths matchers if present
        {
            final Element matchersElement = staticStateElement.element("matchers");
            if (matchersElement != null) {
                final List<Element> matchersElements = Dom4jUtils.elements(matchersElement, "matcher");
                this.versionedPathMatchers = new ArrayList<URLRewriterUtils.PathMatcher>(matchersElements.size());
                for (Element currentMatcherElement: matchersElements) {
                    versionedPathMatchers.add(new URLRewriterUtils.PathMatcher(currentMatcherElement));
                }
            } else {
                // Otherwise use matchers from the pipeline context
                this.versionedPathMatchers = (List<URLRewriterUtils.PathMatcher>) propertyContext.getAttribute(PipelineContext.PATH_MATCHERS);
            }
        }

        if (encodedStaticState != null) {
            // Static state is fully initialized
            this.encodedStaticState = encodedStaticState;
            initialized = true;
        } else {
            // Remember this temporarily only if the encoded state is not yet known
            this.staticStateDocument = staticStateDocument;
            initialized = false;
        }

        indentedLogger.endHandleOperation();
    }

    private void extractProperties(Element staticStateElement, List<Element> topLevelModelsElements) {
        // Gather xxforms:* properties
        {
            // Global properties (outside models and controls)
            {
                final Element propertiesElement = staticStateElement.element(XFormsConstants.STATIC_STATE_PROPERTIES_QNAME);
                if (propertiesElement != null) {
                    for (Iterator i = propertiesElement.attributeIterator(); i.hasNext();) {
                        final Attribute currentAttribute = (Attribute) i.next();
                        final String propertyName = currentAttribute.getName();
                        final Object propertyValue = XFormsProperties.parseProperty(propertyName, currentAttribute.getValue());
                        if (propertyValue != null) {
                            nonDefaultProperties.put(currentAttribute.getName(), propertyValue);
                        } else {
                            indentedLogger.logWarning("", "ignoring global property", "name", propertyName);
                        }
                    }
                }
            }
            // Properties on top-level xforms:model elements
            for (final Element modelElement: topLevelModelsElements) {
                for (Iterator j = modelElement.attributeIterator(); j.hasNext();) {
                    final Attribute currentAttribute = (Attribute) j.next();
                    if (XFormsConstants.XXFORMS_NAMESPACE_URI.equals(currentAttribute.getNamespaceURI())) {
                        final String propertyName = currentAttribute.getName();
                        final Object propertyValue = XFormsProperties.parseProperty(propertyName, currentAttribute.getValue());
                        if (propertyValue != null) {
                            // Only take the first occurrence into account, and make sure the property is supported
                            if (nonDefaultProperties.get(propertyName) == null && XFormsProperties.getPropertyDefinition(propertyName) != null)
                                nonDefaultProperties.put(propertyName, propertyValue);
                        } else {
                            indentedLogger.logWarning("", "ignoring property on xforms:model element", "name", propertyName);
                        }
                    }
                }
            }
        }

        // Handle default for properties
        final PropertySet propertySet = Properties.instance().getPropertySet();
        for (Iterator i = XFormsProperties.getPropertyDefinitionEntryIterator(); i.hasNext();) {
            final Map.Entry currentEntry = (Map.Entry) i.next();
            final String propertyName = (String) currentEntry.getKey();
            final XFormsProperties.PropertyDefinition propertyDefinition = (XFormsProperties.PropertyDefinition) currentEntry.getValue();

            final Object defaultPropertyValue = propertyDefinition.getDefaultValue(); // value can be String, Boolean, Integer
            final Object actualPropertyValue = nonDefaultProperties.get(propertyName); // value can be String, Boolean, Integer
            if (actualPropertyValue == null) {
                // Property not defined in the document, try to obtain from global properties
                final Object globalPropertyValue = propertySet.getObject(XFormsProperties.XFORMS_PROPERTY_PREFIX + propertyName, defaultPropertyValue);

                // If the global property is different from the default, add it
                if (!globalPropertyValue.equals(defaultPropertyValue))
                    nonDefaultProperties.put(propertyName, globalPropertyValue);

            } else {
                // Property defined in the document

                // If the property is identical to the default, remove it
                if (actualPropertyValue.equals(defaultPropertyValue))
                    nonDefaultProperties.remove(propertyName);
            }
        }

        // Check validity of properties of known type
        {
            {
                final String stateHandling = getStringProperty(XFormsProperties.STATE_HANDLING_PROPERTY);
                if (!(stateHandling.equals(XFormsProperties.STATE_HANDLING_CLIENT_VALUE)
                                || stateHandling.equals(XFormsProperties.STATE_HANDLING_SERVER_VALUE)))
                    throw new ValidationException("Invalid xxforms:" + XFormsProperties.STATE_HANDLING_PROPERTY + " attribute value: " + stateHandling, getLocationData());
            }

            {
                final String readonlyAppearance = getStringProperty(XFormsProperties.READONLY_APPEARANCE_PROPERTY);
                if (!(readonlyAppearance.equals(XFormsProperties.READONLY_APPEARANCE_STATIC_VALUE)
                                || readonlyAppearance.equals(XFormsProperties.READONLY_APPEARANCE_DYNAMIC_VALUE)))
                    throw new ValidationException("Invalid xxforms:" + XFormsProperties.READONLY_APPEARANCE_PROPERTY + " attribute value: " + readonlyAppearance, getLocationData());
            }
        }

        // Parse external-events property
        final String externalEvents = getStringProperty(XFormsProperties.EXTERNAL_EVENTS_PROPERTY);
        if (externalEvents != null) {
            final StringTokenizer st = new StringTokenizer(externalEvents);
            while (st.hasMoreTokens()) {
                allowedExternalEvents.add(st.nextToken());
            }
        }
    }

    private void extractControlsModelsComponents(PropertyContext pipelineContext, Element staticStateElement, List<Element> topLevelModelsElements) {

        // Extract static components information
        // NOTE: Do this here so that xblBindings is available for scope resolution  
        xblBindings = new XBLBindings(indentedLogger, this, metadata, staticStateElement);

        // Get top-level models from static state document
        {
            // FIXME: we don't get a System ID here. Is there a simple solution?
            int modelsCount = 0;
            for (final Element modelElement: topLevelModelsElements) {
                // Copy the element because we may need it in staticStateDocument for encoding
                final Document modelDocument = Dom4jUtils.createDocumentCopyParentNamespaces(modelElement);
                addModelDocument(xblBindings.getTopLevelScope(), modelDocument);
                modelsCount++;
            }

            indentedLogger.logDebug("", "created top-level model documents", "count", Integer.toString(modelsCount));
        }

        // Get controls document
        {
            // Create document
            controlsDocument = Dom4jUtils.createDocument();
            final Element controlsElement = Dom4jUtils.createElement("controls");
            controlsDocument.setRootElement(controlsElement);

            // Find all top-level controls
            int topLevelControlsCount = 0;
            for (Object o: staticStateElement.elements()) {
                final Element currentElement = (Element) o;
                final QName currentElementQName = currentElement.getQName();

                if (!currentElementQName.equals(XFormsConstants.XFORMS_MODEL_QNAME)
                        && !currentElementQName.equals(XMLConstants.XHTML_HTML_QNAME)
                        && !XFormsConstants.XBL_NAMESPACE_URI.equals(currentElement.getNamespaceURI())
                        && currentElement.getNamespaceURI() != null && !"".equals(currentElement.getNamespaceURI())) {
                    // Any element in a namespace (xforms:*, xxforms:*, exforms:*, custom namespaces) except xforms:model, xhtml:html and xbl:*

                    // Copy the element because we may need it in staticStateDocument for encoding
                    controlsElement.add(Dom4jUtils.copyElementCopyParentNamespaces(currentElement));
                    topLevelControlsCount++;
                }
            }

            indentedLogger.logDebug("", "created controls document", "top-level controls count", Integer.toString(topLevelControlsCount));
        }

        // Extract models nested within controls
        {
            final DocumentWrapper controlsDocumentInfo = new DocumentWrapper(controlsDocument, null, xpathConfiguration);
            final List<Document> extractedModels = extractNestedModels(pipelineContext, controlsDocumentInfo, false, locationData);
            indentedLogger.logDebug("", "created nested model documents", "count", Integer.toString(extractedModels.size()));
            for (final Document currentModelDocument: extractedModels) {
                addModelDocument(xblBindings.getTopLevelScope(), currentModelDocument);
            }
        }
    }

    /**
     * Register a model document. Used by this and XBLBindings.
     *
     * @param scope             XBL scope
     * @param modelDocument     model document
     */
    public void addModelDocument(XBLBindings.Scope scope, Document modelDocument) {
        List<Model> models = modelsByScope.get(scope);
        if (models == null) {
            models = new ArrayList<Model>();
            modelsByScope.put(scope, models);
        }
        final Model newModel = new Model(this, scope, modelDocument);
        models.add(newModel);
        modelsByPrefixedId.put(newModel.prefixedId, newModel);
    }

    public Model getModel(String prefixedId) {
        return modelsByPrefixedId.get(prefixedId);
    }

    public void extractXFormsScripts(PropertyContext pipelineContext, DocumentWrapper documentInfo, String prefix) {

        // TODO: Not sure why we actually extract the scripts: we could just keep pointers on them, right? There is
        // probably not a notable performance if any at all, especially since this is needed at page generation time
        // only.
 
        final String xpathExpression = "/descendant-or-self::xxforms:script[not(ancestor::xforms:instance) and exists(@id)]";

        final List scripts = XPathCache.evaluate(pipelineContext, documentInfo, xpathExpression,
                BASIC_NAMESPACE_MAPPINGS, null, null, null, null, locationData);

        if (scripts.size() > 0) {
            if (xxformsScripts == null)
                xxformsScripts = new HashMap<String, String>();
            for (Object script: scripts) {
                final NodeInfo currentNodeInfo = (NodeInfo) script;
                final Element scriptElement = (Element) ((NodeWrapper) currentNodeInfo).getUnderlyingNode();

                // Remember script content
                xxformsScripts.put(prefix + XFormsUtils.getElementStaticId(scriptElement), scriptElement.getStringValue());
            }
        }
    }

    public String getUUID() {
        return uuid;
    }

    /**
     * Get a serialized static state. If an encodedStaticState was provided during restoration, return that. Otherwise,
     * return a serialized static state computed from models, instances, and XHTML documents.
     *
     * @param propertyContext   current PropertyContext
     * @return                  serialized static sate
     */
    public String getEncodedStaticState(PropertyContext propertyContext) {

        if (!initialized) {

            final Element rootElement = staticStateDocument.getRootElement();

            if (rootElement.element("instances") != null)
                throw new IllegalStateException("Element instances already present in static state.");

            // TODO: if staticStateDocument will contains XHTML document, don't store controls and models in there

            // Handle XHTML document if needed (for noscript mode)
            if (xhtmlDocument != null && rootElement.element(XMLConstants.XHTML_HTML_QNAME) == null) {
                // Add document
                final Document document = TransformerUtils.saxStoreToDom4jDocument(xhtmlDocument);
                staticStateDocument.getRootElement().add(document.getRootElement().detach());
            }

            // Remember versioned paths
            if (versionedPathMatchers != null && versionedPathMatchers.size() > 0) {
                final Element matchersElement = rootElement.addElement("matchers");
                for (final URLRewriterUtils.PathMatcher pathMatcher: versionedPathMatchers) {
                    matchersElement.add(pathMatcher.serialize());
                }
            }

            // Remember encoded state and discard Document
            final boolean isStateHandlingClient = getStringProperty(XFormsProperties.STATE_HANDLING_PROPERTY).equals(XFormsProperties.STATE_HANDLING_CLIENT_VALUE);
            encodedStaticState = XFormsUtils.encodeXML(propertyContext, staticStateDocument, isStateHandlingClient ? XFormsProperties.getXFormsPassword() : null, true);

            staticStateDocument = null;
            initialized = true;
        }

        return encodedStaticState;
    }

    /**
     * Return all instance containers of the specified model.
     *
     * @param modelPrefixedId       model prefixed id
     * @return                      container elements
     */
    public List<Element> getInstanceContainers(String modelPrefixedId) {
        return modelsByPrefixedId.get(modelPrefixedId).instanceElements;
    }

    /**
     * Whether the noscript mode is enabled.
     *
     * @return true iif enabled
     */
    public final boolean isNoscript() {
        readPropertiesIfNeeded();
        return isNoscript;
    }

    /**
     * Whether XPath analysis is enabled.
     *
     * @return true iif enabled
     */
    public final boolean isXPathAnalysis() {
        readPropertiesIfNeeded();
        return isXPathAnalysis;
    }

    private void readPropertiesIfNeeded() {
        if (!propertiesRead) {
            // NOTE: Later can be also based on:
            // o native controls used
            // o XBL hints
            isNoscript = Version.instance().isPEFeatureEnabled(getBooleanProperty(XFormsProperties.NOSCRIPT_PROPERTY)
                    && getBooleanProperty(XFormsProperties.NOSCRIPT_SUPPORT_PROPERTY), XFormsProperties.NOSCRIPT_PROPERTY);

            isXPathAnalysis = Version.instance().isPEFeatureEnabled(getBooleanProperty(XFormsProperties.XPATH_ANALYSIS_PROPERTY),
                    XFormsProperties.XPATH_ANALYSIS_PROPERTY);

            propertiesRead = true;
        }
    }

    /**
     * Return the complete XHTML document if available. Only for noscript mode.
     *
     * @return  SAXStore containing XHTML document
     */
    public SAXStore getXHTMLDocument() {
        return xhtmlDocument;
    }

    public Document getControlsDocument() {
        return controlsDocument;
    }

    public Model getDefaultModelForScope(XBLBindings.Scope scope) {
        final List<Model> models = modelsByScope.get(scope);
        if (models == null || models.size() == 0) {
            // No model found for the given scope
            return null;
        } else {
            return models.get(0);
        }
    }

    public final List<Model> getModelsForScope(XBLBindings.Scope scope) {
        final List<Model> models = modelsByScope.get(scope);
        return (models != null) ? models : Collections.<Model>emptyList();
    }

    public String getDefaultModelPrefixedIdForScope(XBLBindings.Scope scope) {
        final Model model = getDefaultModelForScope(scope);
        return (model != null) ? model.prefixedId : null;
    }

    public String getDefaultInstancePrefixedIdForScope(XBLBindings.Scope scope) {
        final Model model = getDefaultModelForScope(scope);
        return (model != null) ? model.defaultInstancePrefixedId: null;
    }

    public String getDefaultModelId() {
        return getDefaultModelForScope(xblBindings.getTopLevelScope()).prefixedId;
    }

    public String getDefaultInstanceId() {
        return getDefaultInstancePrefixedIdForScope(xblBindings.getTopLevelScope());
    }

    public String findInstancePrefixedId(XBLBindings.Scope startScope, String instanceStaticId) {
        XBLBindings.Scope currentScope = startScope;
        while (currentScope != null) {
            for (final Model model: getModelsForScope(currentScope)) {
                if (model.instanceStaticIds.contains(instanceStaticId)) {
                    return currentScope.getPrefixedIdForStaticId(instanceStaticId);
                }
            }
            currentScope = currentScope.parent;
        }
        return null;
    }

    public Map<String, String> getScripts() {
        return xxformsScripts;
    }

    public XFormsConstants.DeploymentType getDeploymentType() {
        return deploymentType;
    }

    public String getRequestContextPath() {
        return requestContextPath;
    }

    public Set<String> getAllowedExternalEvents() {
        return allowedExternalEvents;
    }

    public String getContainerType() {
        return containerType;
    }

    public String getContainerNamespace() {
        return containerNamespace;
    }

    public LocationData getLocationData() {
        return locationData;
    }

    public Map<String, Object> getNonDefaultProperties() {
        return nonDefaultProperties;
    }
    
    public Object getProperty(String propertyName) {
        final Object documentProperty = nonDefaultProperties.get(propertyName);
        if (documentProperty != null) {
            return documentProperty;
        } else {
            final XFormsProperties.PropertyDefinition propertyDefinition = XFormsProperties.getPropertyDefinition(propertyName);
            return (propertyDefinition != null) ? propertyDefinition.getDefaultValue() : null; // may be null for example for type formats
        }
    }

    public String getStringProperty(String propertyName) {
        final String documentProperty = (String) nonDefaultProperties.get(propertyName);
        if (documentProperty != null) {
            return documentProperty;
        } else {
            final XFormsProperties.PropertyDefinition propertyDefinition = XFormsProperties.getPropertyDefinition(propertyName);
            return (propertyDefinition != null) ? (String) propertyDefinition.getDefaultValue() : null; // may be null for example for type formats
        }
    }

    public boolean getBooleanProperty(String propertyName) {
        final Boolean documentProperty = (Boolean) nonDefaultProperties.get(propertyName);
        if (documentProperty != null) {
            return documentProperty;
        } else {
            final XFormsProperties.PropertyDefinition propertyDefinition = XFormsProperties.getPropertyDefinition(propertyName);
            return (Boolean) propertyDefinition.getDefaultValue();
        }
    }

    public int getIntegerProperty(String propertyName) {
        final Integer documentProperty = (Integer) nonDefaultProperties.get(propertyName);
        if (documentProperty != null) {
            return documentProperty;
        } else {
            final XFormsProperties.PropertyDefinition propertyDefinition = XFormsProperties.getPropertyDefinition(propertyName);
            return (Integer) propertyDefinition.getDefaultValue();
        }
    }

    public List<XFormsEventHandler> getEventHandlers(String observerPrefixedId) {
        return eventHandlersMap.get(observerPrefixedId);
    }

    public boolean observerHasHandlerForEvent(String observerPrefixedId, String eventName) {
        final List<XFormsEventHandler> handlers = getEventHandlers(observerPrefixedId);
        if (handlers == null || handlers.isEmpty())
            return false;
        for (XFormsEventHandler handler: handlers) {
            if (handler.isAllEvents() || handler.getEventNames().contains(eventName))
                return true;
        }
        return false;
    }

    public Map<String, ControlAnalysis> getRepeatControlAnalysisMap() {
        return controlTypes.get("repeat");
    }

    public Element getControlElement(String prefixedId) {
        final ControlAnalysis controlAnalysis = controlAnalysisMap.get(prefixedId);
        return (controlAnalysis == null) ? null : controlAnalysis.element;
    }

    public int getControlPosition(String prefixedId) {
        final ControlAnalysis controlAnalysis = controlAnalysisMap.get(prefixedId);
        return (controlAnalysis == null) ? -1 : controlAnalysis.index;
    }

    public boolean hasNodeBinding(String prefixedId) {
        final ControlAnalysis controlAnalysis = controlAnalysisMap.get(prefixedId);
        return (controlAnalysis == null) ? false : controlAnalysis.hasNodeBinding;
    }

    public ControlAnalysis.LHHAAnalysis getLabel(String prefixedId) {
        final ControlAnalysis controlAnalysis = controlAnalysisMap.get(prefixedId);
        return (controlAnalysis == null) ? null : controlAnalysis.getLabel();
    }

    public ControlAnalysis.LHHAAnalysis getHelp(String prefixedId) {
        final ControlAnalysis controlAnalysis = controlAnalysisMap.get(prefixedId);
        return (controlAnalysis == null) ? null : controlAnalysis.getHelp();
    }

    public ControlAnalysis.LHHAAnalysis getHint(String prefixedId) {
        final ControlAnalysis controlAnalysis = controlAnalysisMap.get(prefixedId);
        return (controlAnalysis == null) ? null : controlAnalysis.getHint();
    }

    public ControlAnalysis.LHHAAnalysis getAlert(String prefixedId) {
        final ControlAnalysis controlAnalysis = controlAnalysisMap.get(prefixedId);
        return (controlAnalysis == null) ? null : controlAnalysis.getAlert();
    }

    /**
     * Statically check whether a control is a value control.
     *
     * @param controlEffectiveId    prefixed id or effective id of the control
     * @return                      true iif the control is a value control
     */
    public boolean isValueControl(String controlEffectiveId) {
        final ControlAnalysis controlAnalysis = controlAnalysisMap.get(XFormsUtils.getPrefixedId(controlEffectiveId));
        return (controlAnalysis != null) && controlAnalysis.hasValue;
    }

    /**
     * Return the namespace mappings for a given element. If the element does not have an id, or if the mapping is not
     * cached, compute the mapping on the fly. Note that in this case, the resulting mapping is not added to the cache
     * as the mapping is considered transient and not sharable among pages.
     *
     * @param prefix
     * @param element       Element to get namespace mapping for
     * @return              Map<String prefix, String uri>
     */
    public Map<String, String> getNamespaceMappings(String prefix, Element element) {
        final String id = XFormsUtils.getElementStaticId(element);
        if (id != null) {
            // There is an id attribute
            final String prefixedId = (prefix != null) ? prefix + id : id; 
            final Map<String, String> cachedMap = metadata.namespaceMappings.get(prefixedId);
            if (cachedMap != null) {
                return cachedMap;
            } else {
                indentedLogger.logDebug("", "namespace mappings not cached",
                        "prefix", prefix, "element", Dom4jUtils.elementToDebugString(element));
                return Dom4jUtils.getNamespaceContextNoDefault(element);
            }
        } else {
            // No id attribute
            indentedLogger.logDebug("", "namespace mappings not available because element doesn't have an id attribute",
                    "prefix", prefix, "element", Dom4jUtils.elementToDebugString(element));
            return Dom4jUtils.getNamespaceContextNoDefault(element);
        }
    }

    public String getRepeatHierarchyString() {
        return repeatHierarchyString;
    }

    public boolean hasControlByName(String controlName) {
        return controlTypes.get(controlName) != null;
    }

    public Select1Analysis getSelect1Analysis(String controlPrefixedId) {
        return ((Select1Analysis) controlAnalysisMap.get(controlPrefixedId));
    }

    /**
     * Whether a host language element with the given id ("for attribute") has an AVT on an attribute.
     *
     * @param prefixedForAttribute  id of the host language element to check
     * @return                      true iif that element has one or more AVTs
     */
    public boolean hasAttributeControl(String prefixedForAttribute) {
        return attributeControls != null && attributeControls.get(prefixedForAttribute) != null;
    }

    public ControlAnalysis getAttributeControl(String prefixedForAttribute, String attributeName) {
        final Map<String, ControlAnalysis> mapForId = attributeControls.get(prefixedForAttribute);
        return (mapForId != null) ? mapForId.get(attributeName) : null;
    }

    /**
     * Return XBL bindings information.
     *
     * @return XBL bindings information
     */
    public XBLBindings getXBLBindings() {
        return xblBindings;
    }

    /**
     * Perform static analysis on this document if not already done.
     *
     * @param propertyContext   current pipeline context
     * @return                  true iif analysis was just performed in this call
     */
    public synchronized boolean analyzeIfNecessary(final PropertyContext propertyContext) {
        if (!isAnalyzed) {
            final long startTime = indentedLogger.isDebugEnabled() ? System.currentTimeMillis() : 0;

            controlTypes = new HashMap<String, Map<String, ControlAnalysis>>();
            eventNames = new HashSet<String>();
            eventHandlersMap = new HashMap<String, List<XFormsEventHandler>>();
            eventHandlerAncestorsMap = new HashMap<String, String>();
            keyHandlers = new ArrayList<XFormsEventHandler>();
            controlAnalysisMap = new LinkedHashMap<String, ControlAnalysis>();
            repeatChildrenMap = new HashMap<String, List<String>>();

            // Iterate over main static controls tree
            final StringBuilder repeatHierarchyStringBuffer = new StringBuilder(1024);
            final XBLBindings.Scope rootScope = xblBindings.getResolutionScopeById("");
            final ContainerAnalysis rootControlAnalysis = new RootAnalysis(propertyContext, this, rootScope);

            analyzeComponentTree(propertyContext, xpathConfiguration, rootScope,
                    controlsDocument.getRootElement(), rootControlAnalysis, repeatHierarchyStringBuffer);

            if (xxformsScripts != null && xxformsScripts.size() > 0)
                indentedLogger.logDebug("", "extracted script elements", "count", Integer.toString(xxformsScripts.size()));

            // Finalize repeat hierarchy
            repeatHierarchyString = repeatHierarchyStringBuffer.toString();

            // Iterate over models to extract event handlers and scripts
            for (final Map.Entry<String, Model> currentEntry: modelsByPrefixedId.entrySet()) {
                final String modelPrefixedId = currentEntry.getKey();
                final Document modelDocument = currentEntry.getValue().document;
                final DocumentWrapper modelDocumentInfo = new DocumentWrapper(modelDocument, null, xpathConfiguration);
                // NOTE: Say we don't want to exclude gathering event handlers within nested models, since this is a model
                extractEventHandlers(propertyContext, xpathConfiguration, modelDocumentInfo, XFormsUtils.getEffectiveIdPrefix(modelPrefixedId), false);
                extractXFormsScripts(propertyContext, modelDocumentInfo, XFormsUtils.getEffectiveIdPrefix(modelPrefixedId));
            }

            if (indentedLogger.isDebugEnabled()) {
                indentedLogger.logDebug("", "performed static analysis",
                            "time", Long.toString(System.currentTimeMillis() - startTime),
                            "controls", Integer.toString(controlAnalysisMap.size()));

            }

            // Once analysis is done, some state can be freed
            xblBindings.freeTransientState();

            isAnalyzed = true;
            return true;
        } else {
            indentedLogger.logDebug("", "static analysis already available");
            return false;
        }
    }

    private void extractEventHandlers(PropertyContext propertyContext, Configuration xpathConfiguration, DocumentInfo documentInfo, String prefix, boolean isControls) {

        // Register event handlers on any element which has an id or an observer attribute. This also allows
        // registering event handlers on XBL components. This follows the semantics of XML Events.

        // Special work is done to annotate handlers children of bound nodes, because that annotation is not done
        // in XFormsAnnotatorContentHandler. Maybe it should be done there?

        // Top-level handlers within controls are also handled. If they don't have an @ev:observer attribute, they
        // are assumed to listen to a virtual element around all controls. 

        // NOTE: Placing a listener on say a <div> element won't work at this point. Listeners have to be placed within
        // elements which have a representation in the compact component tree.
        // UPDATE: This is right to a point: things should work for elements with @ev:observer, and element which have a
        // compact tree ancestor element. Will not work for top-level handlers without @ev:observer though. Check more!

        // Two expressions depending on whether handlers within models are excluded or not
        final String xpathExpression = isControls ?
                "//*[@ev:event and not(ancestor::xforms:instance) and not(ancestor::xforms:model) and (parent::*/@id or @ev:observer or /* is ..)]" :
                "//*[@ev:event and not(ancestor::xforms:instance) and (parent::*/@id or @ev:observer or /* is ..)]";

        // Get all candidate elements
        final List actionHandlers = XPathCache.evaluate(propertyContext, documentInfo,
                xpathExpression, BASIC_NAMESPACE_MAPPINGS, null, null, null, null, locationData);

        final XBLBindings.Scope innerScope = xblBindings.getResolutionScopeByPrefix(prefix); // if at top-level, prefix is ""
        final XBLBindings.Scope outerScope = (prefix.length() == 0) ? xblBindings.getTopLevelScope() : xblBindings.getResolutionScopeByPrefixedId(innerScope.scopeId);

        // Check all candidate elements
        for (Object actionHandler: actionHandlers) {
            final NodeInfo currentNodeInfo = (NodeInfo) actionHandler;

            if (currentNodeInfo instanceof NodeWrapper) {
                final Element actionElement = (Element) ((NodeWrapper) currentNodeInfo).getUnderlyingNode();

                if (XFormsActions.isActionName(actionElement.getNamespaceURI(), actionElement.getName())) {
                    // This is a known action name

                    final Element newActionElement;
                    final String evObserversStaticIds = actionElement.attributeValue(XFormsConstants.XML_EVENTS_EV_OBSERVER_ATTRIBUTE_QNAME);

                    final String parentStaticId;
                    if (isControls) {
                        // Analyzing controls

                        if (isControlsTopLevelHandler(actionElement)) {
                            // Specially handle #document static id for top-level handlers
                            parentStaticId = XFormsContainingDocument.CONTAINING_DOCUMENT_PSEUDO_ID;
                        } else {
                             // Nested handler
                            parentStaticId = XFormsUtils.getElementStaticId(actionElement.getParent());
                        }

                        if (parentStaticId != null) {
                            final String parentPrefixedId = prefix + parentStaticId;
                            if (xblBindings.hasBinding(parentPrefixedId)) {
                                // Parent is a bound node, so we found an action handler which is a child of a bound element

                                // Annotate handler
                                final XBLBindings.Scope bindingScope = xblBindings.getResolutionScopeByPrefixedId(parentPrefixedId);
                                final XFormsConstants.XXBLScope startScope = innerScope.equals(bindingScope) ? XFormsConstants.XXBLScope.inner : XFormsConstants.XXBLScope.outer;
                                newActionElement = xblBindings.annotateHandler(actionElement, prefix, innerScope, outerScope, startScope).getRootElement();

                                // Extract scripts in the handler
                                final DocumentWrapper handlerWrapper = new DocumentWrapper(newActionElement.getDocument(), null, xpathConfiguration);
                                extractXFormsScripts(propertyContext, handlerWrapper, prefix);

                            } else if (controlAnalysisMap.containsKey(parentPrefixedId)) {
                                // Parent is a control but not a bound node
                                newActionElement = actionElement;
                            } else if (isControlsTopLevelHandler(actionElement)) {
                                // Handler is a top-level handler
                                newActionElement = actionElement;
                            } else {
                                // Neither
                                newActionElement = null;
                            }
                        } else if (evObserversStaticIds != null) {
                            // There is no parent static id but an explicit @ev:observer
                            // TODO: if the element is a descendant of a bound node, it must be ignored
                            newActionElement = actionElement;
                        } else {
                            // No parent id and no @ev:observer, so we ignore the handler
                            newActionElement = null;
                        }
                    } else {
                        // Analyzing models
                        newActionElement = actionElement;
                        parentStaticId = XFormsUtils.getElementStaticId(actionElement.getParent());
                    }

                    // Register action handler
                    if (newActionElement != null) {
                        // If possible, find closest ancestor observer for XPath context evaluation

                        final String ancestorObserverStaticId; {
                            final Element ancestorObserver = findAncestorObserver(actionElement);
                            if (ancestorObserver != null) {
                                assert XFormsUtils.getElementStaticId(ancestorObserver) != null : "ancestor observer must have an id";
                                ancestorObserverStaticId = XFormsUtils.getElementStaticId(ancestorObserver);
                            } else if (isControls && isControlsTopLevelHandler(actionElement)) {
                                // Specially handle #document static id for top-level handlers
                                ancestorObserverStaticId = XFormsContainingDocument.CONTAINING_DOCUMENT_PSEUDO_ID;
                            } else {
                                ancestorObserverStaticId = null;
                            }
                        }

                        // The observers to which this handler is attached might not be in the same scope. Try to find
                        // that scope.
                        final String observersPrefix;
                        if (evObserversStaticIds != null) {
                            // Explicit ev:observer, prefix might be different
                            final XBLBindings.Scope actionScope = xblBindings.getResolutionScopeByPrefixedId(prefix + XFormsUtils.getElementStaticId(newActionElement));
                            observersPrefix = (actionScope != null) ? actionScope.getFullPrefix() : prefix;
                        } else {
                            // Parent is observer and has the same prefix
                            observersPrefix = prefix;
                        }

                        // Create and register the handler
                        final XFormsEventHandlerImpl eventHandler = new XFormsEventHandlerImpl(prefix, newActionElement, parentStaticId, ancestorObserverStaticId);
                        registerActionHandler(eventHandler, observersPrefix);
                    }
                }
            }
        }
    }

    private boolean isControlsTopLevelHandler(Element actionElement) {
        // Structure is:
        // <controls>
        //   <xforms:action .../>
        //   ...
        // </controls>
        return actionElement.getParent() == actionElement.getDocument().getRootElement();
    }

    private Element findAncestorObserver(Element actionElement) {
        // Recurse until we find an element which is an event observer
        Element currentAncestor = actionElement.getParent();
        while (currentAncestor != null) {
            if (isEventObserver(currentAncestor))
                return currentAncestor;
            currentAncestor = currentAncestor.getParent();
        }

        return null;
    }

    /**
     * Return true if the given element is an event observer. Must return true for controls, components, xforms:model,
     * xforms:instance, xforms:submission.
     *
     * @param element       element to check
     * @return              true iif the element is an event observer
     */
    private boolean isEventObserver(Element element) {

        // Whether this is a built-in control or a component
        if (XFormsControlFactory.isBuiltinControl(element.getNamespaceURI(), element.getName()) || xblBindings.isComponent(element.getQName())) {
            return true;
        }

        final String localName = element.getName();
        if (XFormsConstants.XFORMS_NAMESPACE_URI.equals(element.getNamespaceURI())
                && ("model".equals(localName) || "instance".equals(localName) || "submission".equals(localName))) {
            return true;
        }

        return false;
    }

    public void analyzeComponentTree(final PropertyContext propertyContext, final Configuration xpathConfiguration,
                                     final XBLBindings.Scope innerScope, Element startElement, final ContainerAnalysis startControlAnalysis,
                                     final StringBuilder repeatHierarchyStringBuffer) {

        final DocumentWrapper controlsDocumentInfo = new DocumentWrapper(startElement.getDocument(), null, xpathConfiguration);

        final String prefix = innerScope.getFullPrefix();

        // Extract scripts for this tree of controls
        extractXFormsScripts(propertyContext, controlsDocumentInfo, prefix);

        final Map<String, Element> deferredExternalLHHA = new LinkedHashMap<String, Element>();

        // Visit tree
        visitAllControlStatic(startElement, startControlAnalysis, new XFormsStaticState.ControlElementVisitorListener() {

            public void handleLHHA(Element lhhaElement, String controlStaticId) {
                // LHHA within a grouping control or at top-level

                assert controlStaticId != null;

                if (!processLHHAElement(propertyContext, controlsDocumentInfo, lhhaElement, controlStaticId, prefix)) {
                    deferredExternalLHHA.put(controlStaticId, lhhaElement);
                }
            }

            public ControlAnalysis startVisitControl(Element controlElement, ContainerAnalysis parentControlAnalysis, String controlStaticId, boolean isContainer) {

                // Check for mandatory id
                if (controlStaticId == null)
                    throw new ValidationException("Missing mandatory id for element: " + controlElement.getQualifiedName(), locationData);

                // Prefixed id
                final String controlPrefixedId = prefix + controlStaticId;

                // Gather control name
                final String controlName = controlElement.getName();

                final LocationData locationData = new ExtendedLocationData((LocationData) controlElement.getData(), "gathering static control information", controlElement);

                // If element is not built-in, check XBL and generate shadow content if needed
                xblBindings.processElementIfNeeded(propertyContext, indentedLogger, controlElement, controlPrefixedId, locationData,
                        controlsDocumentInfo, xpathConfiguration, innerScope, parentControlAnalysis, repeatHierarchyStringBuffer);

                // Create and index static control information
                final ControlAnalysis controlAnalysis; {
                    final XBLBindings.Scope controlScope = xblBindings.getResolutionScopeByPrefixedId(controlPrefixedId);
                    final int controlIndex = controlAnalysisMap.size() + 1;
                    final Map<String, ControlAnalysis> inScopeVariables = parentControlAnalysis.getInScopeVariablesForContained(controlAnalysisMap);

                    controlAnalysis = ControlAnalysisFactory.create(propertyContext, XFormsStaticState.this, controlsDocumentInfo,
                            controlScope, controlElement, controlIndex, isContainer, parentControlAnalysis, inScopeVariables);
                }

                controlAnalysisMap.put(controlAnalysis.prefixedId, controlAnalysis);
                {
                    Map<String, ControlAnalysis> controlsMap = controlTypes.get(controlName);
                    if (controlsMap == null) {
                        controlsMap = new LinkedHashMap<String, ControlAnalysis>();
                        controlTypes.put(controlName, controlsMap);
                    }

                    controlsMap.put(controlAnalysis.prefixedId, controlAnalysis);
                }

                // TODO: move repeat and attribute cases below to RepeatAnalysis and AttributeAnalysis
                if (controlAnalysis instanceof RepeatAnalysis) {
                    // Gather xforms:repeat information

                    final RepeatAnalysis ancestorRepeatAnalysis = controlAnalysis.getAncestorRepeat();

                    // Find repeat parents
                    {
                        // Create repeat hierarchy string
                        if (repeatHierarchyStringBuffer.length() > 0)
                            repeatHierarchyStringBuffer.append(',');

                        repeatHierarchyStringBuffer.append(controlAnalysis.prefixedId);

                        if (ancestorRepeatAnalysis != null) {
                            // If we have a parent, append it
                            repeatHierarchyStringBuffer.append(' ');
                            repeatHierarchyStringBuffer.append(ancestorRepeatAnalysis.prefixedId);
                        }
                    }
                    // Find repeat children
                    {
                        if (ancestorRepeatAnalysis != null) {
                            // If we have a parent, tell the parent that it has a child
                            final String parentRepeatId = ancestorRepeatAnalysis.prefixedId;
                            List<String> parentRepeatList = repeatChildrenMap.get(parentRepeatId);
                            if (parentRepeatList == null) {
                                parentRepeatList = new ArrayList<String>();
                                repeatChildrenMap.put(parentRepeatId, parentRepeatList);
                            }
                            parentRepeatList.add(controlAnalysis.prefixedId);
                        }

                    }
                } else if ("attribute".equals(controlName)) {
                    // Special indexing of xxforms:attribute controls
                    final String prefixedForAttribute = prefix + controlElement.attributeValue("for");
                    final String nameAttribute = controlElement.attributeValue("name");
                    Map<String, ControlAnalysis> mapForId;
                    if (attributeControls == null) {
                        attributeControls = new HashMap<String, Map<String, ControlAnalysis>>();
                        mapForId = new HashMap<String, ControlAnalysis>();
                        attributeControls.put(prefixedForAttribute, mapForId);
                    } else {
                        mapForId = attributeControls.get(prefixedForAttribute);
                        if (mapForId == null) {
                            mapForId = new HashMap<String, ControlAnalysis>();
                            attributeControls.put(prefixedForAttribute, mapForId);
                        }
                    }
                    mapForId.put(nameAttribute, controlAnalysis);
                }

                return controlAnalysis;
            }

            public void endVisitControl(Element controlElement, ContainerAnalysis containerControlAnalysis, ControlAnalysis newControl, boolean isContainer) {
                if (isContainer) {
                    ((ContainerAnalysis) newControl).clearContainedVariables();
                }
            }
        });

        // Process deferred external LHHA elements
        for (final Map.Entry<String, Element> entry: deferredExternalLHHA.entrySet()) {
            // Process
            if (!processLHHAElement(propertyContext, controlsDocumentInfo, entry.getValue(), entry.getKey(), prefix)) {
                // Warn if failed
                indentedLogger.logWarning("", "could not find control associated with LHHA element", "element",
                        entry.getValue().getName(), "for", entry.getKey());
            }
        }

        // Extract event handlers for this tree of controls
        // NOTE: Do this after analysing controls above so that XBL bindings are available for detection of nested event handlers.
        extractEventHandlers(propertyContext, xpathConfiguration, controlsDocumentInfo, prefix, true);

        // Gather online/offline information
        {
            {
                // Create list of all the documents to search
                final List<DocumentWrapper> documentInfos = new ArrayList<DocumentWrapper>(modelsByPrefixedId.size() + 1);
                for (final Model model: modelsByPrefixedId.values()) {
                    documentInfos.add(new DocumentWrapper(model.document, null, xpathConfiguration));
                }
                documentInfos.add(controlsDocumentInfo);

                // Search for xxforms:offline which are not within instances
                for (final DocumentWrapper currentDocumentInfo: documentInfos) {
                    hasOfflineSupport |= (Boolean) XPathCache.evaluateSingle(propertyContext, currentDocumentInfo,
                            "exists(//xxforms:offline[not(ancestor::xforms:instance)])", BASIC_NAMESPACE_MAPPINGS,
                            null, null, null, null, locationData);

                    if (hasOfflineSupport) {
                        break;
                    }
                }
            }

            if (hasOfflineSupport) {
                // NOTE: We attempt to localize what triggers can cause, upon DOMActivate, xxforms:online, xxforms:offline and xxforms:offline-save actions
                final List onlineTriggerIds = XPathCache.evaluate(propertyContext, controlsDocumentInfo,
                    "distinct-values(for $handler in for $action in //xxforms:online return ($action/ancestor-or-self::*[@ev:event and tokenize(@ev:event, '\\s+') = 'DOMActivate'])[1]" +
                    "   return for $id in $handler/../descendant-or-self::xforms:trigger/@id return string($id))", BASIC_NAMESPACE_MAPPINGS,
                    null, null, null, null, locationData);

                final List offlineTriggerIds = XPathCache.evaluate(propertyContext, controlsDocumentInfo,
                    "distinct-values(for $handler in for $action in //xxforms:offline return ($action/ancestor-or-self::*[@ev:event and tokenize(@ev:event, '\\s+') = 'DOMActivate'])[1]" +
                    "   return for $id in $handler/../descendant-or-self::xforms:trigger/@id return string($id))", BASIC_NAMESPACE_MAPPINGS,
                    null, null, null, null, locationData);

                final List offlineSaveTriggerIds = XPathCache.evaluate(propertyContext, controlsDocumentInfo,
                    "distinct-values(for $handler in for $action in //xxforms:offline-save return ($action/ancestor-or-self::*[@ev:event and tokenize(@ev:event, '\\s+') = 'DOMActivate'])[1]" +
                    "   return for $id in $handler/../descendant-or-self::xforms:trigger/@id return string($id))", BASIC_NAMESPACE_MAPPINGS,
                    null, null, null, null, locationData);

                offlineInsertTriggerIds = XPathCache.evaluate(propertyContext, controlsDocumentInfo,
                    "distinct-values(for $handler in for $action in //xforms:insert return ($action/ancestor-or-self::*[@ev:event and tokenize(@ev:event, '\\s+') = 'DOMActivate'])[1]" +
                    "   return for $id in $handler/../descendant-or-self::xforms:trigger/@id return string($id))", BASIC_NAMESPACE_MAPPINGS,
                    null, null, null, null, locationData);

                final List offlineDeleteTriggerIds = XPathCache.evaluate(propertyContext, controlsDocumentInfo,
                    "distinct-values(for $handler in for $action in //xforms:delete return ($action/ancestor-or-self::*[@ev:event and tokenize(@ev:event, '\\s+') = 'DOMActivate'])[1]" +
                    "   return for $id in $handler/../descendant-or-self::xforms:trigger/@id return string($id))", BASIC_NAMESPACE_MAPPINGS,
                    null, null, null, null, locationData);

                for (Object onlineTriggerId: onlineTriggerIds) {
                    final String currentId = (String) onlineTriggerId;
                    controlAnalysisMap.get(prefix + currentId).addClasses("xxforms-online");
                }

                for (Object offlineTriggerId: offlineTriggerIds) {
                    final String currentId = (String) offlineTriggerId;
                    controlAnalysisMap.get(prefix + currentId).addClasses("xxforms-offline");
                }

                for (Object offlineSaveTriggerId: offlineSaveTriggerIds) {
                    final String currentId = (String) offlineSaveTriggerId;
                    controlAnalysisMap.get(prefix + currentId).addClasses("xxforms-offline-save");
                }

                for (final String currentId: offlineInsertTriggerIds) {
                    controlAnalysisMap.get(prefix + currentId).addClasses("xxforms-offline-insert");
                }

                for (Object offlineDeleteTriggerId: offlineDeleteTriggerIds) {
                    final String currentId = (String) offlineDeleteTriggerId;
                    controlAnalysisMap.get(prefix + currentId).addClasses("xxforms-offline-delete");
                }
            }
        }
    }

    private boolean processLHHAElement(PropertyContext propertyContext, DocumentWrapper controlsDocumentInfo, Element lhhaElement, String controlStaticId, String prefix) {
        final String forAttribute = lhhaElement.attributeValue("for");
        if (forAttribute == null) {
            // NOP: container control handles this itself
            return true;
        } else {
            // Find prefixed id of control with assumption that it is in the same scope as the LHHA element
            final XBLBindings.Scope lhhaScope = xblBindings.getResolutionScopeByPrefixedId(prefix + controlStaticId);
            final String controlPrefixedId = lhhaScope.getPrefixedIdForStaticId(forAttribute);

            final ControlAnalysis controlAnalysis = controlAnalysisMap.get(controlPrefixedId);
            if (controlAnalysis != null) {
                // Control is already known
                controlAnalysis.setExternalLHHA(propertyContext, controlsDocumentInfo, lhhaElement);
                return true;
            } else {
                // Control is not already known or doesn't exist at all, try later
                return false;
            }
        }
    }

    public static List<Document> extractNestedModels(PropertyContext pipelineContext, DocumentWrapper compactShadowTreeWrapper, boolean detach, LocationData locationData) {

        final List<Document> result = new ArrayList<Document>();

        final List modelElements = XPathCache.evaluate(pipelineContext, compactShadowTreeWrapper,
                "//xforms:model[not(ancestor::xforms:instance)]",
                BASIC_NAMESPACE_MAPPINGS, null, null, null, null, locationData);

        if (modelElements.size() > 0) {
            for (Object modelElement : modelElements) {
                final NodeInfo currentNodeInfo = (NodeInfo) modelElement;
                final Element currentModelElement = (Element) ((NodeWrapper) currentNodeInfo).getUnderlyingNode();

                final Document modelDocument = Dom4jUtils.createDocumentCopyParentNamespaces(currentModelElement, detach);
                result.add(modelDocument);
            }
        }

        return result;
    }
    
    public void appendClasses(StringBuilder sb, String prefixedId) {
        final String controlClasses = controlAnalysisMap.get(prefixedId).getClasses();
        if ((controlClasses == null))
            return;

        if (sb.length() > 0)
            sb.append(' ');

        sb.append(controlClasses);
    }

    public List<String> getOfflineInsertTriggerIds() {
        return offlineInsertTriggerIds;
    }

    /**
     * Returns whether there is any event handler registered anywhere in the controls for the given event name.
     *
     * @param eventName event name, like xforms-value-changed
     * @return          true if there is a handler, false otherwise
     */
    public boolean hasHandlerForEvent(String eventName) {
        return hasHandlerForEvent(eventName, true);
    }

    /**
     * Returns whether there is any event handler registered anywhere in the controls for the given event name.
     *
     * @param eventName         event name, like xforms-value-changed
     * @param includeAllEvents  whether to include #all
     * @return          true if there is a handler, false otherwise
     */
    public boolean hasHandlerForEvent(String eventName, boolean includeAllEvents) {
        // Check for #all as well if includeAllEvents is true
        return (includeAllEvents && eventNames.contains(XFormsConstants.XXFORMS_ALL_EVENTS)) || eventNames.contains(eventName);
    }

    public List<XFormsEventHandler> getKeyHandlers() {
        return keyHandlers;
    }

    /**
     * Statically create and register an event handler.
     *
     * @param newEventHandlerImpl           event handler implementation
     * @param observersPrefix               prefix of observers, e.g. "" or "foo$bar$"
     */
    public void registerActionHandler(XFormsEventHandlerImpl newEventHandlerImpl, String observersPrefix) {

        // Register event handler
        final String[] observersStaticIds = newEventHandlerImpl.getObserversStaticIds();
        if (observersStaticIds.length > 0) {
            // There is at least one observer
            for (final String currentObserverStaticId: observersStaticIds) {
                // NOTE: Handle special case of global id on containing document
                final String currentObserverPrefixedId
                        = XFormsContainingDocument.CONTAINING_DOCUMENT_PSEUDO_ID.equals(currentObserverStaticId)
                        ? currentObserverStaticId : observersPrefix + currentObserverStaticId;

                // Get handlers for observer
                final List<XFormsEventHandler> eventHandlersForObserver;
                {
                    final List<XFormsEventHandler> currentList = eventHandlersMap.get(currentObserverPrefixedId);
                    if (currentList == null) {
                        eventHandlersForObserver = new ArrayList<XFormsEventHandler>();
                        eventHandlersMap.put(currentObserverPrefixedId, eventHandlersForObserver);
                    } else {
                        eventHandlersForObserver = currentList;
                    }
                }

                // Add event handler
                eventHandlersForObserver.add(newEventHandlerImpl);
            }

            // Remember closest ancestor observer for all nested actions
            // This is used to find the closest ancestor control, which in turn is used to find the repeat hierarchy
            {
                final String prefix = newEventHandlerImpl.getPrefix();
                final String ancestorObserverPrefixedId = prefix + newEventHandlerImpl.getAncestorObserverStaticId();
                eventHandlerAncestorsMap.put(prefix + newEventHandlerImpl.getStaticId(), ancestorObserverPrefixedId);

                Dom4jUtils.visitSubtree(newEventHandlerImpl.getEventHandlerElement(), new Dom4jUtils.VisitorListener() {
                    public void startElement(Element element) {
                        final String id = XFormsUtils.getElementStaticId(element);
                        if (id != null)
                            eventHandlerAncestorsMap.put(prefix + id, ancestorObserverPrefixedId);
                    }

                    public void endElement(Element element) {}
                    public void text(Text text) {}
                });
            }

            // Remember all event names
            if (newEventHandlerImpl.isAllEvents()) {
                eventNames.add(XFormsConstants.XXFORMS_ALL_EVENTS);
            } else {
                for (final String eventName: newEventHandlerImpl.getEventNames()) {
                    eventNames.add(eventName);
                    // Remember specially keypress events (could have eventNames<String, List<XFormsEventHandlerImpl>)
                    // instead of separate list, if useful for more events
                    if (XFormsEvents.KEYPRESS.equals(eventName))
                        keyHandlers.add(newEventHandlerImpl);
                }
            }
        }
    }

    /**
     * Visit all the control elements without handling repeats or looking at the binding contexts. This is done entirely
     * statically. Only controls are visited, including grouping controls, leaf controls, and components.
     */
    private void visitAllControlStatic(Element startElement, ContainerAnalysis startControlAnalysis, ControlElementVisitorListener controlElementVisitorListener) {
        handleControlsStatic(controlElementVisitorListener, startElement, startControlAnalysis);
    }

    private void handleControlsStatic(ControlElementVisitorListener controlElementVisitorListener, Element container, ContainerAnalysis containerControlAnalysis) {
        for (final Element currentElement: Dom4jUtils.elements(container)) {

            final String elementName = currentElement.getName();
            final String elementStaticId = XFormsUtils.getElementStaticId(currentElement);

            if (XFormsControlFactory.isContainerControl(currentElement.getNamespaceURI(), elementName)) {
                // Handle XForms grouping controls
                final ContainerAnalysis newContainer = (ContainerAnalysis) controlElementVisitorListener.startVisitControl(currentElement, containerControlAnalysis, elementStaticId, true);
                handleControlsStatic(controlElementVisitorListener, currentElement, newContainer);
                controlElementVisitorListener.endVisitControl(currentElement, containerControlAnalysis, newContainer, true);
            } else if (XFormsControlFactory.isCoreControl(currentElement.getNamespaceURI(), elementName)
                    || xblBindings.isComponent(currentElement.getQName())
                    || elementName.equals(XFormsConstants.XXFORMS_VARIABLE_NAME)) {
                // Handle core control, component, or variable
                final ControlAnalysis newControl = controlElementVisitorListener.startVisitControl(currentElement, containerControlAnalysis, elementStaticId, false);
                controlElementVisitorListener.endVisitControl(currentElement, containerControlAnalysis, newControl, false);
            } else if (XFormsControlFactory.isLHHA(currentElement.getNamespaceURI(), elementName)) {
                // LHHA element within container
                if (!(containerControlAnalysis instanceof ComponentAnalysis))
                    controlElementVisitorListener.handleLHHA(currentElement, elementStaticId);
            }
        }
    }

    private static interface ControlElementVisitorListener {
        ControlAnalysis startVisitControl(Element controlElement, ContainerAnalysis containerControlAnalysis, String controlStaticId, boolean isContainer);
        void endVisitControl(Element controlElement, ContainerAnalysis containerControlAnalysis, ControlAnalysis newControl, boolean isContainer);
        void handleLHHA(Element lhhaElement, String controlStaticId);
    }

    /**
     * Find the closest common ancestor repeat given two prefixed ids.
     *
     * @param prefixedId1   first control prefixed id
     * @param prefixedId2   second control prefixed id
     * @return              prefixed id of common ancestor repeat, or null if not found
     */
    public String findClosestCommonAncestorRepeat(String prefixedId1, String prefixedId2) {
        final List<String> ancestors1 = getAncestorRepeats(prefixedId1, null);
        final List<String> ancestors2 = getAncestorRepeats(prefixedId2, null);

        // If one of them has no ancestors, there is no common ancestor
        if (ancestors1.size() == 0 || ancestors2.size() == 0)
            return null;

        Collections.reverse(ancestors1);
        Collections.reverse(ancestors2);

        final Iterator<String> iterator1 = ancestors1.iterator();
        final Iterator<String> iterator2 = ancestors2.iterator();

        String result = null;
        while (iterator1.hasNext() && iterator2.hasNext()) {
            final String repeatId1 = iterator1.next();
            final String repeatId2 = iterator2.next();

            if (!repeatId1.equals(repeatId2))
                break;

            result = repeatId1;
        }
        
        return result;
    }

    /**
     * Get prefixed ids of all of the start control's repeat ancestors, stopping at endPrefixedId if not null. If
     * endPrefixedId is a repeat, it is excluded. If the source doesn't exist, return the empty list.
     *
     * @param startPrefixedId   prefixed id of start control or start action within control
     * @param endPrefixedId     prefixed id of end repeat, or null
     * @return                  list of prefixed id from leaf to root, or the empty list
     */
    public List<String> getAncestorRepeats(String startPrefixedId, String endPrefixedId) {

        // Try control infos
        ControlAnalysis controlAnalysis = controlAnalysisMap.get(startPrefixedId);
        if (controlAnalysis == null) {
            // Not found, so try actions
            final String newStartPrefixedId = eventHandlerAncestorsMap.get(startPrefixedId);
            controlAnalysis = controlAnalysisMap.get(newStartPrefixedId);
        }

        // Simple case where source doesn't exist
        if (controlAnalysis == null)
            return Collections.emptyList();

        // Simple case where there is no ancestor repeat
        RepeatAnalysis repeatControlAnalysis = controlAnalysis.getAncestorRepeat();
        if (repeatControlAnalysis == null)
            return Collections.emptyList();

        // At least one ancestor repeat
        final List<String> result = new ArrayList<String>();
        // Go until there are no more ancestors OR we find the boundary repeat
        while (repeatControlAnalysis != null && (endPrefixedId == null || !endPrefixedId.equals(repeatControlAnalysis.prefixedId)) ) {
            result.add(repeatControlAnalysis.prefixedId);
            repeatControlAnalysis = repeatControlAnalysis.getAncestorRepeat();
        }
        return result;
    }

    public SAXStore.Mark getElementMark(String prefixedId) {
        return metadata.marks.get(prefixedId);
    }

    public ControlAnalysis getControlAnalysis(String prefixedId) {
        return controlAnalysisMap.get(prefixedId);
    }

    // This for debug only
    public void dumpAnalysis() {
        if (isXPathAnalysis()) {
            for (final ControlAnalysis info: controlAnalysisMap.values()) {
                final String pad = "                                           ".substring(0, info.getLevel());

                System.out.println(pad + "- Control ----------------------------------------------------------------------");
                System.out.println(pad + info.prefixedId);
                if (info.bindingAnalysis != null) {
                    System.out.println(pad + "- Binding ----------------------------------------------------------------------");
                    info.bindingAnalysis.dump(System.out, info.getLevel());
                }
                if (info.valueAnalysis != null) {
                    System.out.println(pad + "- Value ------------------------------------------------------------------------");
                    info.valueAnalysis.dump(System.out, info.getLevel());
                }
            }

            for (final Model model: modelsByPrefixedId.values()) {
                if (model.figuredBindAnalysis) {
                    final String pad = "";
                    System.out.println(pad + "- Model ----------------------------------------------------------------------");
                    System.out.println(pad + model.prefixedId);
                    if (model.computedBindExpressionsInstances!= null) {
                        System.out.println(pad + "- Computed binds instances ----------------------------------------------------------------------");
                        System.out.println(model.computedBindExpressionsInstances.toString());
                    }
                    if (model.validationBindInstances != null) {
                        System.out.println(pad + "- Validation binds instances ------------------------------------------------------------------------");
                        System.out.println(model.validationBindInstances.toString());
                    }
                }
            }
        }
    }

    public DocumentWrapper getDefaultDocumentWrapper() {
        return documentWrapper;
    }

    public final NodeInfo DUMMY_CONTEXT;
    {
        try {
            final TinyBuilder treeBuilder = new TinyBuilder();
            final TransformerHandler identity = TransformerUtils.getIdentityTransformerHandler(xpathConfiguration);
            identity.setResult(treeBuilder);

            identity.startDocument();
            identity.endDocument();

            DUMMY_CONTEXT = treeBuilder.getCurrentRoot();
        } catch (SAXException e) {
            throw new OXFException(e);
        }
    }

    // If there is no XPath context defined at the root (in the case there is no default XForms model/instance
    // available), we should use an empty context. However, currently for non-relevance in particular we must not run
    // expressions with an empty context. To allow running expressions at the root of a container without models, we
    // create instead a context with an empty document node instead. This way there is a context for evaluation. In the
    // future, we should allow running expressions with no context, possibly after statically checking that they do not
    // depend on the context, as well as prevent evaluations within non-relevant content by other means.
//    final List<Item> DEFAULT_CONTEXT = XFormsConstants.EMPTY_ITEM_LIST;
    public final List<Item> DEFAULT_CONTEXT = Collections.singletonList((Item) DUMMY_CONTEXT);
}
