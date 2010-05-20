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

import org.orbeon.oxf.cache.Cache;
import org.orbeon.oxf.cache.InternalCacheKey;
import org.orbeon.oxf.cache.ObjectCache;
import org.orbeon.oxf.util.PropertyContext;
import org.orbeon.oxf.util.UUIDUtils;
import org.orbeon.oxf.xforms.XFormsContainingDocument;
import org.orbeon.oxf.xforms.XFormsProperties;

/**
 * This cache stores mappings XFormsState -> XFormsContainingDocument into a global cache.
 */
public class XFormsDocumentCache {

//    private static final String LOG_TYPE = "containing document cache";

    private static final String XFORMS_DOCUMENT_CACHE_NAME = "xforms.cache.documents";
    private static final int XFORMS_DOCUMENT_CACHE_DEFAULT_SIZE = 10;

    private static final Long CONSTANT_VALIDITY = (long) 0;
    private static final String CONTAINING_DOCUMENT_KEY_TYPE = XFORMS_DOCUMENT_CACHE_NAME;

    private static XFormsDocumentCache instance = null;

    public synchronized static XFormsDocumentCache instance() {
        if (instance == null) {
            instance = new XFormsDocumentCache();
        }
        return instance;
    }

    private final Cache cache = ObjectCache.instance(XFORMS_DOCUMENT_CACHE_NAME, XFORMS_DOCUMENT_CACHE_DEFAULT_SIZE);

    private XFormsDocumentCache() {}

    /**
     * Add a document to the cache using the document's current state as cache key.
     *
     * @param propertyContext       current context
     * @param containingDocument    document to store
     */
    public synchronized void storeDocument(PropertyContext propertyContext, XFormsContainingDocument containingDocument) {
        final InternalCacheKey cacheKey = createCacheKey(containingDocument.getXFormsState());
        cache.add(propertyContext, cacheKey, CONSTANT_VALIDITY, containingDocument);
    }

    /**
     * Find a document and remove it from the cache. If not found, return null.
     *
     * @param propertyContext       current context
     * @param xformsState           state used to search cache
     * @return                      document or null
     */
    public synchronized XFormsContainingDocument getDocument(PropertyContext propertyContext, XFormsState xformsState) {
        final InternalCacheKey cacheKey = createCacheKey(xformsState);
        final XFormsContainingDocument result = (XFormsContainingDocument) cache.findValid(propertyContext, cacheKey, CONSTANT_VALIDITY);

        if (result != null)
            cache.remove(propertyContext, cacheKey);

        return result;
    }

    private InternalCacheKey createCacheKey(XFormsState xformsState) {
        // NOTE: For special Ajax test, key by static state only.
        final String cacheKeyString = XFormsProperties.isAjaxTest() ? xformsState.getStaticState() : xformsState.toString();

        // Make sure that we are getting a UUID combination back
        assert cacheKeyString.length() == (XFormsProperties.isAjaxTest() ? UUIDUtils.UUID_LENGTH : UUIDUtils.UUID_LENGTH * 2 + 1);

        return new InternalCacheKey(CONTAINING_DOCUMENT_KEY_TYPE, cacheKeyString);
    }
}
