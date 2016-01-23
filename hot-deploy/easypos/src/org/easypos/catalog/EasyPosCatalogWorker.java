package org.easypos.catalog;

import org.easypos.category.EasyPosProductTagWorker;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericDelegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.service.*;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

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

    public static Map<String, Object> updateStoreMenu(DispatchContext dctx, Map<String, ? extends Object> context)
            throws GenericEntityException, GenericServiceException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default", delegator);
        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");

        Map<String, Object> serviceResult;
        Map<String, Object> paramMap;

        String catalogId = (String) context.get("catalogId");
        List<String> deleteProductIds = (List<String>) context.get("deleteProductIds");
        List<String> addProductIds = (List<String>) context.get("addProductIds");

        EntityCondition condition1 = EntityCondition.makeCondition("prodCatalogId", catalogId);
        EntityCondition condition2 = EntityCondition.makeCondition("prodCatalogCategoryTypeId", EasyPosProductTagWorker.DEFAULT_PRODUCT_CATALOG_CATEGORY_TYPE);
        List<EntityCondition> findCategoryIdConditions = new ArrayList<>();
        findCategoryIdConditions.add(condition1);
        findCategoryIdConditions.add(condition2);
        GenericValue categoryIdGenericValue = EntityQuery.use(delegator).from("ProdCatalogCategory").where(findCategoryIdConditions).cache(false).queryOne();

        String categoryId = "";
        if (categoryIdGenericValue != null) {
            categoryId = (String) categoryIdGenericValue.get("productCategoryId");
        } else {
            ServiceUtil.returnError("Fail to find the corresponding category id for menu: " + catalogId);
        }

        for (String idToDelete : deleteProductIds) {
            EntityCondition timeStampCondition1 = EntityCondition.makeCondition("productCategoryId", categoryId);
            EntityCondition timeStampCondition2 = EntityCondition.makeCondition("productId", idToDelete);
            List<EntityCondition> timeStampConditions = new ArrayList<>();
            timeStampConditions.add(timeStampCondition1);
            timeStampConditions.add(timeStampCondition2);
            GenericValue timeStampGenericValue = EntityQuery.use(delegator).from("ProductCategoryMember").where(timeStampConditions).cache(false).queryOne();

            Timestamp fromDate = (Timestamp) timeStampGenericValue.get("fromDate");

            paramMap = UtilMisc.toMap(
                    "productCategoryId", categoryId,
                    "productId", idToDelete,
                    "fromDate", fromDate,
                    "userLogin", userLoginGenericValue
            );

            serviceResult = dispatcher.runSync("removeProductFromCategory", paramMap);
            if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
                return ServiceUtil.returnError("Unable to remove product from category: " + categoryId);
            } else {
                Debug.logInfo("Removed " + idToDelete + " from " + categoryId, "EasyPosCatalogWorker");
            }
        }

        for (String idToAdd : addProductIds) {
            paramMap = UtilMisc.toMap(
                    "productCategoryId", categoryId,
                    "productId", idToAdd,
                    "fromDate", new Timestamp( (new Date()).getTime()),
                    "userLogin", userLoginGenericValue
            );

            serviceResult = dispatcher.runSync("safeAddProductToCategory", paramMap);
            if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
                return ServiceUtil.returnError("Unable to add product to category: " + idToAdd);
            } else {
                Debug.logInfo("Added " + idToAdd + " to " + categoryId, "EasyPosCatalogWorker");
            }
        }

        Map<String, Object> returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("success", true);

        return returnedValues;
    }
}
