package org.easypos.catalog;

import org.apache.commons.lang.StringUtils;
import org.easypos.category.EasyPosProductTagWorker;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericDelegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.service.*;

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
        Map<String, Object> returnedValues;

        String catalogId = (String) context.get("catalogId");
        List<String> deleteProductIds = (List<String>) context.get("deleteProductIds");
        List<String> addProductIds = (List<String>) context.get("addProductIds");

        if ( (deleteProductIds == null || deleteProductIds.isEmpty())
                && (addProductIds == null || addProductIds.isEmpty())
                && StringUtils.isEmpty(catalogId)) {
            returnedValues = ServiceUtil.returnSuccess();
            returnedValues.put("success", false);

            return returnedValues;
        }

        if (deleteProductIds == null) {
            deleteProductIds = new ArrayList<>();
        }
        if (addProductIds == null) {
            addProductIds = new ArrayList<>();
        }

        EntityCondition condition1 = EntityCondition.makeCondition("prodCatalogId", catalogId);
        EntityCondition condition2 = EntityCondition.makeCondition("prodCatalogCategoryTypeId", EasyPosProductTagWorker.DEFAULT_PRODUCT_CATALOG_CATEGORY_TYPE);
        List<EntityCondition> findCategoryIdConditions = new ArrayList<>();
        findCategoryIdConditions.add(condition1);
        findCategoryIdConditions.add(condition2);
        GenericValue categoryIdGenericValue = EntityQuery.use(delegator).from("ProdCatalogCategory").where(findCategoryIdConditions).cache(false).queryOne();

        String categoryId = "";
        if (categoryIdGenericValue != null) {
            categoryId = (String) categoryIdGenericValue.get("productCategoryId");
        }
        if (StringUtils.isEmpty(categoryId)) {
            returnedValues = ServiceUtil.returnSuccess();
            returnedValues.put("success", false);

            return returnedValues;
        }

        /*************DELETION***********/
        EntityCondition timeStampCondition1 = EntityCondition.makeCondition("productCategoryId", categoryId);
        EntityCondition timeStampCondition2 = EntityCondition.makeCondition("productId", EntityOperator.IN, deleteProductIds);
        List<EntityCondition> timeStampConditions = new ArrayList<>();
        timeStampConditions.add(timeStampCondition1);
        timeStampConditions.add(timeStampCondition2);
        List<GenericValue> timeStampGenericValues = EntityQuery.use(delegator).from("ProductCategoryMember").where(timeStampConditions).cache(false).queryList();

        if (timeStampGenericValues != null) {
            for (GenericValue value : timeStampGenericValues) {
                paramMap = UtilMisc.toMap(
                        "productCategoryId", categoryId,
                        "productId", value.get("productId"),
                        "fromDate", value.get("fromDate"),
                        "userLogin", userLoginGenericValue
                );

                serviceResult = dispatcher.runSync("removeProductFromCategory", paramMap);
                if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
                    return ServiceUtil.returnError("Unable to remove product from category: " + categoryId);
                } else {
                    Debug.logInfo("Removed " + value.get("productId") + " from " + categoryId, "EasyPosCatalogWorker");
                }
            }
        }
        ////////////////////////////////////////

        /*************ADDITION***********/
        timeStampCondition2 = EntityCondition.makeCondition("productId", EntityOperator.IN, addProductIds);
        timeStampConditions.clear();
        timeStampConditions.add(timeStampCondition1);
        timeStampConditions.add(timeStampCondition2);
        List<GenericValue> genericValues = EntityQuery.use(delegator).from("ProductCategoryMember").where(timeStampConditions).cache(false).queryList();

        for (GenericValue value : genericValues) {
            String foundId = (String) value.get("productId");
            addProductIds.remove(foundId);

            Debug.logInfo("Product already exists, NOT adding: " + foundId, "EasyPosCatalogWorker");
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
        ////////////////////////////////////////

        returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("success", true);

        return returnedValues;
    }
}
