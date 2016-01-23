package org.easypos.product;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.easypos.category.EasyPosProductTagWorker;
import org.easypos.party.EasyPosPartyWorker;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericDelegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.jdbc.SQLProcessor;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.service.*;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

public class EasyPosProductWorker {

    private static final String DEFAULT_PRODUCT_TYPE_ID = "FINISHED_GOOD";
    private static final String DEFAULT_REQUIRE_INVENTORY = "N";

    public static final String DEFAULT_PRODUCT_PRICE_TYPE = "DEFAULT_PRICE";
    private static final String DEFAULT_PRODUCT_PRICE_PURPOSE = "PURCHASE";
    private static final String DEFAULT_PRICE_WITH_TAX_INCLUDED = "N";
    private static final String DEFAULT_PRODUCT_STORE_GROUP_ID = "_NA_";

    public static final String PRODUCT_TAG_DELIMITER = ",";

    public static final String PRODUCT_TAG_ATTR_TYPE = "TAG";

    private static final String GET_PRODUCTS_PER_OWNER_SQL = "./hot-deploy/easypos/sql/GetAllProductsPerOwner.sql";
    private static final String GET_PRODUCTS_PER_STORE_MENU_SQL = "./hot-deploy/easypos/sql/GetAllProductsPerStore.sql";

    public static Map<String, Object> addNewProduct(DispatchContext dctx,
                                                    Map<String, ? extends Object> context)
            throws GenericServiceException, GenericEntityException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default", delegator);

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");
        String username = (String) userLoginGenericValue.get("userLoginId");

        Map<String, Object> serviceResult;
        Map<String, Object> returnedValues;
        String newProductId = null;

        //Get all required params
        String internalProductName = ((String) context.get("internalName")).toUpperCase();
        String currencyUomId = (String) context.get("currencyUomId");
        BigDecimal priceBD = (BigDecimal) context.get("price");
        List<String> productTags = (List<String>) context.get("productTags");

        //Create the product
        Map<String, Object> createNewProductParamMap = UtilMisc.toMap(
                "internalName", internalProductName,
                "requireInventory", DEFAULT_REQUIRE_INVENTORY,
                "productTypeId", DEFAULT_PRODUCT_TYPE_ID,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("createProduct", createNewProductParamMap);
        newProductId = (String) serviceResult.get("productId");

        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to create new product: " + internalProductName);
        }
        /*********************************************/

        //Create the product price
        Map<String, Object> createNewProductPriceParamMap = UtilMisc.toMap(
                "productId", newProductId,
                "productPriceTypeId", DEFAULT_PRODUCT_PRICE_TYPE,
                "productPricePurposeId", DEFAULT_PRODUCT_PRICE_PURPOSE,
                "currencyUomId", currencyUomId,
                "productStoreGroupId", DEFAULT_PRODUCT_STORE_GROUP_ID,
                "taxInPrice", DEFAULT_PRICE_WITH_TAX_INCLUDED,
                "price", priceBD,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("createProductPrice", createNewProductPriceParamMap);

        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to add price to product: " + internalProductName);
        }
        /*********************************************/

        //Add product's tags
        serviceResult = addProductTags(productTags, newProductId, dctx, context);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to add tags to product: " + newProductId);
        }
        /*********************************************/

        //set owner of the product
        String ownerPartyId = EasyPosPartyWorker.getOwnerPartyId(delegator, username);

        Map<String, Object> addOwnerToProductParamMap = UtilMisc.toMap(
                "productId", newProductId,
                "partyId", ownerPartyId,
                "roleTypeId", EasyPosPartyWorker.OWNER_ROLE_TYPE_ID,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("addPartyToProduct", addOwnerToProductParamMap);

        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to set product's owner: " + internalProductName);
        }
        /*********************************************/

        returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("newProductId", newProductId);

        return returnedValues;
    }

    public static Map<String, Object> easyPosUpdateProduct(DispatchContext dctx,
                                                    Map<String, ? extends Object> context)
            throws GenericServiceException, GenericEntityException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default", delegator);

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");

        Map<String, Object> serviceResult;
        Map<String, Object> returnedValues;

        //Get all required params
        String productId = ((String) context.get("productId"));
        String newProductName = (String) context.get("internalName");
        String currencyUomId = (String) context.get("currencyUomId");
        String priceString = (String) context.get("price");
        List<String> productTags = (List<String>) context.get("productTags");

        if (!StringUtils.isEmpty(newProductName)) {
            newProductName = newProductName.toUpperCase();

            //update the product name
            Map<String, Object> updateNameParamMap = UtilMisc.toMap(
                    "productId", productId,
                    "internalName", newProductName,
                    "requireInventory", DEFAULT_REQUIRE_INVENTORY,
                    "productTypeId", DEFAULT_PRODUCT_TYPE_ID,
                    "userLogin", userLoginGenericValue
            );

            serviceResult = dispatcher.runSync("updateProduct", updateNameParamMap);

            if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
                return ServiceUtil.returnError("Unable to update product name: " + productId);
            }
            /*********************************************/
        }

        BigDecimal priceBD = null;
        try {
            priceBD = new BigDecimal(priceString);
        } catch (NumberFormatException ex) {
            Debug.logInfo("Failed to parse price: " + priceString, "EasyPosProductWorker");
        }

        if (priceBD != null && StringUtils.isNotEmpty(currencyUomId)) {
            List<EntityCondition> getFromDateConditions = new ArrayList<>();
            getFromDateConditions.add(EntityCondition.makeCondition("productId", productId));
            getFromDateConditions.add(EntityCondition.makeCondition("productPriceTypeId", DEFAULT_PRODUCT_PRICE_TYPE));
            getFromDateConditions.add(EntityCondition.makeCondition("productPricePurposeId", DEFAULT_PRODUCT_PRICE_PURPOSE));
            getFromDateConditions.add(EntityCondition.makeCondition("currencyUomId", currencyUomId));
            getFromDateConditions.add(EntityCondition.makeCondition("productStoreGroupId", DEFAULT_PRODUCT_STORE_GROUP_ID));
            GenericValue fromDateGenericValue = EntityQuery.use(delegator).from("ProductPrice").where(getFromDateConditions).cache(false).queryOne();

            //Create the product price
            Map<String, Object> updatePriceParamMap = UtilMisc.toMap(
                    "productId", productId,
                    "productPriceTypeId", DEFAULT_PRODUCT_PRICE_TYPE,
                    "productPricePurposeId", DEFAULT_PRODUCT_PRICE_PURPOSE,
                    "currencyUomId", currencyUomId,
                    "productStoreGroupId", DEFAULT_PRODUCT_STORE_GROUP_ID,
                    "price", priceBD,
                    "fromDate", fromDateGenericValue.get("fromDate"),
                    "userLogin", userLoginGenericValue
            );

            serviceResult = dispatcher.runSync("updateProductPrice", updatePriceParamMap);

            if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
                return ServiceUtil.returnError("Unable to update price to product: " + productId);
            }
            /*********************************************/
        }

        if (productTags != null && !productTags.isEmpty()) {
            // DELETE ALL TAGS first
            List<GenericValue> tagsGenericValue = EntityQuery.use(delegator).from("ProductAttribute").where("productId", productId).cache(false).queryList();
            if (tagsGenericValue != null) {
                for (GenericValue value : tagsGenericValue) {
                    Map<String, Object> addProductTagParamMap = UtilMisc.toMap(
                            "productId", productId,
                            "attrName", value.get("attrName"),
                            "userLogin", userLoginGenericValue
                    );

                    serviceResult = dispatcher.runSync("deleteProductAttribute", addProductTagParamMap);
                    if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
                        return ServiceUtil.returnError("Unable to delete product's tag: " + value.get("attrName"));
                    }
                }
            }

            // ADD the NEW ones in
            serviceResult = addProductTags(productTags, productId, dctx, context);
            if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
                return ServiceUtil.returnError("Unable to add tags to product: " + productId);
            }
        }

        returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("success", true);

        return returnedValues;
    }

    private static Map<String, Object> addProductTags(List<String> tags,
                                                      String productId,
                                                      DispatchContext dctx,
                                                      Map<String, ? extends Object> context)
            throws GenericServiceException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default", delegator);

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");


        for (String productTag : tags) {
            Map<String, Object> addProductTagParamMap = UtilMisc.toMap(
                    "productId", productId,
                    "attrName", productTag.toUpperCase(),
                    "attrValue", productTag.toUpperCase(),
                    "attrType", PRODUCT_TAG_ATTR_TYPE,
                    "userLogin", userLoginGenericValue
            );

            Map<String, Object> serviceResult = dispatcher.runSync("createProductAttribute", addProductTagParamMap);
            if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
                return ServiceUtil.returnError("Unable to add product's tag: " + productTag);
            }
        }

        return ServiceUtil.returnSuccess();
    }

    public static Map<String, Object> addMultipleProductsToCategory(DispatchContext dctx, Map<String, ? extends Object> context)
            throws GenericServiceException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default", delegator);

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");

        Map<String, Object> paramMap;
        Map<String, Object> serviceResult;
        Map<String, Object> returnedValues;

        List<String> productsAddedList = new ArrayList<>();

        String[] productIdList = ((String) context.get("productIdList")).split(EasyPosProductWorker.PRODUCT_TAG_DELIMITER);
        String categoryId = (String) context.get("categoryId");

        //add products to the default category
        for (String productId : productIdList) {
            paramMap = UtilMisc.toMap(
                    "productCategoryId", categoryId,
                    "productId", productId,
                    "fromDate", new Timestamp( (new Date()).getTime()),
                    "userLogin", userLoginGenericValue
            );

            serviceResult = dispatcher.runSync("safeAddProductToCategory", paramMap);
            if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
                return ServiceUtil.returnError("Unable to add product to default category: " + productId);
            }

            productsAddedList.add(productId);
        }
        /*********************************************/

        returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("productsAdded", productsAddedList);

        return returnedValues;
    }

    public static Map<String, Object> getAllProductsPerOwner(DispatchContext dctx,
                                                    Map<String, ? extends Object> context)
            throws IOException, GenericEntityException, SQLException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");
        String username = (String) userLoginGenericValue.get("userLoginId");

        Map<String, Object> returnedValues;

        SQLProcessor sqlProcessor = new SQLProcessor(delegator, delegator.getGroupHelperInfo("org.ofbiz"));
        String sql = FileUtils.readFileToString(new File(GET_PRODUCTS_PER_OWNER_SQL) );
        sqlProcessor.prepareStatement(sql);
        PreparedStatement preparedStatement = sqlProcessor.getPreparedStatement();
        preparedStatement.setString(1, username);
        preparedStatement.setString(2, EasyPosPartyWorker.OWNER_ROLE_TYPE_ID);
        preparedStatement.setString(3, DEFAULT_PRODUCT_PRICE_TYPE);
        preparedStatement.setString(4, DEFAULT_PRODUCT_PRICE_PURPOSE);
        preparedStatement.setString(5, PRODUCT_TAG_ATTR_TYPE);

        ResultSet rs = sqlProcessor.executeQuery();
        Collection<Product> productCollection = getProductsFromResultSet(rs);

        List<Product> returnedProducts = new ArrayList<>();
        returnedProducts.addAll(productCollection);

        returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("products", returnedProducts);

        return returnedValues;
    }

    public static Map<String, Object> getAllProductsPerStoreAndMenu(DispatchContext dctx,
                                                             Map<String, ? extends Object> context)
            throws IOException, GenericEntityException, SQLException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");
        String username = (String) userLoginGenericValue.get("userLoginId");

        String storeId = (String) context.get("storeId");
        String menuId = (String) context.get("menuId");

        Map<String, Object> returnedValues;

        SQLProcessor sqlProcessor = new SQLProcessor(delegator, delegator.getGroupHelperInfo("org.ofbiz"));
        String sql = FileUtils.readFileToString(new File(GET_PRODUCTS_PER_STORE_MENU_SQL) );
        sqlProcessor.prepareStatement(sql);
        PreparedStatement preparedStatement = sqlProcessor.getPreparedStatement();
        preparedStatement.setString(1, storeId);
        preparedStatement.setString(2, menuId);
        preparedStatement.setString(3, EasyPosProductTagWorker.DEFAULT_PRODUCT_CATALOG_CATEGORY_TYPE);
        preparedStatement.setString(4, DEFAULT_PRODUCT_PRICE_TYPE);
        preparedStatement.setString(5, DEFAULT_PRODUCT_PRICE_PURPOSE);
        preparedStatement.setString(6, PRODUCT_TAG_ATTR_TYPE);

        ResultSet rs = sqlProcessor.executeQuery();
        Collection<Product> productCollection = getProductsFromResultSet(rs);

        List<Product> returnedProducts = new ArrayList<>();
        returnedProducts.addAll(productCollection);

        returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("products", returnedProducts);

        return returnedValues;
    }

    private static Collection<Product> getProductsFromResultSet(ResultSet rs) throws SQLException {
        Map<String, Product> idToProductMap = new HashMap<>();
        while (rs.next()) {
            String productName = (rs.getString("product_name")).toUpperCase();
            String productId = rs.getString("product_id");
            BigDecimal productPrice = rs.getBigDecimal("price");
            String currency = rs.getString("currency_uom_id");
            String tagName = rs.getString("tag_name");
            if (tagName != null) {
                tagName = tagName.toUpperCase();
            }

            Set<String> tags = null;

            if (idToProductMap.containsKey(productId)) {
                tags = idToProductMap.get(productId).getTags();
                tags.add(tagName);
            } else {
                Product product = new Product();
                product.setName(productName);
                product.setId(productId);
                product.setCurrencySymbol(currency);
                product.setPrice(productPrice.floatValue());

                tags = new HashSet<>();
                tags.add(tagName);
                product.setTags(tags);

                idToProductMap.put(productId, product);
            }
        }

        return idToProductMap.values();
    }

    public static class Product {
        private String name;
        private float price;
        private String currencySymbol;
        private Set<String> tags;
        private String id;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public float getPrice() {
            return price;
        }

        public void setPrice(float price) {
            this.price = price;
        }

        public String getCurrencySymbol() {
            return currencySymbol;
        }

        public void setCurrencySymbol(String currencySymbol) {
            this.currencySymbol = currencySymbol;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Set<String> getTags() {
            return tags;
        }

        public void setTags(Set<String> tags) {
            this.tags = tags;
        }
    }
}
