package org.easypos.catalog;

import org.ofbiz.entity.GenericDelegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityQuery;

public class EasyPosCatalogWorker {

    public static final String DEFAULT_CATALOG = "DefaultCatalog";

    public static String getDefaultCatalogId(GenericDelegator delegator) throws GenericEntityException {
        String prodCatalogId = null;

        GenericValue queryResult = EntityQuery.use(delegator).from("ProdCatalog").where("prodCatalogId", DEFAULT_CATALOG).cache(true).queryOne();
        if (queryResult != null) {
            prodCatalogId = (String) queryResult.get("prodCatalogId");
        }

        return prodCatalogId;
    }
}
